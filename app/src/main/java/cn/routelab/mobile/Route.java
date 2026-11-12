package cn.routelab.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Route {
    static final int MAX_POINTS = 300;
    static final float MIN_SPEED = 1f;
    static final float MAX_SPEED = 30f;
    static final float DEFAULT_SPEED = 8f;
    private static final double EARTH_METRES = 6371008.8;

    static final class Point {
        final double lat;
        final double lon;

        Point(double lat, double lon) {
            if (!Double.isFinite(lat) || Math.abs(lat) > 85.05112878
                    || !Double.isFinite(lon) || Math.abs(lon) > 180) {
                throw new IllegalArgumentException("坐标超出地图范围");
            }
            this.lat = lat;
            this.lon = lon;
        }
    }

    static final class Position {
        final Point point;
        final float bearing;

        Position(Point point, float bearing) {
            this.point = point;
            this.bearing = bearing;
        }
    }

    final String name;
    final List<Point> points;
    final boolean loop;
    final float speed;
    final double length;
    private final double[] stops;
    private final int legs;

    Route(String name, List<Point> points, boolean loop, float speed) {
        if (points.size() > MAX_POINTS || !Float.isFinite(speed)
                || speed < MIN_SPEED || speed > MAX_SPEED) {
            throw new IllegalArgumentException("路线参数超出范围");
        }
        this.name = name;
        this.points = new ArrayList<>(points);
        this.loop = loop;
        this.speed = speed;
        legs = points.size() < 2 ? 0 : points.size() - (loop ? 0 : 1);
        stops = new double[legs + 1];
        for (int i = 0; i < legs; i++) {
            stops[i + 1] = stops[i] + distance(points.get(i), points.get((i + 1) % points.size()));
        }
        length = stops[legs];
    }

    Position positionAt(double metres) {
        if (length == 0) return new Position(points.get(0), 0);
        double along = loop ? metres % length : Math.min(metres, length);
        int leg = 0;
        while (leg < legs - 1 && stops[leg + 1] <= along) leg++;
        Point a = points.get(leg);
        Point b = points.get((leg + 1) % points.size());
        double segmentLength = stops[leg + 1] - stops[leg];
        double fraction = segmentLength == 0 ? 1 : (along - stops[leg]) / segmentLength;
        double lonDelta = wrap(b.lon - a.lon);
        Point point = new Point(a.lat + fraction * (b.lat - a.lat), wrap(a.lon + fraction * lonDelta));
        double y = Math.sin(Math.toRadians(lonDelta)) * Math.cos(Math.toRadians(b.lat));
        double x = Math.cos(Math.toRadians(a.lat)) * Math.sin(Math.toRadians(b.lat))
                - Math.sin(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat))
                * Math.cos(Math.toRadians(lonDelta));
        return new Position(point, (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360));
    }

    static double distance(Point a, Point b) {
        double dLat = Math.toRadians(b.lat - a.lat);
        double dLon = Math.toRadians(wrap(b.lon - a.lon));
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat + Math.cos(Math.toRadians(a.lat))
                * Math.cos(Math.toRadians(b.lat)) * sinLon * sinLon;
        return 2 * EARTH_METRES * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    static double wrap(double lon) {
        return ((lon + 180) % 360 + 360) % 360 - 180;
    }

    String encode() {
        try {
            JSONArray coordinates = new JSONArray();
            for (Point point : points) {
                coordinates.put(new JSONArray().put(point.lat).put(point.lon));
            }
            return new JSONObject().put("name", name).put("loop", loop)
                    .put("speed", (double) speed).put("points", coordinates).toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Route decode(String data) throws JSONException {
        JSONObject json = new JSONObject(data);
        JSONArray coordinates = json.getJSONArray("points");
        if (coordinates.length() > MAX_POINTS) throw new JSONException("路线点数过多");
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray pair = coordinates.getJSONArray(i);
            points.add(new Point(pair.getDouble(0), pair.getDouble(1)));
        }
        return new Route(json.getString("name"), points, json.getBoolean("loop"),
                (float) json.getDouble("speed"));
    }
}
