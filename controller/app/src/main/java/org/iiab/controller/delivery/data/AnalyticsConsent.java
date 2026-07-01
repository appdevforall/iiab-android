package org.iiab.controller.delivery.data;

import android.content.Context;

/** Opt-in flag for usage analytics. Default OFF (privacy-by-default). */
public final class AnalyticsConsent {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "analytics_opt_in";

    private AnalyticsConsent() {
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply();
    }
}
