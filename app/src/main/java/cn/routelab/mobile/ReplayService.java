package cn.routelab.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class ReplayService extends Service {
    static final String START = "cn.routelab.mobile.START";
    static final String STOP = "cn.routelab.mobile.STOP";
    static final String TOGGLE = "cn.routelab.mobile.TOGGLE";
    static final String SPEED = "cn.routelab.mobile.SPEED";
    static final String PREFERENCES = "cn.routelab.mobile.PREFERENCES";
    static final String DATA = "route";
    static final String KMH = "speed";
    private static final String CHANNEL = "route_replay";
    private static final int NOTICE = 17;
    private static final long INTERVAL_MS = 500;

    static final class Status {
        final boolean active;
        final boolean paused;
        final Route.Point point;
        final int messageId;
        final float bearing;
        final long lap;
        final double covered;
        final double length;

        Status(int messageId) {
            this(false, false, null, messageId, Float.NaN, 0, 0, 0);
        }

        Status(boolean active, boolean paused, Route.Point point, int messageId, float bearing,
               long lap, double covered, double length) {
            this.active = active;
            this.paused = paused;
            this.point = point;
            this.messageId = messageId;
            this.bearing = bearing;
            this.lap = lap;
            this.covered = covered;
            this.length = length;
        }

        String message(Context context) {
            if (!active) return context.getString(messageId);
            String action = context.getString(paused ? R.string.paused : R.string.replaying);
            if (length == 0) return context.getString(R.string.fixed_position) + " · " + action;
            if (lap > 0) action = context.getString(R.string.lap, lap) + " · " + action;
            return context.getString(R.string.progress, action, covered, length);
        }
    }

    // Service and Activity callbacks run on the same main thread.
    static Status status = new Status(R.string.waiting);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pulse = this::tick;
    private final List<String> providers = new ArrayList<>();
    private LocationManager locations;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private WindowManager windows;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView overlayTitle;
    private View overlayToggle;
    private View overlayStop;
    private Context strings;
    private AppSettings.Theme theme;
    private Typeface font;
    private Route route;
    private Route.Point current;
    private boolean paused;
    private boolean ending;
    private boolean overlayFailed;
    private float speed;
    private double metres;
    private long previous;

    @Override public void onCreate() {
        super.onCreate();
        locations = getSystemService(LocationManager.class);
        notifications = getSystemService(NotificationManager.class);
        windows = getSystemService(WindowManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":route");
        wakeLock.setReferenceCounted(false);
        updateAppearance();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (START.equals(action)) {
            if (route == null) {
                startForeground(NOTICE, notification(strings.getString(R.string.replay_starting)));
                begin(intent.getStringExtra(DATA));
            }
        } else if (route == null) {
            stopSelf(startId);
        } else if (STOP.equals(action)) {
            finish(R.string.replay_stopped);
        } else if (TOGGLE.equals(action)) {
            toggle();
        } else if (PREFERENCES.equals(action)) {
            updateAppearance();
            syncOverlay();
            updateNotice();
        } else if (SPEED.equals(action)) {
            float next = intent.getFloatExtra(KMH, speed);
            if (Float.isFinite(next) && next >= Route.MIN_SPEED && next <= Route.MAX_SPEED) {
                advance(SystemClock.elapsedRealtime());
                speed = next;
                updateNotice();
            }
        }
        return START_NOT_STICKY;
    }

    private void updateAppearance() {
        strings = AppSettings.localized(this);
        boolean wasRem = theme == AppSettings.Theme.REM;
        theme = AppSettings.theme(this);
        font = AppSettings.fontStyle(this).load(this);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, strings.getString(R.string.replay_channel), NotificationManager.IMPORTANCE_LOW));
        if (overlay != null) {
            if (wasRem != (theme == AppSettings.Theme.REM)) {
                int x = overlayParams.x;
                int y = overlayParams.y;
                removeOverlay();
                if (Settings.canDrawOverlays(this)) addOverlay(x, y);
            } else {
                styleOverlay((GradientDrawable) overlay.getBackground());
            }
        }
    }

    private void begin(String data) {
        try {
            if (data == null) throw new IllegalArgumentException("路线缺失");
            route = Route.decode(data);
            if (route.points.isEmpty()) throw new IllegalArgumentException("路线缺少标记点");
        } catch (JSONException | IllegalArgumentException exception) {
            finish(R.string.route_unreadable);
            return;
        }
        if (!locations.isLocationEnabled()) {
            finish(R.string.enable_location);
            return;
        }
        try {
            enableProvider(LocationManager.GPS_PROVIDER, true);
            if (locations.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                enableProvider(LocationManager.NETWORK_PROVIDER, false);
            }
        } catch (SecurityException exception) {
            finish(R.string.mock_app_required);
            return;
        } catch (IllegalArgumentException exception) {
            Log.w("RouteLab", "无法初始化模拟定位", exception);
            finish(R.string.mock_unavailable);
            return;
        }
        speed = route.speed;
        metres = 0;
        paused = false;
        ending = false;
        overlayFailed = false;
        previous = SystemClock.elapsedRealtime();
        wakeLock.acquire();
        tick();
        if (route != null) updateNotice();
    }

    @SuppressWarnings("deprecation")
    private void enableProvider(String name, boolean gps) {
        locations.addTestProvider(name, !gps, gps, !gps, false,
                true, true, true, gps ? Criteria.POWER_HIGH : Criteria.POWER_LOW,
                gps ? Criteria.ACCURACY_FINE : Criteria.ACCURACY_COARSE);
        providers.add(name);
        locations.setTestProviderEnabled(name, true);
    }

    private void advance(long now) {
        if (!paused && route.length > 0) metres += (now - previous) * speed / 3600.0;
        previous = now;
    }

    private void tick() {
        if (route == null) return;
        if (ending) {
            finish(R.string.route_complete);
            return;
        }
        advance(SystemClock.elapsedRealtime());
        ending = !route.loop && route.length > 0 && metres >= route.length;
        Route.Position position = route.positionAt(metres);
        current = position.point;
        try {
            long time = System.currentTimeMillis();
            long monotonic = SystemClock.elapsedRealtimeNanos();
            for (String provider : providers) {
                Location sample = new Location(provider);
                sample.setLatitude(current.lat);
                sample.setLongitude(current.lon);
                sample.setAccuracy(5f);
                sample.setBearing(position.bearing);
                sample.setSpeed(paused || ending || route.length == 0 ? 0 : speed / 3.6f);
                sample.setTime(time);
                sample.setElapsedRealtimeNanos(monotonic);
                locations.setTestProviderLocation(provider, sample);
            }
        } catch (SecurityException | IllegalArgumentException exception) {
            Log.w("RouteLab", "模拟定位更新失败", exception);
            finish(R.string.mock_interrupted);
            return;
        }
        boolean looping = route.loop && route.length > 0;
        long lap = looping ? 1 + (long) (metres / route.length) : 0;
        double covered = looping ? metres % route.length : Math.min(metres, route.length);
        status = new Status(true, paused, current, 0, route.length > 0 ? position.bearing : Float.NaN,
                lap, covered, route.length);
        syncOverlay();
        main.postDelayed(pulse, ending ? 1200 : INTERVAL_MS);
    }

    private void toggle() {
        if (route == null || ending) return;
        advance(SystemClock.elapsedRealtime());
        paused = !paused;
        if (paused && wakeLock.isHeld()) wakeLock.release();
        if (!paused && !wakeLock.isHeld()) wakeLock.acquire();
        main.removeCallbacks(pulse);
        tick();
        if (route != null) updateNotice();
    }

    private void updateNotice() {
        String detail = route.length == 0 ? strings.getString(R.string.fixed_position)
                : strings.getString(R.string.speed_value, speed);
        notifications.notify(NOTICE, notification(strings.getString(paused ? R.string.paused : R.string.replaying)
                + " · " + detail));
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_status)
                .setContentTitle(strings.getString(R.string.app_name))
                .setContentText(message)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, strings.getString(paused ? R.string.resume : R.string.pause),
                        serviceAction(TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(null, strings.getString(R.string.stop), serviceAction(STOP, 3)).build())
                .build();
    }

    private PendingIntent serviceAction(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, ReplayService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void syncOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            removeOverlay();
            overlayFailed = false;
            return;
        }
        if (overlay == null && !overlayFailed) {
            int top = theme == AppSettings.Theme.REM
                    ? (int) (getResources().getDisplayMetrics().heightPixels * .72f) : dp(100);
            addOverlay(dp(16), top);
        }
        if (overlay != null) {
            String detail = route.length == 0 ? strings.getString(R.string.fixed_position)
                    : strings.getString(R.string.speed_value, speed);
            overlayTitle.setText(detail);
            String action = strings.getString(paused ? R.string.resume : R.string.pause);
            if (overlayToggle instanceof ImageButton icon) {
                if (icon.isSelected() != paused) {
                    icon.setImageResource(paused ? R.drawable.ic_rem_play : R.drawable.ic_rem_pause);
                    icon.setSelected(paused);
                }
                icon.setContentDescription(action);
            } else {
                ((Button) overlayToggle).setText(action);
            }
        }
    }

    private void addOverlay(int left, int top) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        boolean rem = theme == AppSettings.Theme.REM;
        bar.setPadding(dp(rem ? 6 : 12), dp(2), dp(rem ? 4 : 6), dp(2));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(rem ? 24 : 18));
        bar.setBackground(background);
        overlayTitle = new TextView(this);
        overlayTitle.setTextSize(14);
        if (rem) {
            LinearLayout.LayoutParams avatar = new LinearLayout.LayoutParams(dp(26), dp(26));
            avatar.setMarginEnd(dp(6));
            bar.addView(RemArt.avatar(this), avatar);
            overlayTitle.setGravity(Gravity.CENTER_VERTICAL);
            overlayTitle.setSingleLine();
            overlayTitle.setEllipsize(TextUtils.TruncateAt.END);
            overlayTitle.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
            bar.addView(overlayTitle, new LinearLayout.LayoutParams(dp(67), dp(44)));
            overlayToggle = iconButton(R.drawable.ic_rem_pause, theme.accent, this::toggle);
            overlayStop = iconButton(R.drawable.ic_rem_stop, RemArt.PINK, () -> finish(R.string.replay_stopped));
            bar.addView(overlayToggle, new LinearLayout.LayoutParams(dp(40), dp(44)));
            bar.addView(overlayStop, new LinearLayout.LayoutParams(dp(40), dp(44)));
        } else {
            overlayTitle.setMinWidth(dp(74));
            bar.addView(overlayTitle);
            overlayToggle = smallButton(strings.getString(R.string.pause), this::toggle);
            overlayStop = smallButton(strings.getString(R.string.stop), () -> finish(R.string.replay_stopped));
            bar.addView(overlayToggle);
            bar.addView(overlayStop);
        }
        styleOverlay(background);

        overlayParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        overlayParams.x = left;
        overlayParams.y = top;
        overlayTitle.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float fingerX, fingerY;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = overlayParams.x;
                    startY = overlayParams.y;
                    fingerX = event.getRawX();
                    fingerY = event.getRawY();
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && overlay != null) {
                    overlayParams.x = Math.max(0, startX + (int) (event.getRawX() - fingerX));
                    overlayParams.y = Math.max(0, startY + (int) (event.getRawY() - fingerY));
                    try {
                        windows.updateViewLayout(overlay, overlayParams);
                    } catch (IllegalArgumentException | SecurityException exception) {
                        removeOverlay();
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;
            }
        });
        try {
            windows.addView(bar, overlayParams);
            overlay = bar;
        } catch (WindowManager.BadTokenException | SecurityException exception) {
            overlayFailed = true;
            Log.w("RouteLab", "悬浮窗无法打开", exception);
        }
    }

    private void styleOverlay(GradientDrawable background) {
        boolean rem = theme == AppSettings.Theme.REM;
        background.setColor(rem ? theme.paper : theme.soft);
        background.setStroke(dp(1), rem ? RemArt.BLUE_EDGE : theme.muted);
        overlayTitle.setTextColor(theme.ink);
        overlayTitle.setTypeface(font);
        overlayTitle.setContentDescription(strings.getString(R.string.overlay_drag));
        if (rem) {
            overlayStop.setContentDescription(strings.getString(R.string.stop));
        } else {
            ((Button) overlayToggle).setTextColor(theme.ink);
            ((Button) overlayToggle).setTypeface(font);
            Button stop = (Button) overlayStop;
            stop.setTextColor(theme.ink);
            stop.setTypeface(font);
            stop.setText(strings.getString(R.string.stop));
        }
    }

    private ImageButton iconButton(int icon, int tint, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(tint);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xffffffff);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf((tint & 0x00ffffff) | 0x22000000),
                null, mask));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private Button smallButton(String label, Runnable action) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setTextSize(13);
        button.setMinimumWidth(dp(52));
        button.setMinWidth(dp(52));
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void removeOverlay() {
        if (overlay != null) {
            try {
                windows.removeViewImmediate(overlay);
            } catch (IllegalArgumentException exception) {
                Log.w("RouteLab", "悬浮窗已移除", exception);
            }
            overlay = null;
        }
    }

    private void finish(int messageId) {
        cleanUp();
        status = new Status(messageId);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanUp() {
        main.removeCallbacks(pulse);
        removeOverlay();
        for (String provider : providers) {
            try {
                locations.removeTestProvider(provider);
            } catch (SecurityException | IllegalArgumentException exception) {
                Log.w("RouteLab", "无法清理模拟位置提供者 " + provider, exception);
            }
        }
        providers.clear();
        if (wakeLock.isHeld()) wakeLock.release();
        route = null;
        current = null;
    }

    @Override public void onDestroy() {
        boolean wasRunning = route != null;
        cleanUp();
        if (wasRunning) status = new Status(R.string.replay_ended);
        super.onDestroy();
    }
}
