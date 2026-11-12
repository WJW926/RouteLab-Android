package cn.routelab.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.http.HttpResponseCache;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class RouteMap extends View {
    interface TapListener {
        void onTap(Route.Point point);
    }

    private static final int TILE = 256;
    private static final int MIN_ZOOM = 2;
    private static final int MAX_ZOOM = 19;
    private static final double LAT_LIMIT = 85.05112878;
    private static final double LOG_TWO = Math.log(2);
    private static final String STANDARD = "https://tile.openstreetmap.org/";
    private static final String FRANCE = "https://a.tile.openstreetmap.fr/osmfr/";
    private static final long RETRY_DELAY_MS = 30_000;

    private static final class TileRequests {
        final boolean franceFirst;
        final AtomicLong standardUnavailable = new AtomicLong();
        final AtomicLong franceUnavailable = new AtomicLong();

        TileRequests(boolean franceFirst) {
            this.franceFirst = franceFirst;
        }
    }

    private TileRequests requests;
    private final LruCache<String, Bitmap> tiles = new LruCache<>(96);
    private final LruCache<String, Long> failed = new LruCache<>(64);
    private final Set<String> pending = new HashSet<>();
    private final ThreadPoolExecutor downloads = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.SECONDS, new PriorityBlockingQueue<>(48));
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface mapFont;
    private final Typeface boldMapFont;
    private final Rect tileCrop = new Rect();
    private final RectF tileRect = new RectF();
    private final RectF childRect = new RectF();
    private final Path arrow = new Path();
    private final Path avatarClip = new Path();
    private final RectF avatarBounds = new RectF();
    private final Bitmap positionAvatar;
    private final ScaleGestureDetector scale;
    private final TileLabels labels;
    private final AppSettings.Theme theme;
    private final int touchSlop;
    private double centreX = x(116.3913);
    private double centreY = y(39.9075);
    private double zoom = 15;
    private double world = (double) TILE * (1 << 15);
    private ValueAnimator zoomAnimator;
    private double buttonZoomTarget;
    private List<TileLabels.Label> cachedLabels = Collections.emptyList();
    private float downX, downY, lastX, lastY;
    private boolean dragged, multiTouch;
    private List<Route.Point> points = Collections.emptyList();
    private boolean loop;
    private Route.Point marker;
    private float accuracy;
    private boolean simulated;
    private float heading = Float.NaN;
    private float headingFrom;
    private long headingStart;
    TapListener tapListener;
    Runnable onManualMove;

    RouteMap(Context context, boolean franceFirst, Typeface font) {
        super(context);
        requests = new TileRequests(franceFirst);
        theme = AppSettings.theme(context);
        mapFont = font;
        boldMapFont = Typeface.create(font, Typeface.BOLD);
        positionAvatar = theme == AppSettings.Theme.REM
                ? BitmapFactory.decodeResource(getResources(), R.drawable.rem_avatar) : null;
        labels = new TileLabels(this);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setStrokeJoin(Paint.Join.ROUND);
        int arrowBase = positionAvatar == null ? 8 : 5;
        int arrowBottom = positionAvatar == null ? 5 : 8;
        int arrowTop = positionAvatar == null ? 21 : 16;
        arrow.moveTo(-dp(arrowBase), -dp(arrowBottom));
        arrow.lineTo(-dp(1), -dp(arrowTop));
        arrow.quadTo(0, -dp(arrowTop + 2), dp(1), -dp(arrowTop));
        arrow.lineTo(dp(arrowBase), -dp(arrowBottom));
        arrow.close();
        setContentDescription(getContext().getString(R.string.map_accessibility));
        setClickable(true);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        try {
            if (HttpResponseCache.getInstalled() == null) {
                HttpResponseCache.install(new File(context.getCacheDir(), "map-tiles"), 96L * 1024 * 1024);
            }
        } catch (IOException exception) {
            android.util.Log.w("RouteLab", "地图磁盘缓存初始化失败", exception);
        }
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float lastFocusX, lastFocusY;

            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                if (onManualMove != null) onManualMove.run();
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                float factor = detector.getScaleFactor();
                if (Float.isFinite(factor) && factor > 0) {
                    double oldWorld = world;
                    setZoom(zoom + Math.log(factor) / LOG_TWO);
                    double midX = getWidth() / 2.0;
                    double midY = getHeight() / 2.0;
                    centreX = wrapX(centreX + (lastFocusX - midX) / oldWorld - (focusX - midX) / world);
                    centreY = clampY(centreY + (lastFocusY - midY) / oldWorld - (focusY - midY) / world);
                    postInvalidateOnAnimation();
                }
                lastFocusX = focusX;
                lastFocusY = focusY;
                return true;
            }

            @Override public void onScaleEnd(ScaleGestureDetector detector) { postInvalidateOnAnimation(); }
        });
        scale.setQuickScaleEnabled(false);
    }

    private static double x(double lon) { return (lon + 180) / 360; }

    private static double y(double lat) {
        double sin = Math.sin(Math.toRadians(lat));
        return .5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
    }

    private static Route.Point point(double x, double y) {
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))));
        return new Route.Point(Math.max(-LAT_LIMIT, Math.min(LAT_LIMIT, lat)), Route.wrap(x * 360 - 180));
    }

    private static double wrapX(double value) { return value - Math.floor(value); }
    private static double deltaX(double value) { return value - Math.floor(value + .5); }
    private static double clampY(double value) { return Math.max(0, Math.min(1, value)); }

    double zoomLevel() { return zoom; }
    Route.Point centre() { return point(centreX, centreY); }

    private void setZoom(double level) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, level));
        world = TILE * Math.pow(2, zoom);
    }

    void centreOn(Route.Point location, double level) {
        centreX = wrapX(x(location.lon));
        centreY = clampY(y(location.lat));
        if (zoom != level) {
            if (zoomAnimator != null) zoomAnimator.cancel();
            setZoom(level);
        }
        postInvalidateOnAnimation();
    }

    void fit(List<Route.Point> route) {
        if (route.isEmpty()) return;
        double start = x(route.get(0).lon);
        double minX = start, maxX = start;
        double minY = 1, maxY = 0;
        for (Route.Point location : route) {
            double px = start + deltaX(x(location.lon) - start);
            double py = y(location.lat);
            minX = Math.min(minX, px);
            maxX = Math.max(maxX, px);
            minY = Math.min(minY, py);
            maxY = Math.max(maxY, py);
        }
        int level = 18;
        while (level > MIN_ZOOM && ((maxX - minX) * TILE * (1 << level) > getWidth() * .76
                || (maxY - minY) * TILE * (1 << level) > getHeight() * .67)) {
            level--;
        }
        centreOn(point((minX + maxX) / 2, (minY + maxY) / 2), level);
    }

    void setRoute(List<Route.Point> points, boolean loop) {
        this.points = points;
        this.loop = loop;
        invalidate();
    }

    void setMarker(Route.Point point, float accuracy, boolean simulated, float bearing) {
        boolean changed = Float.compare(heading, bearing) != 0;
        if (changed || this.simulated != simulated) {
            long now = SystemClock.uptimeMillis();
            headingFrom = Float.isFinite(heading) && this.simulated == simulated ? shownHeading(now) : bearing;
            heading = bearing;
            headingStart = now;
        }
        if (changed || marker != point || this.accuracy != accuracy || this.simulated != simulated) {
            marker = point;
            this.accuracy = accuracy;
            this.simulated = simulated;
            postInvalidateOnAnimation();
        }
    }

    void zoomBy(int step) {
        double base = zoomAnimator != null && zoomAnimator.isRunning() ? buttonZoomTarget : zoom;
        double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, base + step));
        if (next == base) return;
        if (zoomAnimator != null) zoomAnimator.cancel();
        buttonZoomTarget = next;
        zoomAnimator = ValueAnimator.ofFloat((float) zoom, (float) next);
        zoomAnimator.setDuration(240);
        zoomAnimator.setInterpolator(new DecelerateInterpolator());
        zoomAnimator.addUpdateListener(animation -> {
            setZoom(((Float) animation.getAnimatedValue()).doubleValue());
            postInvalidateOnAnimation();
        });
        zoomAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { postInvalidateOnAnimation(); }
        });
        zoomAnimator.start();
    }

    void reload(boolean franceFirst) {
        if (requests.franceFirst != franceFirst) tiles.evictAll();
        requests = new TileRequests(franceFirst);
        downloads.getQueue().clear();
        pending.clear();
        failed.evictAll();
        labels.retry();
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scale.onTouchEvent(event);
        float px = event.getX();
        float py = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (zoomAnimator != null) zoomAnimator.cancel();
                downX = lastX = px;
                downY = lastY = py;
                dragged = multiTouch = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multiTouch) return true;
                if (!dragged && Math.hypot(px - downX, py - downY) > touchSlop) {
                    dragged = true;
                    if (onManualMove != null) onManualMove.run();
                }
                if (dragged) {
                    centreX = wrapX(centreX - (px - lastX) / world);
                    centreY = clampY(centreY - (py - lastY) / world);
                    lastX = px;
                    lastY = py;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragged && !multiTouch && tapListener != null) {
                    performClick();
                    tapListener.onTap(point(
                            centreX + (px - getWidth() / 2.0) / world,
                            centreY + (py - getHeight() / 2.0) / world));
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        double left = centreX * world - getWidth() / 2.0;
        double top = centreY * world - getHeight() / 2.0;
        canvas.drawColor(theme.paper);
        int level = (int) zoom;
        int count = 1 << level;
        double size = world / count;
        int minX = (int) Math.floor(left / size);
        int maxX = (int) Math.floor((left + getWidth() - 1) / size);
        int minY = Math.max(0, (int) Math.floor(top / size));
        int maxY = Math.min(count - 1, (int) Math.floor((top + getHeight() - 1) / size));
        boolean hasTiles = false;
        boolean hasFailure = false;
        boolean zooming = scale.isInProgress() || (zoomAnimator != null && zoomAnimator.isRunning());
        Map<String, Float> visibleMissing = zooming ? null : new HashMap<>();
        Map<String, Integer> previewCounts = new HashMap<>();
        Map<String, Float> previewDistances = new HashMap<>();
        for (int ty = minY; ty <= maxY; ty++) {
            for (int tx = minX; tx <= maxX; tx++) {
                int wrap = Math.floorMod(tx, count);
                String key = tileKey(level, wrap, ty);
                float px = (float) (tx * size - left);
                float py = (float) (ty * size - top);
                tileRect.set(px, py, (float) ((tx + 1) * size - left), (float) ((ty + 1) * size - top));
                Bitmap bitmap = tiles.get(key);
                if (bitmap != null) {
                    paint.setColor(Color.WHITE);
                    canvas.drawBitmap(bitmap, null, tileRect, paint);
                    hasTiles = true;
                    continue;
                }
                boolean previewDrawn = drawCachedParent(canvas, level, wrap, ty, tileRect);
                if (!previewDrawn) {
                    paint.setColor(((tx + ty) & 1) == 0 ? theme.paper : theme.soft);
                    canvas.drawRect(tileRect, paint);
                }
                if (level < MAX_ZOOM && drawCachedChildren(canvas, level, wrap, ty, tileRect)) previewDrawn = true;
                hasTiles |= previewDrawn;
                hasFailure |= failed.get(key) != null;
                if (visibleMissing != null) {
                    float dx = tileRect.centerX() - getWidth() / 2f;
                    float dy = tileRect.centerY() - getHeight() / 2f;
                    float distance = dx * dx + dy * dy;
                    visibleMissing.merge(key, distance, (first, second) -> Math.min(first, second));
                    if (!previewDrawn && level > MIN_ZOOM) {
                        String parent = tileKey(level - 1, wrap >> 1, ty >> 1);
                        previewCounts.merge(parent, 1, (first, second) -> first + second);
                        previewDistances.merge(parent, distance, (first, second) -> Math.min(first, second));
                    }
                }
            }
        }
        if (visibleMissing != null) {
            addPreviewRequests(visibleMissing, previewCounts, previewDistances);
            requestVisible(visibleMissing);
            cachedLabels = labels.visible(level, centreX, centreY,
                    getWidth() / (2 * world), getHeight() / (2 * world));
        }
        if (hasTiles) drawLabels(canvas, cachedLabels);
        if (!hasTiles) {
            paint.setColor(theme.muted);
            paint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
            paint.setTextAlign(Paint.Align.CENTER);
            float middle = getHeight() * .45f;
            canvas.drawText(hasFailure ? getContext().getString(R.string.map_unreachable) : getContext().getString(R.string.map_loading),
                    getWidth() / 2f, middle, paint);
            if (hasFailure) {
                canvas.drawText(getContext().getString(R.string.map_try_again),
                        getWidth() / 2f, middle + dp(23), paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
        }
        drawRoute(canvas);
    }

    private static String tileKey(int level, int x, int y) {
        return level + "/" + x + "/" + y;
    }

    private boolean drawCachedParent(Canvas canvas, int level, int x, int y, RectF rect) {
        for (int depth = 1; depth <= 4 && level - depth >= MIN_ZOOM; depth++) {
            Bitmap bitmap = tiles.get(tileKey(level - depth, x >> depth, y >> depth));
            if (bitmap == null) continue;
            int count = 1 << depth;
            int column = x & (count - 1);
            int row = y & (count - 1);
            tileCrop.set(column * bitmap.getWidth() / count, row * bitmap.getHeight() / count,
                    (column + 1) * bitmap.getWidth() / count, (row + 1) * bitmap.getHeight() / count);
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(bitmap, tileCrop, rect, paint);
            return true;
        }
        return false;
    }

    private boolean drawCachedChildren(Canvas canvas, int level, int x, int y, RectF rect) {
        boolean drawn = false;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                Bitmap child = tiles.get(tileKey(level + 1, 2 * x + col, 2 * y + row));
                if (child == null) continue;
                childRect.set(col == 0 ? rect.left : rect.centerX(), row == 0 ? rect.top : rect.centerY(),
                        col == 0 ? rect.centerX() : rect.right, row == 0 ? rect.centerY() : rect.bottom);
                paint.setColor(Color.WHITE);
                canvas.drawBitmap(child, null, childRect, paint);
                drawn = true;
            }
        }
        return drawn;
    }

    private void drawLabels(Canvas canvas, List<TileLabels.Label> visibleLabels) {
        Set<String> names = new HashSet<>();
        List<RectF> occupied = new ArrayList<>();
        for (TileLabels.Label label : visibleLabels) {
            if (label.minZoom > zoom || names.contains(label.name)) continue;
            float x = screenX(label.point);
            float y = screenY(label.point) - dp(3);
            if (x < 0 || x > getWidth() || y < dp(70) || y > getHeight() - dp(42)) continue;
            boolean major = label.priority <= 1 || label.priority == 4;
            float textSize = dp(major ? 14 : 11);
            labelPaint.setTextSize(textSize);
            labelPaint.setTypeface(major ? boldMapFont : mapFont);
            float width = labelPaint.measureText(label.name) / 2 + dp(4);
            RectF bounds = new RectF(x - width, y - textSize, x + width, y + dp(5));
            if (bounds.left < 0 || bounds.right > getWidth()) continue;
            boolean overlap = false;
            for (RectF area : occupied) {
                if (RectF.intersects(bounds, area)) {
                    overlap = true;
                    break;
                }
            }
            if (overlap) continue;
            labelPaint.setStyle(Paint.Style.STROKE);
            labelPaint.setStrokeWidth(dp(3));
            labelPaint.setColor(0xfaffffff);
            canvas.drawText(label.name, x, y, labelPaint);
            labelPaint.setStyle(Paint.Style.FILL);
            labelPaint.setColor(label.priority >= 4 ? theme.ink : 0xff35415a);
            canvas.drawText(label.name, x, y, labelPaint);
            names.add(label.name);
            occupied.add(bounds);
            if (occupied.size() >= 36) break;
        }
    }

    private void drawRoute(Canvas canvas) {
        if (!points.isEmpty()) {
            Path line = new Path();
            for (int i = 0; i < points.size(); i++) {
                Route.Point point = points.get(i);
                if (i == 0) line.moveTo(screenX(point), screenY(point));
                else line.lineTo(screenX(point), screenY(point));
            }
            if (loop && points.size() > 1) line.close();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(dp(7));
            canvas.drawPath(line, paint);
            paint.setColor(theme.accent);
            paint.setStrokeWidth(dp(4));
            canvas.drawPath(line, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < points.size(); i++) {
                dot(canvas, points.get(i), i == 0 ? 7 : 4, i == 0 ? theme.accent : theme.muted);
            }
        }
        if (marker != null) {
            if (!simulated && accuracy > 0) {
                double metresPerPixel = 40_075_017 * Math.cos(Math.toRadians(marker.lat)) / world;
                float radius = (float) Math.max(dp(14), accuracy / metresPerPixel);
                paint.setColor(0x262b62e7);
                canvas.drawCircle(screenX(marker), screenY(marker), radius, paint);
            }
            drawPosition(canvas);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float screenX(Route.Point point) {
        return (float) (getWidth() / 2.0 + deltaX(x(point.lon) - centreX) * world);
    }

    private float screenY(Route.Point point) {
        return (float) (getHeight() / 2.0 + (y(point.lat) - centreY) * world);
    }

    private float shownHeading(long now) {
        float t = Math.min(1f, (now - headingStart) / 170f);
        return headingFrom + (float) Route.wrap(heading - headingFrom) * t * (2 - t);
    }

    private void drawPosition(Canvas canvas) {
        float x = screenX(marker);
        float y = screenY(marker);
        int colour = simulated ? 0xff8550bd : 0xff2b62e7;
        paint.setColor(0x30324d80);
        canvas.drawCircle(x, y + dp(1), dp(13), paint);
        if (Float.isFinite(heading)) {
            long now = SystemClock.uptimeMillis();
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(shownHeading(now));
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(dp(positionAvatar == null ? 3 : 2));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(arrow, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colour);
            canvas.drawPath(arrow, paint);
            canvas.restore();
            if (now - headingStart < 170 && Math.abs(Route.wrap(heading - headingFrom)) > .1) {
                postInvalidateOnAnimation();
            }
        }
        if (positionAvatar == null) {
            dot(canvas, marker, 9, colour);
            paint.setColor(0xfff7fbff);
            canvas.drawCircle(x, y, dp(3), paint);
        } else {
            drawAvatar(canvas, x, y, colour);
        }
    }

    private void drawAvatar(Canvas canvas, float x, float y, int colour) {
        int radius = dp(9);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, dp(11), paint);
        paint.setColor(colour);
        canvas.drawCircle(x, y, dp(10), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, radius + 1f, paint);
        avatarClip.rewind();
        avatarClip.addCircle(x, y, radius, Path.Direction.CW);
        avatarBounds.set(x - radius, y - radius, x + radius, y + radius);
        canvas.save();
        canvas.clipPath(avatarClip);
        canvas.drawBitmap(positionAvatar, null, avatarBounds, paint);
        canvas.restore();
    }
    private void dot(Canvas canvas, Route.Point point, int radius, int colour) {
        paint.setColor(Color.WHITE);
        canvas.drawCircle(screenX(point), screenY(point), dp(radius + 2), paint);
        paint.setColor(colour);
        canvas.drawCircle(screenX(point), screenY(point), dp(radius), paint);
    }

    private void addPreviewRequests(Map<String, Float> visible, Map<String, Integer> counts,
                                    Map<String, Float> distances) {
        List<String> nearest = new ArrayList<>(counts.keySet());
        nearest.sort((first, second) -> Float.compare(distances.get(first), distances.get(second)));
        int added = 0;
        for (String key : nearest) {
            if (counts.get(key) < 2 || isCoolingDown(key)) continue;
            visible.put(key, -2f + added);
            if (++added == 2) break;
        }
    }

    private void requestVisible(Map<String, Float> visible) {
        for (Runnable queued : downloads.getQueue()) {
            TileLoad load = (TileLoad) queued;
            if (!visible.containsKey(load.key) && downloads.getQueue().remove(load)) {
                pending.remove(load.key);
            }
        }
        List<String> nearest = new ArrayList<>(visible.keySet());
        nearest.sort((first, second) -> Float.compare(visible.get(first), visible.get(second)));
        for (String key : nearest) request(key, visible.get(key));
    }

    private boolean isCoolingDown(String key) {
        Long lastFailure = failed.get(key);
        return lastFailure != null && SystemClock.elapsedRealtime() - lastFailure < RETRY_DELAY_MS;
    }

    private void request(String key, float priority) {
        if (isCoolingDown(key)) return;
        if (downloads.isShutdown() || !pending.add(key)) return;
        try {
            downloads.execute(new TileLoad(key, requests, priority));
        } catch (RejectedExecutionException exception) {
            pending.remove(key);
        }
    }

    private final class TileLoad implements Runnable, Comparable<TileLoad> {
        final String key;
        final TileRequests batch;
        final float priority;

        TileLoad(String key, TileRequests batch, float priority) {
            this.key = key;
            this.batch = batch;
            this.priority = priority;
        }

        @Override public int compareTo(TileLoad other) { return Float.compare(priority, other.priority); }

        @Override public void run() {
            Bitmap tile = fetch(key, batch);
            post(() -> {
                if (requests != batch) return;
                pending.remove(key);
                if (tile != null) {
                    failed.remove(key);
                    tiles.put(key, tile);
                } else {
                    failed.put(key, SystemClock.elapsedRealtime());
                    postInvalidateDelayed(RETRY_DELAY_MS);
                }
                invalidate();
            });
        }
    }

    private Bitmap fetch(String key, TileRequests batch) {
        if (batch.franceFirst) {
            Bitmap tile = fetch(FRANCE, key, batch.franceUnavailable);
            return tile != null ? tile : fetch(STANDARD, key, batch.standardUnavailable);
        }
        Bitmap tile = fetch(STANDARD, key, batch.standardUnavailable);
        return tile != null ? tile : fetch(FRANCE, key, batch.franceUnavailable);
    }

    private Bitmap fetch(String base, String key, AtomicLong unavailable) {
        if (SystemClock.elapsedRealtime() < unavailable.get()) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + key + ".png").openConnection();
            connection.setRequestProperty("User-Agent", "RouteLab-Android/1.5 (cn.routelab.mobile)");
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(5000);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                if (status == 403 || status == 429 || status >= 500) {
                    unavailable.set(SystemClock.elapsedRealtime() + RETRY_DELAY_MS);
                }
                return null;
            }
            try (InputStream stream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } catch (IOException exception) {
            unavailable.set(SystemClock.elapsedRealtime() + 15_000);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    void close() {
        if (zoomAnimator != null) zoomAnimator.cancel();
        downloads.shutdownNow();
        labels.close();
    }
}
