package com.zuxos.desktopplus.hook.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.desktop.DesktopHost;

/**
 * The launcher taking a changed setting with nobody force-stopping it: at once where it can, by
 * restarting itself where it cannot.
 *
 * <p>The settings app says when any setting changed ({@link Const#ACTION_SETTINGS_CHANGED}).
 * The launcher reads them again. The ones it reads only as it starts - the theme, the monitor's
 * bar position, which hooks are in - are compared with what it started with, and only a real
 * difference restarts it; Android brings home straight back. Anything else is put on every bar
 * at once ({@link #onLive}): before, a bar setting waited for ZUI to make its bar again.
 */
public final class SettingsRestart {

    private static String sStartedWith;
    private static boolean sListening;
    /** What re-applies the settings that need no restart; run on the main thread. */
    private static final java.util.List<Runnable> LIVE =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private SettingsRestart() {
    }

    /** What the launcher starts with, read as its hooks go in. */
    public static void remember() {
        sStartedWith = current();
    }

    /** Something that takes a changed setting at once, without a restart. */
    public static void onLive(Runnable live) {
        LIVE.add(live);
    }

    /** Listens once, from the first activity that comes up. */
    static void listen(Context ctx) {
        if (sListening || sStartedWith == null) {
            return;
        }
        sListening = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    changed(context);
                }
            };
            IntentFilter filter = new IntentFilter(Const.ACTION_SETTINGS_CHANGED);
            Context app = ctx.getApplicationContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            L.w("settings: not listening for changes (" + t + "), a restart takes them");
        }
    }

    private static void changed(Context ctx) {
        try {
            Cfg.reload();
            String now = current();
            if (now.equals(sStartedWith)) {
                // A setting read as it is used, or put on as a bar is made: on every bar now.
                for (Runnable live : LIVE) {
                    try {
                        live.run();
                    } catch (Throwable t) {
                        L.d("settings: a change not taken at once (" + t + ")");
                    }
                }
                return;
            }
            L.i("settings: " + sStartedWith + " -> " + now + ", the launcher restarts to take "
                    + "them");
            // Coming back for a setting is no new connect: no start animation as it does.
            StartAnimation.skipNext(ctx);
            DesktopHost.saveAllNow();
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable t) {
            L.w("settings: could not restart for the change (" + t + ")");
        }
    }

    /** The start-only settings, as one line. */
    private static String current() {
        return "enabled=" + Cfg.enabled() + " theme=" + Cfg.theme() + " edge=" + Cfg.taskbarEdge()
                + " drawer=" + Cfg.nativeDrawer() + " tray=" + Cfg.taskbarTray()
                + " popups=" + Cfg.notifyPopups() + " unlock=" + Cfg.unlockStock()
                + " monitors=" + Cfg.monitorLayouts() + " grid=" + Cfg.homeGrid()
                + " band=" + Cfg.homeBand();
    }
}
