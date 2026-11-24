package com.zuxos.desktopplus.hook;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Su;

/**
 * Evidence only: pictures of both screens' lock screens, so the monitor's can be made to look like
 * the tablet's. What the tablet's lock screen is drawn with is not in its logs, and a photo is
 * one more thing to ask for; these are taken by themselves, once a run, the first time the
 * screens wake locked with the monitor plugged in: every physical screen through root's
 * {@code screencap}, a few seconds after waking (the clock) and again later (the pad, once it is
 * clicked), while still locked, into Download as {@code lock-<port>-<n>.png}. Goes with the look
 * fix.
 */
public final class LockShots {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** After waking: the clock, then the pad once it has been clicked. */
    private static final long[] AFTER_MS = {3000L, 12000L};

    private static boolean sStarted;
    private static boolean sTaken;

    private LockShots() {
    }

    /** Started with the launcher; again is a no-op. */
    public static synchronized void start(Context ctx) {
        if (sStarted || ctx == null) {
            return;
        }
        sStarted = true;
        try {
            Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    woke(app);
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            L.d("lock pictures: not watched (" + t + ")");
        }
    }

    private static void woke(Context app) {
        try {
            if (sTaken || !Cfg.useRoot() || !Cfg.unlockOnMonitor() || !locked(app)) {
                return;
            }
            DisplayManager displays = app.getSystemService(DisplayManager.class);
            if (displays == null || displays.getDisplays(
                    DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length == 0) {
                return;
            }
            sTaken = true;
            for (int i = 0; i < AFTER_MS.length; i++) {
                int n = i + 1;
                MAIN.postDelayed(() -> take(app, n), AFTER_MS[i]);
            }
        } catch (Throwable t) {
            L.d("lock pictures: not taken (" + t + ")");
        }
    }

    private static boolean locked(Context app) {
        KeyguardManager keyguard = app.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private static void take(Context app, int n) {
        try {
            if (!locked(app)) {
                return;
            }
            String script = "for s in $(dumpsys SurfaceFlinger --display-id 2>/dev/null"
                    + " | sed -n 's/^Display \\([0-9]*\\) .*port=\\([0-9]*\\).*/\\1:\\2/p'); do\n"
                    + "  f=/sdcard/Download/lock-${s##*:}-" + n + ".png\n"
                    + "  screencap -p -d ${s%%:*} $f && echo \"$f\"\n"
                    + "done\n"
                    + "true";
            Su.read((outcome, text) -> L.i("lock pictures: " + (outcome.ok() && text != null
                    && !text.isEmpty() ? text.replace('\n', ' ').trim() + " (each screen's port"
                    + " as the input system's screens list it)" : "none taken (" + outcome + ")")),
                    script);
        } catch (Throwable t) {
            L.d("lock pictures: not taken (" + t + ")");
        }
    }
}
