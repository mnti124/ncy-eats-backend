package com.nyceats.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.nyceats.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Looks up restaurants via OpenStreetMap — free, no API key:
 *  - Overpass API: food places near a coordinate
 *  - Nominatim:    text search within NYC, and reverse geocoding to guess the borough
 */
@Service
public class OsmPlacesService {

    private static final Logger log = LoggerFactory.getLogger(OsmPlacesService.class);

    // overpass-api.de (and its lz4 subdomain — same operator, same firewall) actively refuses
    // connections from many cloud/hosting IP ranges, including Render's: confirmed in production
    // logs as "Connection refused", not a timeout. Kumi Systems is a separately operated instance
    // on different infrastructure, so it's a real fallback rather than hitting the same block twice.
    // (overpass.osm.ch was tried and dropped: its database is permanently stale and it returns
    // HTTP 200 with zero results for every query, which silently looks like "nothing nearby"
    // instead of a visible failure — see isFreshOverpassResponse below for the general guard.)
    private static final List<String> OVERPASS_URLS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter");
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org";
    /** NYC bounding box: west, north, east, south (Nominatim viewbox order). */
    private static final String NYC_VIEWBOX = "-74.2591,40.9176,-73.7004,40.4774";
    private static final List<String> BOROUGHS = List.of("Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** A coordinate's borough never changes, so it's safe to cache for a long time. */
    private static final Duration BOROUGH_CACHE_TTL = Duration.ofHours(12);
    /** Borough is best-effort metadata; never let it hold up the places the map actually needs. */
    private static final Duration BOROUGH_LOOKUP_BUDGET = Duration.ofSeconds(3);

    public record Place(
            String osmId,
            String name,
            String cuisine,
            String address,
            String borough,
            double latitude,
            double longitude,
            Integer distanceMeters
    ) {}

    public record NearbyResult(String borough, List<Place> places) {}

    private record CacheEntry(Instant at, NearbyResult value) {}
    private record BoroughEntry(Instant at, String value) {}

    private final RestClient http;
    private final RestClient boroughHttp;
    private final ExecutorService boroughExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CacheEntry> nearbyCache = new ConcurrentHashMap<>();
    private final Map<String, BoroughEntry> boroughCache = new ConcurrentHashMap<>();

    public OsmPlacesService(AppProperties props) {
        // Kept short per attempt: with multiple Overpass mirrors to fail over across, a slow one
        // should be abandoned quickly rather than tying up the request for a full 20s each.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.osmUserAgent())
                .defaultHeader("Accept", "application/json")
                .build();

        // Separate, short-timeout client for the best-effort borough lookup so a slow Nominatim
        // response can't drag out the whole /nearby call the way sharing `http`'s 20s timeout would.
        SimpleClientHttpRequestFactory boroughFactory = new SimpleClientHttpRequestFactory();
        boroughFactory.setConnectTimeout(Duration.ofSeconds(2));
        boroughFactory.setReadTimeout(Duration.ofSeconds(3));
        this.boroughHttp = RestClient.builder()
                .requestFactory(boroughFactory)
                .defaultHeader("User-Agent", props.osmUserAgent())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public NearbyResult nearby(double lat, double lng, int radiusMeters) {
        int radius = Math.max(50, Math.min(radiusMeters, 1500));
        String key = String.format(Locale.ROOT, "%.4f,%.4f,%d", lat, lng, radius);
        CacheEntry cached = nearbyCache.get(key);
        if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) return cached.value();

        // Borough and places come from two independent services (Nominatim, Overpass) — look them up
        // concurrently instead of one after the other so the map isn't waiting on their combined latency.
        CompletableFuture<String> boroughFuture = CompletableFuture.supplyAsync(() -> boroughCached(lat, lng), boroughExecutor);

        String query = String.format(Locale.ROOT, """
                [out:json][timeout:15];
                nwr["amenity"~"^(restaurant|cafe|fast_food|bar|pub|ice_cream|food_court|biergarten)$"]["name"](around:%d,%f,%f);
                out center tags 80;
                """, radius, lat, lng);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", query);

        JsonNode body = queryOverpass(form);

        String borough = awaitBorough(boroughFuture);
        List<Place> places = new ArrayList<>();
        if (body != null) {
            for (JsonNode el : body.path("elements")) {
                JsonNode tags = el.path("tags");
                double pLat = el.has("lat") ? el.path("lat").asDouble() : el.path("center").path("lat").asDouble();
                double pLng = el.has("lon") ? el.path("lon").asDouble() : el.path("center").path("lon").asDouble();
                places.add(new Place(
                        el.path("type").asText() + "/" + el.path("id").asText(),
                        tags.path("name").asText(),
                        prettyCuisine(text(tags, "cuisine"), text(tags, "amenity")),
                        streetAddress(tags),
                        borough,
                        pLat, pLng,
                        (int) Math.round(haversineMeters(lat, lng, pLat, pLng))));
            }
        }

        places.sort(Comparator.comparingInt(Place::distanceMeters));
        NearbyResult result = new NearbyResult(borough, places.stream().limit(40).toList());
        nearbyCache.put(key, new CacheEntry(Instant.now(), result));
        if (nearbyCache.size() > 500) nearbyCache.clear();
        return result;
    }

    /** Tries each Overpass mirror in turn, returning the first fresh success; throws only if all of them fail. */
    private JsonNode queryOverpass(MultiValueMap<String, String> form) {
        RestClientException lastFailure = null;
        for (String url : OVERPASS_URLS) {
            try {
                JsonNode body = http.post().uri(url)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
                if (isFreshOverpassResponse(body)) return body;
                log.warn("Overpass mirror {} returned a response with no valid data timestamp "
                        + "(likely serving a stale database); trying the next mirror", url);
            } catch (RestClientException e) {
                log.warn("Overpass mirror {} failed: {}", url, e.getMessage());
                lastFailure = e;
            }
        }
        throw new PlacesUnavailableException("Couldn't look up nearby places right now. You can still search or add one manually.",
                lastFailure);
    }

    /**
     * Every genuine Overpass instance stamps its response with the real-world time its database
     * was last updated (osm3s.timestamp_osm_base, e.g. "2026-10-04T19:50:58Z"). A misconfigured or
     * stale instance can still return HTTP 200 with that field garbled and an empty result set —
     * indistinguishable from "no places nearby" unless we check for a plausible timestamp first.
     */
    private static boolean isFreshOverpassResponse(JsonNode body) {
        return body != null && body.path("osm3s").path("timestamp_osm_base").asText("").matches("\\d{4}-\\d{2}-\\d{2}T.*");
    }

    public List<Place> search(String q, Double nearLat, Double nearLng) {
        if (q == null || q.isBlank()) return List.of();
        try {
            JsonNode body = http.get().uri(NOMINATIM_URL + "/search?format=jsonv2&addressdetails=1&extratags=1"
                            + "&limit=10&bounded=1&viewbox={vb}&q={q}", NYC_VIEWBOX, q.trim())
                    .retrieve()
                    .body(JsonNode.class);
            List<Place> out = new ArrayList<>();
            if (body != null) {
                for (JsonNode r : body) {
                    JsonNode addr = r.path("address");
                    double lat = r.path("lat").asDouble();
                    double lng = r.path("lon").asDouble();
                    String name = r.path("name").asText("");
                    if (name.isBlank()) name = r.path("display_name").asText("").split(",")[0];
                    out.add(new Place(
                            r.path("osm_type").asText() + "/" + r.path("osm_id").asText(),
                            name,
                            prettyCuisine(text(r.path("extratags"), "cuisine"), r.path("type").asText(null)),
                            streetAddress(addr),
                            boroughFrom(addr),
                            lat, lng,
                            nearLat != null && nearLng != null
                                    ? (int) Math.round(haversineMeters(nearLat, nearLng, lat, lng)) : null));
                }
            }
            return out;
        } catch (RestClientException e) {
            log.warn("Nominatim search failed: {}", e.getMessage());
            throw new PlacesUnavailableException("Search is unavailable right now. You can add the place manually.");
        }
    }

    /** Best-effort borough for a coordinate; null if it can't be determined. */
    public String reverseBorough(double lat, double lng) {
        try {
            JsonNode body = boroughHttp.get().uri(NOMINATIM_URL + "/reverse?format=jsonv2&zoom=14&addressdetails=1&lat={lat}&lon={lng}",
                            lat, lng)
                    .retrieve()
                    .body(JsonNode.class);
            return body == null ? null : boroughFrom(body.path("address"));
        } catch (RestClientException e) {
            log.debug("Reverse geocode failed: {}", e.getMessage());
            return null;
        }
    }

    /** Reads through the long-lived borough cache; only successful lookups are cached. */
    private String boroughCached(double lat, double lng) {
        String key = String.format(Locale.ROOT, "%.3f,%.3f", lat, lng);
        BoroughEntry cached = boroughCache.get(key);
        if (cached != null && cached.at().plus(BOROUGH_CACHE_TTL).isAfter(Instant.now())) return cached.value();
        String borough = reverseBorough(lat, lng);
        if (borough != null) {
            boroughCache.put(key, new BoroughEntry(Instant.now(), borough));
            if (boroughCache.size() > 2000) boroughCache.clear();
        }
        return borough;
    }

    /** Waits a bounded amount of extra time for the borough lookup; gives up rather than stall the response. */
    private static String awaitBorough(CompletableFuture<String> boroughFuture) {
        try {
            return boroughFuture.get(BOROUGH_LOOKUP_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- helpers ----------

    static String boroughFrom(JsonNode address) {
        if (address == null || address.isMissingNode()) return null;
        for (String field : List.of("borough", "suburb", "city_district", "county", "city")) {
            String b = normalizeBorough(text(address, field));
            if (b != null) return b;
        }
        return null;
    }

    static String normalizeBorough(String raw) {
        if (raw == null) return null;
        String s = raw.replace("County", "").replace("The ", "").trim();
        switch (s) {
            case "New York" -> { return "Manhattan"; }      // New York County
            case "Kings" -> { return "Brooklyn"; }          // Kings County
            case "Richmond" -> { return "Staten Island"; }  // Richmond County
            default -> { }
        }
        for (String b : BOROUGHS) if (b.equalsIgnoreCase(s)) return b;
        return null;
    }

    private static String streetAddress(JsonNode n) {
        String house = firstNonBlank(text(n, "addr:housenumber"), text(n, "house_number"));
        String street = firstNonBlank(text(n, "addr:street"), text(n, "road"));
        if (street == null) return null;
        return house == null ? street : house + " " + street;
    }

    private static String prettyCuisine(String cuisine, String fallbackType) {
        String c = cuisine != null ? cuisine : switch (fallbackType == null ? "" : fallbackType) {
            case "cafe" -> "cafe";
            case "bar", "pub", "biergarten" -> "bar";
            case "fast_food" -> "fast food";
            case "ice_cream" -> "ice cream";
            default -> null;
        };
        if (c == null) return null;
        return c.split(";")[0].replace('_', ' ').trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
