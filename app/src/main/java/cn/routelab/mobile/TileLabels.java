package cn.routelab.mobile;

import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

final class TileLabels {
    private static final String TILE_JSON = "https://tiles.openfreemap.org/planet";
    private static final long RETRY_MS = 30_000;
    private final View view;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<Tile, List<Label>> cache = new LruCache<>(48);
    private final LruCache<Tile, Long> failures = new LruCache<>(48);
    private final Runnable fetchNext = this::requestNext;
    private List<Tile> wanted = Collections.emptyList();
    private boolean busy;
    private boolean closed;
    private String template;
    private long metadataRetryAt;

    static final class Label {
        final String name;
        final Route.Point point;
        final int minZoom;
        final int priority;

        Label(String name, Route.Point point, int minZoom, int priority) {
            this.name = name;
            this.point = point;
            this.minZoom = minZoom;
            this.priority = priority;
        }
    }

    static final class Tile {
        final int z, x, y;

        Tile(int z, int x, int y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Tile)) return false;
            Tile tile = (Tile) other;
            return z == tile.z && x == tile.x && y == tile.y;
        }

        @Override public int hashCode() { return ((z * 31) + x) * 31 + y; }
    }

    TileLabels(View view) { this.view = view; }

    List<Label> visible(int zoom, double x, double y, double halfX, double halfY) {
        if (zoom < 6) {
            wanted = Collections.emptyList();
            return Collections.emptyList();
        }
        int level = Math.min(14, zoom - 2);
        int count = 1 << level;
        List<Tile> tiles = new ArrayList<>();
        int west = (int) Math.floor((x - halfX) * count);
        int east = (int) Math.floor((x + halfX) * count);
        int north = Math.max(0, (int) Math.floor((y - halfY) * count));
        int south = Math.min(count - 1, (int) Math.floor((y + halfY) * count));
        for (int row = north; row <= south; row++) {
            for (int col = west; col <= east && col - west < count; col++) {
                tiles.add(new Tile(level, Math.floorMod(col, count), row));
            }
        }
        tiles.sort(Comparator.comparingDouble(tile -> {
            double dx = tile.x + .5 - x * count;
            dx -= count * Math.rint(dx / count);
            return Math.hypot(dx, tile.y + .5 - y * count);
        }));
        if (!tiles.equals(wanted)) {
            wanted = tiles;
            view.removeCallbacks(fetchNext);
            view.postDelayed(fetchNext, 250);
        }

        List<Label> result = new ArrayList<>();
        for (Tile tile : tiles) {
            List<Label> entries = cache.get(tile);
            if (entries != null) result.addAll(entries);
        }
        result.sort(Comparator.comparingInt(label -> label.priority));
        return result;
    }

    private void requestNext() {
        if (closed || busy || view.getWindowVisibility() != View.VISIBLE) return;
        long now = SystemClock.elapsedRealtime();
        for (Tile tile : wanted) {
            Long failedAt = failures.get(tile);
            if (cache.get(tile) != null || (failedAt != null && now - failedAt < RETRY_MS)) continue;
            busy = true;
            worker.execute(() -> {
                List<Label> labels = download(tile);
                view.post(() -> {
                    if (closed) return;
                    busy = false;
                    if (labels == null) {
                        failures.put(tile, SystemClock.elapsedRealtime());
                        view.postDelayed(fetchNext, RETRY_MS);
                    } else {
                        failures.remove(tile);
                        cache.put(tile, labels);
                        view.invalidate();
                    }
                    requestNext();
                });
            });
            return;
        }
    }

    void retry() {
        failures.evictAll();
        view.removeCallbacks(fetchNext);
        view.post(fetchNext);
    }

    void close() {
        closed = true;
        view.removeCallbacks(fetchNext);
        worker.shutdownNow();
    }

    private List<Label> download(Tile tile) {
        try {
            if (template == null) {
                if (SystemClock.elapsedRealtime() < metadataRetryAt) return null;
                try {
                    JSONObject metadata = new JSONObject(new String(read(TILE_JSON), StandardCharsets.UTF_8));
                    String url = metadata.getJSONArray("tiles").getString(0);
                    if (!url.startsWith(TILE_JSON + "/") || !url.contains("/{z}/{x}/{y}.pbf")) {
                        throw new IOException("Unexpected tile URL");
                    }
                    template = url;
                } catch (IOException | JSONException exception) {
                    metadataRetryAt = SystemClock.elapsedRealtime() + RETRY_MS;
                    return null;
                }
            }
            String path = template.replace("{z}", Integer.toString(tile.z))
                    .replace("{x}", Integer.toString(tile.x)).replace("{y}", Integer.toString(tile.y));
            return decode(read(path), tile);
        } catch (IOException exception) {
            return null;
        }
    }

    private static byte[] read(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestProperty("User-Agent", "RouteLab-Android/1.5 (cn.routelab.mobile)");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(5000);
        try (InputStream response = connection.getInputStream();
             PushbackInputStream input = new PushbackInputStream(response, 2)) {
            int first = input.read();
            int second = input.read();
            if (second >= 0) input.unread(second);
            if (first >= 0) input.unread(first);
            try (InputStream data = first == 0x1f && second == 0x8b ? new GZIPInputStream(input) : input) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = data.read(buffer)) != -1) {
                    if (result.size() + n > 3 * 1024 * 1024) throw new IOException("Tile is too large");
                    result.write(buffer, 0, n);
                }
                return result.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    static List<Label> decode(byte[] data, Tile tile) throws IOException {
        List<Label> labels = new ArrayList<>();
        Pbf file = new Pbf(data, 0, data.length);
        while (file.hasNext()) {
            int tag = file.number();
            if (tag == 26) decodeLayer(file.section(), tile, labels);
            else file.skip(tag);
        }
        return labels;
    }

    private static void decodeLayer(Pbf layer, Tile tile, List<Label> labels) throws IOException {
        String layerName = "";
        List<Pbf> features = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        int extent = 4096;
        while (layer.hasNext()) {
            int tag = layer.number();
            switch (tag) {
                case 10: layerName = layer.string(); break;
                case 18: features.add(layer.section()); break;
                case 26: keys.add(layer.string()); break;
                case 34: values.add(valueString(layer.section())); break;
                case 40: extent = layer.number(); break;
                default: layer.skip(tag);
            }
        }
        if (extent <= 0 || (!"place".equals(layerName) && !"poi".equals(layerName))) return;
        boolean place = "place".equals(layerName);
        for (Pbf feature : features) {
            Label label = decodeFeature(feature, tile, extent, keys, values, place);
            if (label != null) labels.add(label);
        }
    }

    private static String valueString(Pbf value) throws IOException {
        while (value.hasNext()) {
            int tag = value.number();
            if (tag == 10) return value.string();
            value.skip(tag);
        }
        return "";
    }

    private static Label decodeFeature(Pbf feature, Tile tile, int extent, List<String> keys,
                                       List<String> values, boolean place) throws IOException {
        Pbf tags = null;
        Pbf geometry = null;
        int type = 0;
        while (feature.hasNext()) {
            int tag = feature.number();
            switch (tag) {
                case 18: tags = feature.section(); break;
                case 24: type = feature.number(); break;
                case 34: geometry = feature.section(); break;
                default: feature.skip(tag);
            }
        }
        if (type != 1 || tags == null || geometry == null || !geometry.hasNext()) return null;
        String name = "";
        String category = "";
        String subclass = "";
        int bestName = Integer.MAX_VALUE;
        while (tags.hasNext()) {
            int key = tags.number();
            int value = tags.number();
            if (key < 0 || key >= keys.size() || value < 0 || value >= values.size()) continue;
            String field = keys.get(key);
            String text = values.get(value);
            if (text.isEmpty()) continue;
            if ("class".equals(field)) category = text;
            if ("subclass".equals(field)) subclass = text;
            int priority = namePriority(field);
            if (priority < bestName) {
                bestName = priority;
                name = text.trim();
            }
        }
        if (name.isEmpty() || name.length() > 64 || (geometry.number() & 7) != 1) return null;
        double x = (tile.x + (double) signed(geometry.number()) / extent) / (1 << tile.z);
        double y = (tile.y + (double) signed(geometry.number()) / extent) / (1 << tile.z);
        if (y <= 0 || y >= 1) return null;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))));
        Route.Point point = new Route.Point(lat, Route.wrap(x * 360 - 180));
        if (place) {
            switch (category) {
                case "country": case "state": case "province": return new Label(name, point, 6, 0);
                case "city": case "town": return new Label(name, point, 7, 1);
                case "village": case "borough": case "suburb": return new Label(name, point, 12, 2);
                case "quarter": case "neighbourhood": return new Label(name, point, 15, 3);
                default: return null;
            }
        }
        boolean campus = education(category) || education(subclass);
        if (name.endsWith("楼") || name.endsWith("馆") || name.endsWith("大厦") || name.endsWith("中心")) {
            return new Label(name, point, 17, 5);
        }
        return new Label(name, point, campus ? 16 : 18, campus ? 4 : 6);
    }

    private static boolean education(String value) {
        return "university".equals(value) || "college".equals(value) || "school".equals(value);
    }

    private static int namePriority(String key) {
        switch (key) {
            case "name:zh-Hans": return 0;
            case "name:zh": case "name_zh": return 1;
            case "name": return 2;
            default: return Integer.MAX_VALUE;
        }
    }

    private static int signed(int n) { return (n >>> 1) ^ -(n & 1); }

    private static final class Pbf {
        final byte[] data;
        final int end;
        int at;

        Pbf(byte[] data, int at, int end) {
            this.data = data;
            this.at = at;
            this.end = end;
        }

        boolean hasNext() { return at < end; }

        int number() throws IOException {
            long n = 0;
            for (int shift = 0; shift < 64 && hasNext(); shift += 7) {
                int b = data[at++] & 255;
                n |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return (int) n;
            }
            throw new IOException("Invalid tile field");
        }

        Pbf section() throws IOException {
            int count = number();
            int start = at;
            advance(count);
            return new Pbf(data, start, at);
        }

        String string() throws IOException {
            Pbf bytes = section();
            return new String(data, bytes.at, bytes.end - bytes.at, StandardCharsets.UTF_8);
        }

        void skip(int tag) throws IOException {
            switch (tag & 7) {
                case 0: number(); return;
                case 1: advance(8); return;
                case 2: advance(number()); return;
                case 5: advance(4); return;
                default: throw new IOException("Unsupported tile field");
            }
        }

        private void advance(int count) throws IOException {
            if (count < 0 || count > end - at) throw new IOException("Truncated tile");
            at += count;
        }
    }
}
