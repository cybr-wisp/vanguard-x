package com.vanguard.spatial;

import org.locationtech.jts.geom.*;

/**
 * A restricted zone defined by a polygon with two buffer distances:
 *   - advisoryBufferM: outer ring for early notification
 *   - warningBufferM:  inner ring for imminent breach
 *
 * The zone itself is the original polygon. Classification is:
 *   BREACH   if the point is inside the polygon
 *   WARNING  if the point is inside the warning buffer but outside the polygon
 *   ADVISORY if the point is inside the advisory buffer but outside the warning buffer
 *   CLEAR    if the point is outside everything
 */
public class RestrictedZone {

    private final String zoneId;
    private final Polygon polygon;
    private final Geometry warningBuffer;
    private final Geometry advisoryBuffer;

    public RestrictedZone(String zoneId, Polygon polygon,
                          double warningBufferM, double advisoryBufferM) {
        this.zoneId = zoneId;
        this.polygon = polygon;
        this.warningBuffer = polygon.buffer(warningBufferM);
        this.advisoryBuffer = polygon.buffer(advisoryBufferM);
    }

    /**
     * Classify a point relative to this zone.
     */
    public ZoneClassification classify(double px, double py) {
        GeometryFactory gf = polygon.getFactory();
        Point point = gf.createPoint(new Coordinate(px, py));

        if (polygon.covers(point)) return ZoneClassification.BREACH;
        if (warningBuffer.contains(point)) return ZoneClassification.WARNING;
        if (advisoryBuffer.contains(point)) return ZoneClassification.ADVISORY;
        return ZoneClassification.CLEAR;
    }

    /**
     * Compute the signed distance from a point to the zone boundary.
     * Negative = inside the polygon, positive = outside.
     */
    public double signedDistance(double px, double py) {
        GeometryFactory gf = polygon.getFactory();
        Point point = gf.createPoint(new Coordinate(px, py));
        double dist = polygon.distance(point);
        return polygon.contains(point) ? -dist : dist;
    }

    public String getZoneId() { return zoneId; }
    public Polygon getPolygon() { return polygon; }
    public Geometry getWarningBuffer() { return warningBuffer; }
    public Geometry getAdvisoryBuffer() { return advisoryBuffer; }

    /**
     * Create a rectangular restricted zone (convenience for testing).
     */
    public static RestrictedZone rectangle(String id,
                                            double minX, double minY,
                                            double maxX, double maxY,
                                            double warningBuffer, double advisoryBuffer) {
        GeometryFactory gf = new GeometryFactory();
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(minX, minY),
                new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY),
                new Coordinate(minX, maxY),
                new Coordinate(minX, minY)  // close the ring
        };
        Polygon poly = gf.createPolygon(coords);
        return new RestrictedZone(id, poly, warningBuffer, advisoryBuffer);
    }
}
