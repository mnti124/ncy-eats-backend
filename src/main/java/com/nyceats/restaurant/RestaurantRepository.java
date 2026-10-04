package com.nyceats.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {

    /** Loads every restaurant with its visits in one query (fine for a personal list of hundreds of places). */
    @Query("select distinct r from Restaurant r left join fetch r.visits")
    List<Restaurant> findAllWithVisits();

    @Query("select r from Restaurant r left join fetch r.visits where r.id = :id")
    Optional<Restaurant> findByIdWithVisits(@Param("id") UUID id);

    Optional<Restaurant> findByOsmId(String osmId);
}
