package com.nyceats.places;

/** Exposes package-private helpers to tests in other packages. */
public final class OsmPlacesServiceTestAccess {
    private OsmPlacesServiceTestAccess() {}

    public static String normalizeBorough(String raw) {
        return OsmPlacesService.normalizeBorough(raw);
    }

    public static double haversine(double lat1, double lng1, double lat2, double lng2) {
        return OsmPlacesService.haversineMeters(lat1, lng1, lat2, lng2);
    }
}
