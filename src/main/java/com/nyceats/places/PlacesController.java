package com.nyceats.places;

import com.nyceats.places.OsmPlacesService.NearbyResult;
import com.nyceats.places.OsmPlacesService.Place;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/places")
@Validated
public class PlacesController {

    private final OsmPlacesService places;

    public PlacesController(OsmPlacesService places) {
        this.places = places;
    }

    /** Food places around the user's current location, nearest first. */
    @GetMapping("/nearby")
    public NearbyResult nearby(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                               @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
                               @RequestParam(defaultValue = "250") int radius) {
        return places.nearby(lat, lng, radius);
    }

    /** Free-text search limited to the five boroughs. */
    @GetMapping("/search")
    public List<Place> search(@RequestParam @Size(min = 2, max = 120) String q,
                              @RequestParam(required = false) Double lat,
                              @RequestParam(required = false) Double lng) {
        return places.search(q, lat, lng);
    }
}
