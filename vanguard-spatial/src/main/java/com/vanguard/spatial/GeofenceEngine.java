package com.vanguard.spatial;

import java.util.*;

/**
 * Evaluates track positions against all registered restricted zones.
 * Produces per-track, per-zone classifications each cycle.
 */
public class GeofenceEngine {

    private final List<RestrictedZone> zones = new ArrayList<>();

    public void addZone(RestrictedZone zone) {
        zones.add(zone);
    }

    public List<RestrictedZone> getZones() {
        return Collections.unmodifiableList(zones);
    }

    /**
     * Classify a single track position against all zones. Returns the
     * classification for each zone.
     */
    public Map<String, ZoneClassification> classify(double px, double py) {
        Map<String, ZoneClassification> result = new LinkedHashMap<>();
        for (RestrictedZone zone : zones) {
            result.put(zone.getZoneId(), zone.classify(px, py));
        }
        return result;
    }

    /**
     * Find the highest severity classification across all zones.
     */
    public ZoneClassification worstClassification(double px, double py) {
        ZoneClassification worst = ZoneClassification.CLEAR;
        for (RestrictedZone zone : zones) {
            ZoneClassification c = zone.classify(px, py);
            if (c.ordinal() > worst.ordinal()) {
                worst = c;
            }
        }
        return worst;
    }
}
