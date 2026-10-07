package com.nyceats.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for the API. Kept together so the contract is easy to read. */
public final class Dtos {

    private Dtos() {}

    public static final String BOROUGH_REGEX = "|Manhattan|Brooklyn|Queens|Bronx|Staten Island"; // empty = clear

    // ---------- requests ----------

    public record VisitRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 2000) String comment,
            @PastOrPresent LocalDate visitedOn
    ) {}

    public record CreateRestaurantRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 300) String address,
            @Pattern(regexp = BOROUGH_REGEX, message = "must be one of the five boroughs") String borough,
            @Size(max = 100) String cuisine,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            // Geoapify place ids ("geoapify/<id>") run well past OSM's old "node/123" length (~100 chars observed).
            @Size(max = 200) String osmId,
            @NotNull @Valid VisitRequest visit
    ) {}

    /** All fields optional: only non-null values are applied. */
    public record UpdateRestaurantRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 300) String address,
            @Pattern(regexp = BOROUGH_REGEX, message = "must be one of the five boroughs") String borough,
            @Size(max = 100) String cuisine
    ) {}

    public record UpdateVisitRequest(
            @Min(1) @Max(5) Integer rating,
            @Size(max = 2000) String comment,
            @PastOrPresent LocalDate visitedOn
    ) {}

    // ---------- responses ----------

    public record VisitResponse(UUID id, int rating, String comment, LocalDate visitedOn, Instant createdAt) {
        static VisitResponse from(Visit v) {
            return new VisitResponse(v.getId(), v.getRating(), v.getComment(), v.getVisitedOn(), v.getCreatedAt());
        }
    }

    public record RestaurantResponse(
            UUID id,
            String name,
            String address,
            String borough,
            String cuisine,
            Double latitude,
            Double longitude,
            String osmId,
            int visitCount,
            Double averageRating,
            LocalDate lastVisitedOn,
            Instant createdAt,
            List<VisitResponse> visits
    ) {
        static RestaurantResponse from(Restaurant r) {
            List<VisitResponse> visits = r.getVisits().stream()
                    .sorted((a, b) -> {
                        int byDate = b.getVisitedOn().compareTo(a.getVisitedOn());
                        if (byDate != 0) return byDate;
                        if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                        return b.getCreatedAt().compareTo(a.getCreatedAt());
                    })
                    .map(VisitResponse::from)
                    .toList();
            Double avg = visits.isEmpty() ? null
                    : Math.round(visits.stream().mapToInt(VisitResponse::rating).average().orElse(0) * 10) / 10.0;
            LocalDate last = visits.isEmpty() ? null : visits.getFirst().visitedOn();
            return new RestaurantResponse(r.getId(), r.getName(), r.getAddress(), r.getBorough(), r.getCuisine(),
                    r.getLatitude(), r.getLongitude(), r.getOsmId(), visits.size(), avg, last, r.getCreatedAt(), visits);
        }
    }

    public record CreateResult(RestaurantResponse restaurant, boolean existing) {}

    public record StatsResponse(
            long places,
            long visits,
            Double averageRating,
            List<BoroughCount> byBorough,
            String topCuisine
    ) {}

    public record BoroughCount(String borough, long count) {}
}
