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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up restaurants via OpenStreetMap — free, no API key:
 *  - Overpass API: food places near a coordinate
 *  - Nominatim:    text search within NYC, and reverse geocoding to guess the borough
 */
@Service
public class OsmPlacesService {

    private static final Logger log = LoggerFactory.getLogger(OsmPlacesService.class);

    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org";
    /** NYC bounding box: west, north, east, south (Nominatim viewbox order). */
    private static final String NYC_VIEWBOX = "-74.2591,40.9176,-73.7004,40.4774";
    private static final List<String> BOROUGHS = List.of("Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

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

    private final RestClient http;
    private final Map<String, CacheEntry> nearbyCache = new ConcurrentHashMap<>();

    public OsmPlacesService(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.osmUserAgent())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public NearbyResult nearby(double lat, double lng, int radiusMeters) {
        int radius = Math.max(50, Math.min(radiusMeters, 1500));
        String key = String.format(Locale.ROOT, "%.4f,%.4f,%d", lat, lng, radius);
        CacheEntry cached = nearbyCache.get(key);
        if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) return cached.value();

        String borough = reverseBorough(lat, lng);
        String query = String.format(Locale.ROOT, """
                [out:json][timeout:15];
                nwr["amenity"~"^(restaurant|cafe|fast_food|bar|pub|ice_cream|food_court|biergarten)$"]["name"](around:%d,%f,%f);
                out center tags 80;
                """, radius, lat, lng);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", query);

        List<Place> places = new ArrayList<>();
        try {
            JsonNode body = http.post().uri(OVERPASS_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
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
        } catch (RestClientException e) {
            log.warn("Overpass lookup failed: {}", e.getMessage());
            throw new PlacesUnavailableException("Couldn't look up nearby places right now. You can still search or add one manually.");
        }

        places.sort(Comparator.comparingInt(Place::distanceMeters));
        NearbyResult result = new NearbyResult(borough, places.stream().limit(40).toList());
        nearbyCache.put(key, new CacheEntry(Instant.now(), result));
        if (nearbyCache.size() > 500) nearbyCache.clear();
        return result;
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
            JsonNode body = http.get().uri(NOMINATIM_URL + "/reverse?format=jsonv2&zoom=14&addressdetails=1&lat={lat}&lon={lng}",
                            lat, lng)
                    .retrieve()
                    .body(JsonNode.class);
            return body == null ? null : boroughFrom(body.path("address"));
        } catch (RestClientException e) {
            log.debug("Reverse geocode failed: {}", e.getMessage());
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
