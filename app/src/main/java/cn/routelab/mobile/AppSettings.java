package cn.routelab.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;

import java.util.Locale;

final class AppSettings {
    enum Theme {
        BLUE(R.string.theme_blue, R.style.AppTheme, 0xfff5f8ff, 0xff172e54, 0xff4c6387, 0xff205ccc, 0xffe4eeff),
        GREEN(R.string.theme_green, R.style.AppTheme_Green, 0xfff3faf6, 0xff173b2e, 0xff466b5b, 0xff087956, 0xffe1f1e8),
        RED(R.string.theme_red, R.style.AppTheme_Red, 0xfffff6f7, 0xff49242c, 0xff805660, 0xffb82e42, 0xfff8e3e7),
        MONO(R.string.theme_mono, R.style.AppTheme_Mono, 0xfff7f7f7, 0xff161719, 0xff55585b, 0xff24272b, 0xffe8e9eb),
        REM(R.string.theme_rem, R.style.AppTheme_Rem, 0xfff6f8fe, 0xff172d51, 0xff50688d, 0xff306dcb, 0xffe4ecfe);

        final int label, style, paper, ink, muted, accent, soft;

        Theme(int label, int style, int paper, int ink, int muted, int accent, int soft) {
            this.label = label;
            this.style = style;
            this.paper = paper;
            this.ink = ink;
            this.muted = muted;
            this.accent = accent;
            this.soft = soft;
        }
    }

    enum FontStyle {
        SANS(R.string.font_sans, R.font.noto_sans_family),
        SERIF(R.string.font_serif, R.font.noto_serif_family),
        WENKAI(R.string.font_wenkai, R.font.lxgw_wenkai),
        KUAILE(R.string.font_kuaile, R.font.zcool_kuaile),
        QINGKE(R.string.font_qingke, R.font.zcool_qingke);

        final int label, resource;

        FontStyle(int label, int resource) {
            this.label = label;
            this.resource = resource;
        }

        Typeface load(Context context) { return context.getResources().getFont(resource); }
    }

    static final String[] LANGUAGE_TAGS = {"zh-CN", "en", "ja"};
    static final String[] LANGUAGE_NAMES = {"简体中文", "English", "日本語"};

    private AppSettings() { }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }

    static Theme theme(Context context) {
        String selected = preferences(context).getString("theme", Theme.REM.name());
        for (Theme theme : Theme.values()) {
            if (theme.name().equals(selected)) return theme;
        }
        return Theme.REM;
    }

    static void setTheme(Context context, Theme theme) {
        preferences(context).edit().putString("theme", theme.name()).apply();
    }

    static FontStyle fontStyle(Context context) {
        String selected = preferences(context).getString("font", FontStyle.SANS.name());
        for (FontStyle style : FontStyle.values()) {
            if (style.name().equals(selected)) return style;
        }
        return FontStyle.SANS;
    }

    static void setFontStyle(Context context, FontStyle style) {
        preferences(context).edit().putString("font", style.name()).apply();
    }

    static int languageIndex(Context context) {
        String selected = preferences(context).getString("language", LANGUAGE_TAGS[0]);
        for (int i = 0; i < LANGUAGE_TAGS.length; i++) {
            if (LANGUAGE_TAGS[i].equals(selected)) return i;
        }
        return 0;
    }

    static void setLanguage(Context context, int index) {
        preferences(context).edit().putString("language", LANGUAGE_TAGS[index]).apply();
    }

    static Context localized(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(LANGUAGE_TAGS[languageIndex(base)]));
        return base.createConfigurationContext(config);
    }
}
