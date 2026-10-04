package com.nyceats.restaurant;

import com.nyceats.restaurant.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class RestaurantController {

    private final RestaurantService service;

    public RestaurantController(RestaurantService service) {
        this.service = service;
    }

    @GetMapping("/restaurants")
    public List<RestaurantResponse> list(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String borough,
                                         @RequestParam(required = false, defaultValue = "recent") String sort) {
        return service.list(q, borough, sort);
    }

    @GetMapping("/restaurants/{id}")
    public RestaurantResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/restaurants")
    public ResponseEntity<CreateResult> create(@Valid @RequestBody CreateRestaurantRequest req) {
        CreateResult result = service.create(req);
        return ResponseEntity.status(result.existing() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/restaurants/{id}")
    public RestaurantResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRestaurantRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/restaurants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/restaurants/{id}/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantResponse addVisit(@PathVariable UUID id, @Valid @RequestBody VisitRequest req) {
        return service.addVisit(id, req);
    }

    @PatchMapping("/visits/{id}")
    public RestaurantResponse updateVisit(@PathVariable UUID id, @Valid @RequestBody UpdateVisitRequest req) {
        return service.updateVisit(id, req);
    }

    /** Returns the updated restaurant, or 204 if that was its last visit and the restaurant was removed. */
    @DeleteMapping("/visits/{id}")
    public ResponseEntity<RestaurantResponse> deleteVisit(@PathVariable UUID id) {
        return service.deleteVisit(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return service.stats();
    }
}
