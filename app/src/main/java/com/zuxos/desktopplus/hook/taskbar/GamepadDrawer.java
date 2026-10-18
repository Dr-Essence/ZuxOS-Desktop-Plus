package com.zuxos.desktopplus.hook.taskbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.hook.system.SystemGamepad;

/**
 * A game controller's Home button, taken in the system ({@link SystemGamepad}), opens and closes
 * ZUI's drawer: as a tap on our start button does, on the screen the button was pressed for, and
 * on the tablet's when that one has no button showing. Only the system may ask ({@link #SENDER}).
 */
final class GamepadDrawer {

    /** What a sender must hold: input injection, the system's and the shell's alone. */
    private static final String SENDER = "android.permission.INJECT_EVENTS";

    private static boolean sListening;
    private static int sSaid;

    private GamepadDrawer() {
    }

    /** Listens once per process, from the first start button made. Main thread. */
    static void listen(Context ctx) {
        if (sListening || !Cfg.gamepadDrawer() || ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        if (app == null) {
            return;
        }
        sListening = true;
        try {
            // Only the system may ask: it holds every permission, and no app holds this one. The
            // sender's own name is no test - Android gives it only to a sender that shares it,
            // which the system's broadcasts do not: every press was refused (owner, 10:36:45).
            app.registerReceiver(new Receiver(), new IntentFilter(SystemGamepad.ACTION_DRAWER),
                    SENDER, null, Context.RECEIVER_EXPORTED);
            L.i("gamepad drawer: a controller's Home button opens and closes the drawer");
        } catch (Throwable t) {
            L.w("gamepad drawer: not listening (" + t + ")");
        }
    }

    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                int display = intent.getIntExtra(SystemGamepad.EXTRA_DISPLAY, 0);
                boolean pressed = TaskbarStart.toggleDrawer(display)
                        || (display != 0 && TaskbarStart.toggleDrawer(0));
                if (sSaid < 3) {
                    sSaid++;
                    L.i("gamepad drawer: Home on a controller for display " + display + " - "
                            + (pressed ? "the start button pressed"
                            : "no start button there to press"));
                }
            } catch (Throwable t) {
                L.w("gamepad drawer: the drawer left as it is (" + t + ")");
            }
        }
    }
}
