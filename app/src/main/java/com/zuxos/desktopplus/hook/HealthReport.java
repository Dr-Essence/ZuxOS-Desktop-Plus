package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.notify.NotifyProvider;

import java.util.ArrayList;

/**
 * Hands the launcher's {@link Health} list to the settings app's health screen (#13), through the
 * module's own provider - the only way back from the launcher to the module.
 *
 * <p>Asked on each resume, sent only when the list changed since it was last sent, a few seconds
 * after the last change: one call per launcher start, none while nothing changes. Launcher
 * process only; the system process never sends anything.
 */
public final class HealthReport {

    private static final long DELAY_MS = 3000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static int sSent = -1;
    private static Context sApp;
    private static final Runnable SEND = HealthReport::send;

    private HealthReport() {
    }

    /** Main thread. Sends the list shortly, if it changed. */
    public static void soon(Context ctx) {
        if (ctx == null || !Cfg.health() || Health.version() == sSent) {
            return;
        }
        sApp = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        MAIN.removeCallbacks(SEND);
        MAIN.postDelayed(SEND, DELAY_MS);
    }

    private static void send() {
        Context app = sApp;
        int version = Health.version();
        if (app == null || version == sSent) {
            return;
        }
        sSent = version;
        ArrayList<String> rows = new ArrayList<>();
        for (String[] row : Health.rows()) {
            rows.add(String.join("\t", row));
        }
        Bundle extras = new Bundle();
        extras.putStringArrayList(NotifyProvider.EXTRA_HEALTH_ROWS, rows);
        extras.putString(NotifyProvider.EXTRA_HEALTH_FROM, from(app));
        extras.putLong(NotifyProvider.EXTRA_HEALTH_WHEN, System.currentTimeMillis());
        try {
            app.getContentResolver().call(NotifyProvider.URI, NotifyProvider.METHOD_HEALTH, null,
                    extras);
        } catch (Throwable t) {
            L.d("health: not sent to the settings app (" + t + ")");
        }
    }

    /** "ZUX Home 18.1.2" - the app and version the list is from. */
    private static String from(Context app) {
        try {
            String version = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
            CharSequence label = app.getApplicationInfo().loadLabel(app.getPackageManager());
            return label + " " + version;
        } catch (Throwable t) {
            return app.getPackageName();
        }
    }
}
