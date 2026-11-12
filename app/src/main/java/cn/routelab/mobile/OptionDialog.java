package cn.routelab.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntConsumer;

final class OptionDialog {
    static final class Option {
        final String label;
        final int icon, swatch;
        final String sample;
        final Typeface font;

        Option(String label, int icon, int swatch) { this(label, icon, swatch, null, null); }

        Option(String label, int icon, int swatch, String sample, Typeface font) {
            this.label = label;
            this.icon = icon;
            this.swatch = swatch;
            this.sample = sample;
            this.font = font;
        }
    }

    private final Activity activity;
    private final Typeface font;
    private final AppSettings.Theme palette;

    OptionDialog(Activity activity, Typeface font) {
        this.activity = activity;
        this.font = font;
        palette = AppSettings.theme(activity);
    }

    void show(int title, int art, List<Option> options, int selected, int dismissText, IntConsumer select) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(title);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{palette.paper, palette.soft});
        surface.setCornerRadius(dp(23));
        surface.setStroke(dp(1), palette == AppSettings.Theme.REM ? RemArt.BLUE_EDGE : palette.soft);
        card.setBackground(surface);
        card.setClipToOutline(true);
        card.addView(header(title, art), new LinearLayout.LayoutParams(-1, dp(art == 0 ? 78 : 132)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(4));
        for (int index = 0; index < options.size(); index++) {
            int itemIndex = index;
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, -2);
            rowLayout.bottomMargin = dp(7);
            View row = row(options.get(index), selected >= 0, index == selected);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                select.accept(itemIndex);
            });
            list.addView(row, rowLayout);
        }
        scroll.addView(list);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(activity);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), dp(3), dp(16), dp(9));
        Button close = new Button(activity);
        close.setAllCaps(false);
        close.setText(dismissText);
        close.setTextColor(palette.ink);
        close.setTypeface(font);
        close.setPadding(dp(13), 0, dp(13), 0);
        close.setBackground(ripple(outline(palette.paper, palette.accent, 13)));
        close.setOnClickListener(view -> dialog.dismiss());
        footer.addView(close, new LinearLayout.LayoutParams(-2, dp(44)));
        card.addView(footer);

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.43f);
        dialog.show();
        int width = Math.max(1, Math.min(dp(390), activity.getResources().getDisplayMetrics().widthPixels - dp(32)));
        int availableHeight = activity.getResources().getDisplayMetrics().heightPixels - dp(60);
        int normalHeight = dp((art == 0 ? 78 : 132) + 79);
        for (Option option : options) normalHeight += dp(option.sample == null ? 62 : 78);
        window.setLayout(width, Math.max(1, Math.min(availableHeight, normalHeight)));
    }

    private FrameLayout header(int title, int artwork) {
        FrameLayout header = new FrameLayout(activity);
        header.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{palette.paper, palette.soft}));
        if (artwork != 0) {
            ImageView art = new ImageView(activity);
            art.setImageResource(artwork);
            art.setScaleType(ImageView.ScaleType.FIT_END);
            art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams artPosition = new FrameLayout.LayoutParams(dp(149), -1, Gravity.END | Gravity.BOTTOM);
            artPosition.setMarginEnd(dp(4));
            artPosition.bottomMargin = dp(8);
            header.addView(art, artPosition);
        }
        TextView heading = text(activity.getString(title), 22, palette.ink, Typeface.create(font, Typeface.BOLD));
        heading.setAccessibilityHeading(true);
        if (palette == AppSettings.Theme.REM) {
            heading.setCompoundDrawablePadding(dp(3));
            heading.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_rem_flourish, 0);
        }
        FrameLayout.LayoutParams titlePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        titlePosition.setMarginStart(dp(17));
        titlePosition.setMarginEnd(dp(artwork == 0 ? 17 : 139));
        header.addView(heading, titlePosition);
        if (palette == AppSettings.Theme.REM) {
            header.addView(RemArt.lace(activity, true), new FrameLayout.LayoutParams(-1, dp(14), Gravity.BOTTOM));
        }
        return header;
    }

    private View row(Option option, boolean choices, boolean selected) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(4), dp(10), dp(4));
        row.setMinimumHeight(dp(49));
        row.setFocusable(true);
        row.setSelected(selected);
        row.setContentDescription(option.sample == null ? option.label : option.label + " · " + option.sample);
        row.setBackground(ripple(outline(selected ? palette.soft : palette.paper,
                selected ? palette.accent : Color.WHITE, 13)));
        if (choices) {
            RadioButton radio = new RadioButton(activity);
            radio.setChecked(selected);
            radio.setClickable(false);
            radio.setFocusable(false);
            radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(radio, new LinearLayout.LayoutParams(dp(33), dp(35)));
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(selected);
                }
            });
        } else {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(option.icon);
            icon.setColorFilter(palette.ink);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));
        }
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, dp(4), 0);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        labels.addView(text(option.label, 15, palette.ink, font));
        if (option.sample != null) {
            labels.addView(text(option.sample, 15, palette.ink, option.font));
            String controls = activity.getString(R.string.save_route) + " · "
                    + activity.getString(R.string.map_jump) + " · "
                    + activity.getString(R.string.start_replay) + " · "
                    + activity.getString(R.string.speed_value, 8.0);
            labels.addView(text(controls, 11, palette.muted, option.font));
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (option.swatch != 0) {
            View swatch = new View(activity);
            GradientDrawable colours = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{option.swatch, 0xfff9fbff});
            colours.setCornerRadius(dp(6));
            colours.setStroke(dp(1), Color.WHITE);
            swatch.setBackground(colours);
            row.addView(swatch, new LinearLayout.LayoutParams(dp(34), dp(16)));
        } else if (!choices) {
            row.addView(text("›", 20, palette.ink, font));
        }
        return row;
    }

    private TextView text(String label, int size, int colour, Typeface typeface) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(colour);
        view.setTypeface(typeface);
        return view;
    }

    private GradientDrawable outline(int fill, int stroke, int corner) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setStroke(dp(1), stroke);
        background.setCornerRadius(dp(corner));
        return background;
    }

    private RippleDrawable ripple(GradientDrawable base) {
        return new RippleDrawable(ColorStateList.valueOf(0x26326dcc), base, null);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
