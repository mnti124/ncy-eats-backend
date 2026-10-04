package com.nyceats;

import org.junit.jupiter.api.Test;

import static com.nyceats.places.OsmPlacesServiceTestAccess.haversine;
import static com.nyceats.places.OsmPlacesServiceTestAccess.normalizeBorough;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OsmHelpersTest {

    @Test
    void normalizesBoroughNames() {
        assertThat(normalizeBorough("The Bronx")).isEqualTo("Bronx");
        assertThat(normalizeBorough("Kings County")).isEqualTo("Brooklyn");
        assertThat(normalizeBorough("New York County")).isEqualTo("Manhattan");
        assertThat(normalizeBorough("Queens")).isEqualTo("Queens");
        assertThat(normalizeBorough("Jersey City")).isNull();
    }

    @Test
    void haversineIsRoughlyRight() {
        // Washington Square Arch → Union Square (N side) ≈ 0.7 km
        assertThat(haversine(40.7312, -73.9971, 40.7359, -73.9911)).isCloseTo(720.0, within(150.0));
    }
}
