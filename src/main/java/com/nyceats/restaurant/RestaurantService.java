package com.nyceats.restaurant;

import com.nyceats.restaurant.Dtos.*;
import com.nyceats.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RestaurantService {

    private final RestaurantRepository restaurants;
    private final VisitRepository visits;

    public RestaurantService(RestaurantRepository restaurants, VisitRepository visits) {
        this.restaurants = restaurants;
        this.visits = visits;
    }

    @Transactional(readOnly = true)
    public List<RestaurantResponse> list(String q, String borough, String sort) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Comparator<RestaurantResponse> order = switch (sort == null ? "recent" : sort) {
            case "rating" -> Comparator.comparing(RestaurantResponse::averageRating,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "name" -> Comparator.comparing((RestaurantResponse r) -> r.name().toLowerCase(Locale.ROOT));
            case "visits" -> Comparator.comparingInt(RestaurantResponse::visitCount).reversed();
            default -> Comparator.comparing(RestaurantResponse::lastVisitedOn,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return restaurants.findAllWithVisits().stream()
                .map(RestaurantResponse::from)
                .filter(r -> borough == null || borough.isBlank() || borough.equalsIgnoreCase(r.borough()))
                .filter(r -> needle.isEmpty() || matches(r, needle))
                .sorted(order.thenComparing((RestaurantResponse r) -> r.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static boolean matches(RestaurantResponse r, String needle) {
        return contains(r.name(), needle) || contains(r.cuisine(), needle) || contains(r.address(), needle)
                || r.visits().stream().anyMatch(v -> contains(v.comment(), needle));
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Transactional(readOnly = true)
    public RestaurantResponse get(UUID id) {
        return RestaurantResponse.from(load(id));
    }

    /**
     * Creates a restaurant with its first visit. If the place was picked from the nearby list and is
     * already saved (same OpenStreetMap id), the visit is added to the existing restaurant instead.
     */
    @Transactional
    public CreateResult create(CreateRestaurantRequest req) {
        String osmId = blankToNull(req.osmId());
        if (osmId != null) {
            Optional<Restaurant> existing = restaurants.findByOsmId(osmId);
            if (existing.isPresent()) {
                Restaurant r = existing.get();
                r.addVisit(newVisit(req.visit()));
                return new CreateResult(RestaurantResponse.from(restaurants.saveAndFlush(r)), true);
            }
        }
        Restaurant r = new Restaurant();
        r.setName(req.name().trim());
        r.setAddress(blankToNull(req.address()));
        r.setBorough(blankToNull(req.borough()));
        r.setCuisine(normalizeCuisine(req.cuisine()));
        r.setLatitude(req.latitude());
        r.setLongitude(req.longitude());
        r.setOsmId(osmId);
        r.addVisit(newVisit(req.visit()));
        return new CreateResult(RestaurantResponse.from(restaurants.saveAndFlush(r)), false);
    }

    @Transactional
    public RestaurantResponse update(UUID id, UpdateRestaurantRequest req) {
        Restaurant r = load(id);
        if (req.name() != null) r.setName(req.name().trim());
        if (req.address() != null) r.setAddress(blankToNull(req.address()));
        if (req.borough() != null) r.setBorough(blankToNull(req.borough()));
        if (req.cuisine() != null) r.setCuisine(normalizeCuisine(req.cuisine()));
        return RestaurantResponse.from(restaurants.saveAndFlush(r));
    }

    @Transactional
    public void delete(UUID id) {
        restaurants.delete(load(id));
    }

    @Transactional
    public RestaurantResponse addVisit(UUID restaurantId, VisitRequest req) {
        Restaurant r = load(restaurantId);
        r.addVisit(newVisit(req));
        return RestaurantResponse.from(restaurants.saveAndFlush(r));
    }

    @Transactional
    public RestaurantResponse updateVisit(UUID visitId, UpdateVisitRequest req) {
        Visit v = visits.findById(visitId).orElseThrow(() -> new NotFoundException("Visit not found"));
        if (req.rating() != null) v.setRating(req.rating());
        if (req.comment() != null) v.setComment(blankToNull(req.comment()));
        if (req.visitedOn() != null) v.setVisitedOn(req.visitedOn());
        visits.saveAndFlush(v);
        return RestaurantResponse.from(load(v.getRestaurant().getId()));
    }

    /** Deleting the last visit removes the restaurant too, so the list never shows unrated places. */
    @Transactional
    public Optional<RestaurantResponse> deleteVisit(UUID visitId) {
        Visit v = visits.findById(visitId).orElseThrow(() -> new NotFoundException("Visit not found"));
        Restaurant r = v.getRestaurant();
        r.getVisits().remove(v);
        if (r.getVisits().isEmpty()) {
            restaurants.delete(r);
            return Optional.empty();
        }
        return Optional.of(RestaurantResponse.from(restaurants.saveAndFlush(r)));
    }

    @Transactional(readOnly = true)
    public StatsResponse stats() {
        List<Restaurant> all = restaurants.findAllWithVisits();
        List<Visit> allVisits = all.stream().flatMap(r -> r.getVisits().stream()).toList();
        Double avg = allVisits.isEmpty() ? null
                : Math.round(allVisits.stream().mapToInt(Visit::getRating).average().orElse(0) * 10) / 10.0;

        Map<String, Long> boroughCounts = all.stream()
                .collect(Collectors.groupingBy(r -> Objects.requireNonNullElse(r.getBorough(), "Unknown"),
                        TreeMap::new, Collectors.counting()));
        List<BoroughCount> byBorough = boroughCounts.entrySet().stream()
                .map(e -> new BoroughCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(BoroughCount::count).reversed())
                .toList();

        String topCuisine = all.stream()
                .map(Restaurant::getCuisine)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        return new StatsResponse(all.size(), allVisits.size(), avg, byBorough, topCuisine);
    }

    private Restaurant load(UUID id) {
        return restaurants.findByIdWithVisits(id).orElseThrow(() -> new NotFoundException("Restaurant not found"));
    }

    private static Visit newVisit(VisitRequest req) {
        Visit v = new Visit();
        v.setRating(req.rating());
        v.setComment(blankToNull(req.comment()));
        v.setVisitedOn(req.visitedOn() != null ? req.visitedOn() : LocalDate.now(NYC));
        return v;
    }

    private static final java.time.ZoneId NYC = java.time.ZoneId.of("America/New_York");

    private static String normalizeCuisine(String cuisine) {
        String c = blankToNull(cuisine);
        return c == null ? null : c.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
