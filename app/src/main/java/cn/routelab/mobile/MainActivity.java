package cn.routelab.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int MAX_SPEED_PROGRESS = (int) ((Route.MAX_SPEED - Route.MIN_SPEED) * 10);
    private static final int LOCATION_REQUEST = 11;

    private final List<Route.Point> points = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            showStatus();
            handler.postDelayed(this, 350);
        }
    };
    private SharedPreferences draftStore;
    private SharedPreferences library;
    private AppSettings.Theme theme;
    private AppSettings.FontStyle fontStyle;
    private Typeface font;
    private FrameLayout drawer;
    private View drawerShade;
    private LinearLayout drawerPanel;
    private LinearLayout mainContent;
    private ImageButton menuButton;
    private int drawerWidth;
    private RouteMap map;
    private DevicePosition devicePosition;
    private DevicePosition.Fix lastCentred;
    private boolean visible;
    private boolean following = true;
    private boolean zoomOnNextFix = true;
    private View locate;
    private TextView locationInfo;
    private TextView routeInfo;
    private TextView status;
    private TextView speedLabel;
    private CheckBox loop;
    private SeekBar speedSlider;
    private Button save;
    private Button load;
    private Button undo;
    private Button clear;
    private Button startPause;
    private Button stop;
    private String name = "";
    private float speed = Route.DEFAULT_SPEED;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppSettings.localized(base));
    }

    @Override public void onCreate(Bundle state) {
        theme = AppSettings.theme(this);
        setTheme(theme.style);
        super.onCreate(state);
        fontStyle = AppSettings.fontStyle(this);
        font = fontStyle.load(this);
        draftStore = getSharedPreferences("draft", MODE_PRIVATE);
        library = getSharedPreferences("routes", MODE_PRIVATE);
        if (state != null) {
            following = state.getBoolean("following", true);
            zoomOnNextFix = state.getBoolean("zoomOnNextFix", true);
        }
        devicePosition = new DevicePosition(this,
                () -> map.getDisplay() == null ? 0 : map.getDisplay().getRotation(), this::renderPosition);
        setupView();
        restoreDraft();
        updateRoute();
        showStatus();
    }

    private void setupView() {
        boolean rem = theme == AppSettings.Theme.REM;
        FrameLayout screen = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        mainContent = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.paper);
        getWindow().setStatusBarColor(theme.paper);
        getWindow().setNavigationBarColor(theme.paper);
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(rem ? 12 : 8), dp(rem ? 5 : 7), dp(rem ? 10 : 8), dp(rem ? 5 : 7));
        menuButton = new ImageButton(this);
        menuButton.setImageResource(R.drawable.ic_menu);
        menuButton.setColorFilter(theme.ink);
        menuButton.setContentDescription(getString(R.string.open_menu));
        RippleDrawable menuBackground = new RippleDrawable(ColorStateList.valueOf(0x44326dcc),
                shape(rem ? 0xffd5e2fc : Color.TRANSPARENT, 12), shape(Color.WHITE, 12));
        menuButton.setBackground(rem ? new InsetDrawable(menuBackground, dp(4), dp(5), dp(4), dp(5))
                : menuBackground);
        menuButton.setOnClickListener(view -> openDrawer());
        LinearLayout.LayoutParams menuSpace = new LinearLayout.LayoutParams(dp(44), dp(44));
        menuSpace.setMarginEnd(dp(rem ? 5 : 0));
        header.addView(menuButton, menuSpace);
        TextView title;
        if (rem) {
            title = RemArt.wordmark(this, getString(R.string.app_name), theme.ink, 23);
        } else {
            title = text(getString(R.string.app_name), 22, theme.ink);
            title.setTypeface(font, Typeface.BOLD);
            title.setMaxLines(1);
            title.setAutoSizeTextTypeUniformWithConfiguration(15, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        header.addView(title, new LinearLayout.LayoutParams(0, rem ? dp(44) : -2, 1));
        addHeaderButton(header, R.string.map_jump, this::jumpDialog, rem);
        addHeaderButton(header, R.string.permissions, this::setupDialog, rem);
        root.addView(header);
        if (rem) {
            root.addView(RemArt.lace(this, true), new LinearLayout.LayoutParams(-1, dp(12)));
        }

        FrameLayout body = new FrameLayout(this);
        int cardOverlap = rem ? dp(24) : 0;
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        body.addView(stack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout mapFrame = new FrameLayout(this);
        map = new RouteMap(this, draftStore.getBoolean("franceFirst", true), font);
        map.onManualMove = this::stopFollowing;
        map.tapListener = point -> {
            if (ReplayService.status.active) return;
            if (points.size() >= Route.MAX_POINTS) {
                toast(getString(R.string.point_limit));
                return;
            }
            points.add(point);
            updateRoute();
            persistDraft();
        };
        mapFrame.addView(map, new FrameLayout.LayoutParams(-1, -1));
        if (!rem) {
            mapFrame.addView(mapTip(getString(R.string.map_gestures), theme.ink), mapTipPosition(10));
            locationInfo = mapTip("", 0xff1e52c2);
            mapFrame.addView(locationInfo, mapTipPosition(42));
        }
        LinearLayout zoom = new LinearLayout(this);
        zoom.setOrientation(LinearLayout.VERTICAL);
        zoom.setGravity(Gravity.END);
        if (rem) {
            locate = addMapIcon(zoom, R.drawable.ic_map_locate, R.string.follow_location, this::locateMe);
            addMapIcon(zoom, R.drawable.ic_zoom_in, R.string.zoom_in, () -> map.zoomBy(1));
            addMapIcon(zoom, R.drawable.ic_zoom_out, R.string.zoom_out, () -> map.zoomBy(-1));
        } else {
            locate = button(getString(R.string.locate), true, this::locateMe);
            locate.setContentDescription(getString(R.string.follow_location));
            zoom.addView(locate, new LinearLayout.LayoutParams(dp(84), dp(46)));
            Button zoomIn = button("＋", false, () -> map.zoomBy(1));
            zoomIn.setContentDescription(getString(R.string.zoom_in));
            Button zoomOut = button("－", false, () -> map.zoomBy(-1));
            zoomOut.setContentDescription(getString(R.string.zoom_out));
            zoom.addView(zoomIn, new LinearLayout.LayoutParams(dp(48), dp(46)));
            zoom.addView(zoomOut, new LinearLayout.LayoutParams(dp(48), dp(46)));
        }
        FrameLayout.LayoutParams zoomPosition = new FrameLayout.LayoutParams(-2, -2,
                (rem ? Gravity.TOP : Gravity.BOTTOM) | Gravity.END);
        zoomPosition.setMargins(0, rem ? dp(2) : 0, dp(8), rem ? 0 : dp(22));
        mapFrame.addView(zoom, zoomPosition);
        TextView credits = text("© OpenStreetMap · © OpenMapTiles · OSM France", 9, theme.ink);
        credits.setTypeface(Typeface.DEFAULT);
        credits.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        credits.setMaxLines(1);
        credits.setAutoSizeTextTypeUniformWithConfiguration(7, 9, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        credits.setPadding(dp(5), 0, dp(3), 0);
        credits.setBackground(shape(0xebffffff, 6));
        credits.setContentDescription(getString(R.string.map_credits));
        credits.setOnClickListener(view -> mapCredits());
        FrameLayout.LayoutParams creditsPosition = new FrameLayout.LayoutParams(-1, dp(16),
                Gravity.BOTTOM | Gravity.START);
        creditsPosition.setMargins(dp(8), 0, dp(rem ? 144 : 8), dp(3) + cardOverlap);
        mapFrame.addView(credits, creditsPosition);
        stack.addView(mapFrame, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout footer = panel;
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        routeInfo = text("", rem ? 19 : 13, theme.ink);
        if (rem) {
            routeInfo.setTypeface(font, Typeface.BOLD);
            line.setOrientation(LinearLayout.VERTICAL);
            line.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }
        routeInfo.setMaxLines(2);
        routeInfo.setPadding(dp(4), 0, 0, 0);
        line.addView(routeInfo, rem ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));
        loop = new CheckBox(this);
        loop.setTypeface(font);
        loop.setText(getString(R.string.loop));
        loop.setTextColor(theme.ink);
        if (rem) {
            loop.setTextSize(13);
            loop.setMinHeight(dp(30));
            loop.setMinimumHeight(dp(30));
        }
        loop.setOnCheckedChangeListener((view, checked) -> {
            updateRoute();
            persistDraft();
        });
        line.addView(loop);
        FrameLayout routeCard = rem ? remRouteCard(line) : null;
        if (routeCard != null) {
            panel.addView(routeCard);
            footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.VERTICAL);
            panel.addView(footer);
        } else {
            panel.addView(line);
        }
        footer.setPadding(dp(12), rem ? 0 : dp(4), dp(12), dp(8));

        LinearLayout edit = new LinearLayout(this);
        save = button(getString(R.string.save), false, this::saveDialog);
        load = button(getString(R.string.saved_routes), false, this::libraryDialog);
        undo = button(getString(R.string.undo), false, () -> {
            if (!points.isEmpty()) points.remove(points.size() - 1);
            updateRoute();
            persistDraft();
        });
        clear = button(getString(R.string.clear), false, () -> {
            points.clear();
            name = "";
            updateRoute();
            persistDraft();
        });
        for (Button control : new Button[]{save, load, undo, clear}) {
            edit.addView(control, weightedButton());
        }
        footer.addView(edit);

        LinearLayout pace = new LinearLayout(this);
        pace.setGravity(Gravity.CENTER_VERTICAL);
        speedLabel = text("", 13, theme.ink);
        speedLabel.setPadding(dp(4), 0, 0, 0);
        pace.addView(speedLabel);
        speedSlider = new SeekBar(this);
        speedSlider.setMax(MAX_SPEED_PROGRESS);
        speedSlider.setContentDescription(getString(R.string.speed_description));
        pace.addView(speedSlider, new LinearLayout.LayoutParams(0, dp(42), 1));
        speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speed = Route.MIN_SPEED + progress / 10f;
                updateSpeedLabel();
                if (fromUser && !seekBar.isPressed()) commitSpeed();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { commitSpeed(); }
        });
        pace.addView(button(getString(R.string.fine_tune), false, MainActivity.this::speedDialog));
        footer.addView(pace);

        status = text("", 12, theme.muted);
        status.setPadding(dp(4), 0, dp(4), dp(2));
        footer.addView(status);
        LinearLayout actions = new LinearLayout(this);
        startPause = button(getString(R.string.start_replay), true, this::startOrPause);
        stop = button(getString(R.string.stop), false, () -> command(ReplayService.STOP));
        actions.addView(startPause, new LinearLayout.LayoutParams(0, dp(48), 2));
        LinearLayout.LayoutParams stopParams = weightedButton();
        stopParams.setMarginStart(dp(8));
        actions.addView(stop, stopParams);
        LinearLayout.LayoutParams actionsPosition = new LinearLayout.LayoutParams(-1, -2);
        actionsPosition.topMargin = rem ? dp(8) : 0;
        footer.addView(actions, actionsPosition);
        LinearLayout.LayoutParams panelPosition = new LinearLayout.LayoutParams(-1, -2);
        panelPosition.topMargin = -cardOverlap;
        stack.addView(panel, panelPosition);
        if (routeCard != null) {
            addOverlappingPortrait(body, panel, routeCard);
        }
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        buildDrawer(screen);
        setContentView(screen);
        screen.requestApplyInsets();
    }

    private void addHeaderButton(LinearLayout header, int label, Runnable action, boolean rem) {
        Button control = button(getString(label), false, action);
        if (rem) {
            RippleDrawable background = new RippleDrawable(ColorStateList.valueOf(0x1a326dcc),
                    shape(theme.soft, 10), null);
            control.setBackground(new InsetDrawable(background, dp(2), dp(6), dp(2), dp(6)));
            control.setPadding(dp(11), 0, dp(11), 0);
        }
        header.addView(control);
    }

    private TextView mapTip(String label, int color) {
        TextView tip = text(label, 12, color);
        tip.setPadding(dp(10), dp(5), dp(10), dp(5));
        tip.setBackground(shape(theme.paper, 10));
        return tip;
    }

    private FrameLayout.LayoutParams mapTipPosition(int top) {
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        position.setMargins(dp(10), dp(top), dp(5), 0);
        return position;
    }

    private ImageButton addMapIcon(LinearLayout cluster, int icon, int label, Runnable action) {
        ImageButton control = new ImageButton(this);
        control.setImageResource(icon);
        control.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        control.setPadding(dp(10), dp(10), dp(10), dp(10));
        control.setColorFilter(theme.ink);
        control.setElevation(dp(3));
        control.setBackground(new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(0x33326dcc),
                shape(0xfaffffff, 18), null), dp(3)));
        control.setContentDescription(getString(label));
        control.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(36), dp(36));
        cluster.addView(control, size);
        return control;
    }

    private void addOverlappingPortrait(FrameLayout body, LinearLayout panel, FrameLayout card) {
        ImageView portrait = RemArt.framedIllustration(this, R.drawable.rem_portrait,
                new RectF(.108f, .005f, .866f, .991f), true);
        int height = dp(170);
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(dp(191), height,
                Gravity.TOP | Gravity.END);
        body.addView(portrait, size);
        panel.addOnLayoutChangeListener((view, left, top, right, bottom, previousLeft, previousTop,
                                         previousRight, previousBottom) ->
                portrait.setTranslationY(Math.max(0, top + card.getBottom() - height)));
    }

    private FrameLayout remRouteCard(LinearLayout details) {
        FrameLayout card = new FrameLayout(this);
        card.addView(RemArt.routeSurface(this), new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams description = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        description.setMarginStart(dp(18));
        description.setMarginEnd(dp(142));
        card.addView(details, description);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(94));
        layout.bottomMargin = dp(8);
        card.setLayoutParams(layout);
        return card;
    }

    private void buildDrawer(FrameLayout screen) {
        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);
        drawerShade = new View(this);
        drawerShade.setBackgroundColor(0x660e1420);
        drawerShade.setContentDescription(getString(R.string.close_menu));
        drawerShade.setOnClickListener(view -> closeDrawer());
        drawer.addView(drawerShade, new FrameLayout.LayoutParams(-1, -1));

        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setBackgroundColor(theme.paper);
        drawerPanel.setPadding(dp(18), dp(20), dp(18), dp(20));
        drawerPanel.setClickable(true);
        drawerPanel.setElevation(dp(12));
        if (theme == AppSettings.Theme.REM) {
            addRemDrawerHeader();
        } else {
            TextView title = text(getString(R.string.app_name), 24, theme.ink);
            title.setTypeface(font, Typeface.BOLD);
            title.setPadding(0, 0, 0, dp(20));
            drawerPanel.addView(title);
        }
        addDrawerOption(R.string.change_theme, getString(theme.label), R.drawable.ic_rem_palette, this::chooseTheme);
        addDrawerOption(R.string.change_language, AppSettings.LANGUAGE_NAMES[AppSettings.languageIndex(this)],
                R.drawable.ic_rem_language, this::chooseLanguage);
        addDrawerOption(R.string.map_source, "", R.drawable.ic_map_layers, this::mapSourceDialog);
        addDrawerOption(R.string.change_font, getString(fontStyle.label), R.drawable.ic_font_style, this::chooseFont);
        drawerWidth = Math.min(dp(292), getResources().getDisplayMetrics().widthPixels - dp(52));
        drawer.addView(drawerPanel, new FrameLayout.LayoutParams(drawerWidth, -1, Gravity.LEFT));
        screen.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addRemDrawerHeader() {
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xffdfebff, 0xffbcd5fb});
        background.setCornerRadius(dp(17));
        hero.setBackground(background);
        hero.setClipToOutline(true);
        ImageView artwork = new ImageView(this);
        artwork.setImageResource(R.drawable.rem_drawer);
        artwork.setScaleType(ImageView.ScaleType.FIT_END);
        artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(artwork, new FrameLayout.LayoutParams(dp(148), -1, Gravity.END));
        hero.addView(RemArt.lace(this, true), new FrameLayout.LayoutParams(-1, dp(13), Gravity.TOP));
        hero.addView(RemArt.lace(this, false), new FrameLayout.LayoutParams(-1, dp(13), Gravity.BOTTOM));
        TextView title = RemArt.wordmark(this, getString(R.string.app_name), theme.ink, 21);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP | Gravity.START);
        titleParams.setMarginStart(dp(13));
        titleParams.setMarginEnd(dp(104));
        titleParams.topMargin = dp(27);
        hero.addView(title, titleParams);
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_rem_close);
        close.setColorFilter(theme.ink);
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.soft),
                shape(0xeef9fbff, 20), null));
        close.setContentDescription(getString(R.string.close_menu));
        close.setOnClickListener(view -> closeDrawer());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.END);
        closeParams.topMargin = dp(8);
        closeParams.setMarginEnd(dp(8));
        hero.addView(close, closeParams);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(152));
        layout.bottomMargin = dp(14);
        drawerPanel.addView(hero, layout);
    }

    private void addDrawerOption(int label, String value, int icon, Runnable action) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(62));
        layout.bottomMargin = dp(8);
        if (theme != AppSettings.Theme.REM) {
            Button option = button(getString(label) + (value.isEmpty() ? "" : "\n" + value), false, action);
            option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            option.setPadding(dp(14), 0, dp(12), 0);
            drawerPanel.addView(option, layout);
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26326dcc),
                shape(theme.soft, 14), null));
        row.setOnClickListener(view -> action.run());
        row.setFocusable(true);
        row.setContentDescription(getString(label) + (value.isEmpty() ? "" : " · " + value));
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(icon);
        glyph.setColorFilter(theme.accent);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(21), dp(21)));
        TextView heading = text(getString(label), 14, theme.ink);
        heading.setTypeface(font, Typeface.BOLD);
        heading.setPadding(dp(8), 0, 0, 0);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (!value.isEmpty()) {
            TextView selection = text(value, 12, theme.muted);
            selection.setMaxWidth(dp(102));
            selection.setSingleLine();
            selection.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(selection);
        }
        TextView chevron = text("›", 21, theme.accent);
        chevron.setPadding(dp(7), 0, 0, 0);
        row.addView(chevron);
        drawerPanel.addView(row, layout);
    }

    private void openDrawer() {
        mainContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        drawer.setVisibility(View.VISIBLE);
        drawerPanel.animate().cancel();
        drawerShade.animate().cancel();
        drawerPanel.setTranslationX(-drawerWidth);
        drawerShade.setAlpha(0);
        drawerPanel.animate().translationX(0).setDuration(220).start();
        drawerShade.animate().alpha(1).setDuration(220).start();
        drawerPanel.getChildAt(1).requestFocus();
    }

    private void closeDrawer() {
        drawerPanel.animate().translationX(-drawerWidth).setDuration(180).withEndAction(() -> {
            drawer.setVisibility(View.GONE);
            mainContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            menuButton.requestFocus();
        }).start();
        drawerShade.animate().alpha(0).setDuration(180).start();
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else super.onBackPressed();
    }

    private void chooseTheme() {
        AppSettings.Theme[] themes = AppSettings.Theme.values();
        List<OptionDialog.Option> options = new ArrayList<>();
        for (AppSettings.Theme choice : themes) {
            options.add(new OptionDialog.Option(getString(choice.label), 0, choice.accent));
        }
        new OptionDialog(this, font).show(R.string.change_theme,
                theme == AppSettings.Theme.REM ? R.drawable.rem_themes : 0,
                options, theme.ordinal(), R.string.cancel, which -> {
                    if (themes[which] == theme) return;
                    AppSettings.setTheme(this, themes[which]);
                    applyAppearance();
                });
    }

    private void chooseLanguage() {
        int current = AppSettings.languageIndex(this);
        List<OptionDialog.Option> options = new ArrayList<>();
        for (String language : AppSettings.LANGUAGE_NAMES) options.add(new OptionDialog.Option(language, 0, 0));
        new OptionDialog(this, font).show(R.string.change_language,
                theme == AppSettings.Theme.REM ? R.drawable.rem_languages : 0,
                options, current, R.string.cancel, which -> {
                    if (which == current) return;
                    AppSettings.setLanguage(this, which);
                    applyAppearance();
                });
    }

    private void chooseFont() {
        AppSettings.FontStyle[] styles = AppSettings.FontStyle.values();
        List<OptionDialog.Option> options = new ArrayList<>();
        for (AppSettings.FontStyle style : styles) {
            options.add(new OptionDialog.Option(getString(style.label), 0, 0,
                    getString(R.string.font_sample), style.load(this)));
        }
        new OptionDialog(this, font).show(R.string.change_font, 0, options, fontStyle.ordinal(),
                R.string.cancel, which -> {
                    if (styles[which] == fontStyle) return;
                    AppSettings.setFontStyle(this, styles[which]);
                    applyAppearance();
                });
    }

    private void applyAppearance() {
        persistDraft();
        if (ReplayService.status.active) command(ReplayService.PREFERENCES);
        recreate();
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(font);
        return view;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTypeface(font);
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{theme.muted, primary ? Color.WHITE : theme.ink}));
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33ffffff : ((theme.accent & 0x00ffffff) | 0x1a000000)),
                shape(primary ? theme.accent : theme.soft, 10), null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMarginEnd(dp(3));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void updateRoute() {
        Route route = draft();
        map.setRoute(points, route.loop);
        String description = route.length == 0 && points.size() == 1 ? getString(R.string.fixed_position)
                : getString(R.string.point_distance, points.size(), route.length);
        routeInfo.setText(name.isEmpty() ? description : name + " · " + description);
        updateSpeedLabel();
        showStatus();
    }

    private void showStatus() {
        if (status == null) return;
        ReplayService.Status replay = ReplayService.status;
        boolean precise = hasPreciseLocation();
        devicePosition.track(visible && !replay.active && (precise || hasCoarseLocation()), precise);
        renderPosition();
        String message = replay.message(this);
        if (!status.getText().toString().equals(message)) status.setText(message);
        int statusVisibility = theme == AppSettings.Theme.REM && !replay.active
                && message.equals(getString(R.string.waiting)) ? View.GONE : View.VISIBLE;
        status.setVisibility(statusVisibility);
        String action = !replay.active ? getString(R.string.start_replay) : replay.paused ? getString(R.string.resume_replay) : getString(R.string.pause_replay);
        if (!startPause.getText().toString().equals(action)) startPause.setText(action);
        startPause.setEnabled(replay.active || !points.isEmpty());
        stop.setEnabled(replay.active);
        save.setEnabled(!replay.active && !points.isEmpty());
        load.setEnabled(!replay.active);
        undo.setEnabled(!replay.active && !points.isEmpty());
        clear.setEnabled(!replay.active && !points.isEmpty());
        loop.setEnabled(!replay.active);
    }

    private boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCoarseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void renderPosition() {
        ReplayService.Status replay = ReplayService.status;
        DevicePosition.Fix fix = devicePosition.latest();
        String caption;
        if (replay.active) {
            map.setMarker(replay.point, 0, true, replay.bearing);
            caption = getString(R.string.mock_marker);
        } else if (fix != null) {
            map.setMarker(fix.point, fix.accuracy, false, devicePosition.heading(fix));
            caption = fix.accuracy > 0
                    ? getString(R.string.real_accuracy, Math.round(fix.accuracy))
                    : getString(R.string.real_marker);
            if (following && (zoomOnNextFix || lastCentred != fix)) {
                double level = zoomOnNextFix ? (fix.accuracy > 500 ? 13 : 17) : map.zoomLevel();
                map.centreOn(fix.point, level);
                lastCentred = fix;
                zoomOnNextFix = false;
            }
        } else {
            map.setMarker(null, 0, false, Float.NaN);
            if (!hasPreciseLocation() && !hasCoarseLocation()) caption = getString(R.string.tap_locate);
            else if (!devicePosition.isEnabled()) caption = getString(R.string.enable_location);
            else caption = getString(R.string.locating);
        }
        if (locationInfo != null) {
            locationInfo.setTextColor(replay.active ? 0xff7541ad : 0xff1e52c2);
            if (!locationInfo.getText().toString().equals(caption)) locationInfo.setText(caption);
        }
        boolean tracking = !replay.active && following && fix != null;
        String buttonLabel = getString(tracking ? R.string.following : R.string.locate);
        if (locate instanceof Button button) {
            if (!button.getText().toString().equals(buttonLabel)) button.setText(buttonLabel);
        } else {
            if (locate.isSelected() != tracking) {
                locate.setSelected(tracking);
                ((ImageButton) locate).setColorFilter(tracking ? theme.accent : theme.ink);
            }
            String label = tracking ? buttonLabel : getString(R.string.follow_location);
            String description = label + " · " + caption;
            if (!TextUtils.equals(locate.getContentDescription(), description)) {
                locate.setContentDescription(description);
            }
        }
    }

    private void stopFollowing() {
        following = false;
        renderPosition();
    }

    private void locateMe() {
        if (ReplayService.status.active) {
            toast(getString(R.string.stop_to_locate));
            return;
        }
        following = true;
        zoomOnNextFix = true;
        if (!hasPreciseLocation() && !hasCoarseLocation()) {
            if (draftStore.getBoolean("locationAsked", false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                draftStore.edit().putBoolean("locationAsked", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            }
        } else if (!devicePosition.isEnabled()) {
            open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }
        showStatus();
    }

    private void updateSpeedLabel() {
        speedLabel.setText(getString(R.string.speed_value, speed));
    }

    private void commitSpeed() {
        persistDraft();
        if (ReplayService.status.active) {
            startService(new Intent(this, ReplayService.class).setAction(ReplayService.SPEED)
                    .putExtra(ReplayService.KMH, speed));
        }
    }

    private void speedDialog() {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(String.format(Locale.CHINA, "%.1f", speed));
        field.selectAll();
        prompt(getString(R.string.speed_title), getString(R.string.speed_hint), field, () -> {
            try {
                float value = Float.parseFloat(field.getText().toString().trim());
                if (!Float.isFinite(value) || value < Route.MIN_SPEED || value > Route.MAX_SPEED) {
                    throw new NumberFormatException();
                }
                speedSlider.setProgress(Math.round((value - Route.MIN_SPEED) * 10));
                commitSpeed();
                return true;
            } catch (NumberFormatException exception) {
                field.setError(getString(R.string.speed_error));
                return false;
            }
        });
    }

    private void jumpDialog() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setHint("39.90750, 116.39130");
        prompt(getString(R.string.map_jump), getString(R.string.jump_hint), field, () -> {
            try {
                String[] pair = field.getText().toString().trim().split("[,，\\s]+");
                if (pair.length != 2) throw new IllegalArgumentException();
                Route.Point location = new Route.Point(Double.parseDouble(pair[0]), Double.parseDouble(pair[1]));
                stopFollowing();
                map.centreOn(location, 17);
                persistDraft();
                return true;
            } catch (IllegalArgumentException exception) {
                field.setError(getString(R.string.invalid_coords));
                return false;
            }
        });
    }

    private void mapSourceDialog() {
        boolean franceFirst = draftStore.getBoolean("franceFirst", true);
        showNative(new AlertDialog.Builder(this).setTitle(getString(R.string.map_source))
                .setSingleChoiceItems(new String[]{getString(R.string.osm_france_first), getString(R.string.osm_standard_first)},
                        franceFirst ? 0 : 1, (dialog, which) -> {
                            boolean preferFrance = which == 0;
                            draftStore.edit().putBoolean("franceFirst", preferFrance).apply();
                            map.reload(preferFrance);
                            dialog.dismiss();
                        })
                .setPositiveButton(getString(R.string.retry), (dialog, which) -> map.reload(franceFirst))
                .setNegativeButton(getString(R.string.close), null));
    }

    private void mapCredits() {
        String[] names = {"OpenStreetMap", "OpenStreetMap France", "OpenMapTiles", "OpenFreeMap"};
        String[] urls = {"https://www.openstreetmap.org/copyright",
                "https://www.openstreetmap.fr/fonds-de-carte/", "https://openmaptiles.org/",
                "https://openfreemap.org/"};
        showNative(new AlertDialog.Builder(this).setTitle(getString(R.string.map_data))
                .setItems(names, (dialog, which) -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(urls[which]))))
                .setNegativeButton(getString(R.string.close), null));
    }

    private void showNative(AlertDialog.Builder builder) {
        styleNative(builder.show());
    }

    private void styleNative(AlertDialog dialog) {
        applyDialogFont(dialog.getWindow().getDecorView());
        if (dialog.getListView() != null) {
            dialog.getListView().setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int state) { }
                @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                    applyDialogFont(view);
                }
            });
        }
    }

    private void applyDialogFont(View view) {
        if (view instanceof TextView label) {
            Typeface current = label.getTypeface();
            label.setTypeface(font, current == null ? Typeface.NORMAL : current.getStyle());
        } else if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) applyDialogFont(group.getChildAt(i));
        }
    }

    private interface Confirm { boolean run(); }

    private void prompt(String title, String message, EditText field, Confirm confirm) {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(23), dp(6), dp(23), 0);
        wrapper.addView(field, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setView(wrapper).setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.ok), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (confirm.run()) dialog.dismiss();
                }));
        dialog.show();
        styleNative(dialog);
    }

    private void saveDialog() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        field.setText(name);
        field.setHint(getString(R.string.route_example));
        prompt(getString(R.string.save_route), getString(R.string.save_route_hint), field, () -> {
            String label = field.getText().toString().trim();
            if (label.isEmpty()) {
                field.setError(getString(R.string.route_name_required));
                return false;
            }
            Route route = new Route(label, points, loop.isChecked(), speed);
            library.edit().putString(UUID.randomUUID().toString(), route.encode()).apply();
            name = label;
            updateRoute();
            persistDraft();
            toast(getString(R.string.route_saved));
            return true;
        });
    }

    private static final class Saved {
        final String id;
        final Route route;

        Saved(String id, Route route) {
            this.id = id;
            this.route = route;
        }
    }

    private void libraryDialog() {
        List<Saved> saved = new ArrayList<>();
        for (Map.Entry<String, ?> item : library.getAll().entrySet()) {
            try {
                if (item.getValue() instanceof String) {
                    saved.add(new Saved(item.getKey(), Route.decode((String) item.getValue())));
                }
            } catch (JSONException | IllegalArgumentException exception) {
                android.util.Log.w("RouteLab", "跳过无法读取的路线", exception);
            }
        }
        if (saved.isEmpty()) {
            toast(getString(R.string.no_routes));
            return;
        }
        saved.sort(Comparator.comparing(item -> item.route.name));
        String[] labels = new String[saved.size()];
        for (int i = 0; i < labels.length; i++) {
            Route route = saved.get(i).route;
            labels[i] = getString(R.string.route_row, route.name, route.length,
                    route.loop ? " " + getString(R.string.loop_suffix) : "");
        }
        showNative(new AlertDialog.Builder(this).setTitle(getString(R.string.saved_routes))
                .setItems(labels, (dialog, which) -> savedActions(saved.get(which)))
                .setNegativeButton(getString(R.string.close), null));
    }

    private void savedActions(Saved item) {
        showNative(new AlertDialog.Builder(this).setTitle(item.route.name)
                .setMessage(getString(R.string.load_route_warning))
                .setPositiveButton(getString(R.string.load), (dialog, which) -> {
                    stopFollowing();
                    applyRoute(item.route);
                    map.fit(points);
                    persistDraft();
                }).setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.delete_more), (dialog, which) -> confirmDelete(item)));
    }

    private void confirmDelete(Saved item) {
        showNative(new AlertDialog.Builder(this).setTitle(getString(R.string.delete_confirm))
                .setMessage(item.route.name)
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    library.edit().remove(item.id).apply();
                    toast(getString(R.string.deleted));
                }).setNegativeButton(getString(R.string.cancel), null));
    }

    private void setupDialog() {
        String[] options = {getString(R.string.tutorial), getString(R.string.select_mock_option), getString(R.string.allow_overlay), getString(R.string.allow_notifications)};
        if (theme == AppSettings.Theme.REM) {
            List<OptionDialog.Option> actions = Arrays.asList(
                    new OptionDialog.Option(options[0], R.drawable.ic_help_book, 0),
                    new OptionDialog.Option(options[1], R.drawable.ic_help_code, 0),
                    new OptionDialog.Option(options[2], R.drawable.ic_help_overlay, 0),
                    new OptionDialog.Option(options[3], R.drawable.ic_help_bell, 0));
            new OptionDialog(this, font).show(R.string.permissions_help, R.drawable.rem_help,
                    actions, -1, R.string.close, this::openPermission);
        } else {
            showNative(new AlertDialog.Builder(this).setTitle(getString(R.string.permissions_help))
                    .setItems(options, (dialog, which) -> openPermission(which))
                    .setNegativeButton(R.string.close, null));
        }
    }

    private void openPermission(int which) {
        if (which == 0) {
            showTutorial();
        } else if (which == 1) {
            open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } else if (which == 2) {
            open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !draftStore.getBoolean("noticeAsked", false)) {
            draftStore.edit().putBoolean("noticeAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        } else {
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void showTutorial() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setPadding(dp(18), dp(6), dp(18), dp(10));
        scroll.addView(sections);

        addTutorialSection(sections, R.string.guide_location_title, R.string.guide_location_body);
        addTutorialSection(sections, R.string.guide_map_title, R.string.guide_map_body);
        addTutorialSection(sections, R.string.guide_route_title, R.string.guide_route_body);
        addTutorialSection(sections, R.string.guide_developer_title, R.string.guide_developer_body);
        addTutorialSection(sections, R.string.guide_replay_title, R.string.guide_replay_body);
        addTutorialSection(sections, R.string.guide_overlay_title, R.string.guide_overlay_body);
        addTutorialSection(sections, R.string.guide_settings_title, R.string.guide_settings_body);
        addTutorialSection(sections, R.string.guide_trouble_title, R.string.guide_trouble_body);

        showNative(new AlertDialog.Builder(this).setTitle(getString(R.string.tutorial)).setView(scroll)
                .setPositiveButton(getString(R.string.done), null));
    }

    private void addTutorialSection(LinearLayout sections, int title, int instructions) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(12));
        card.setBackground(shape(theme.paper, 12));
        TextView heading = text(getString(title), 15, theme.accent);
        heading.setTypeface(font, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        card.addView(heading);
        TextView content = text(getString(instructions), 14, theme.ink);
        content.setLineSpacing(dp(3), 1);
        content.setPadding(0, dp(5), 0, 0);
        card.addView(content);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        sections.addView(card, params);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            toast(getString(R.string.settings_unavailable));
        }
    }

    private void startOrPause() {
        if (ReplayService.status.active) {
            command(ReplayService.TOGGLE);
        } else if (!points.isEmpty()) {
            Intent run = new Intent(this, ReplayService.class).setAction(ReplayService.START)
                    .putExtra(ReplayService.DATA, draft().encode());
            try {
                startForegroundService(run);
            } catch (IllegalStateException | SecurityException exception) {
                toast(getString(R.string.service_start_failed));
            }
        }
    }

    private void command(String action) {
        startService(new Intent(this, ReplayService.class).setAction(action));
    }

    private Route draft() { return new Route(name, points, loop.isChecked(), speed); }

    private void applyRoute(Route route) {
        points.clear();
        points.addAll(route.points);
        name = route.name;
        speed = route.speed;
        speedSlider.setProgress(Math.round((speed - Route.MIN_SPEED) * 10));
        loop.setChecked(route.loop);
        updateRoute();
    }

    private void restoreDraft() {
        String stored = draftStore.getString("route", null);
        if (stored != null) {
            try {
                Route route = Route.decode(stored);
                Route.Point centre = new Route.Point(
                        Double.longBitsToDouble(draftStore.getLong("lat", Double.doubleToLongBits(39.9075))),
                        Double.longBitsToDouble(draftStore.getLong("lon", Double.doubleToLongBits(116.3913))));
                map.centreOn(centre, Double.longBitsToDouble(draftStore.getLong("zoomFraction",
                        Double.doubleToLongBits(draftStore.getInt("zoom", 15)))));
                applyRoute(route);
                return;
            } catch (JSONException | IllegalArgumentException exception) {
                android.util.Log.w("RouteLab", "草稿无法恢复", exception);
            }
        }
        speedSlider.setProgress(Math.round((Route.DEFAULT_SPEED - Route.MIN_SPEED) * 10));
    }

    private void persistDraft() {
        Route.Point centre = map.centre();
        draftStore.edit().putString("route", draft().encode())
                .putLong("zoomFraction", Double.doubleToLongBits(map.zoomLevel()))
                .putLong("lat", Double.doubleToLongBits(centre.lat))
                .putLong("lon", Double.doubleToLongBits(centre.lon)).apply();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        refresh.run();
    }

    @Override protected void onResume() {
        super.onResume();
        showStatus();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) showStatus();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("following", following);
        state.putBoolean("zoomOnNextFix", zoomOnNextFix);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        visible = false;
        devicePosition.track(false, false);
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override protected void onPause() {
        persistDraft();
        super.onPause();
    }

    @Override protected void onDestroy() {
        map.close();
        super.onDestroy();
    }
}
