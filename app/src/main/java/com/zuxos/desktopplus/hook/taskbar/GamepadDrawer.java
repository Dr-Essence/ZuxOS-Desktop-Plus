package com.zuxos.desktopplus.hook.taskbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Process;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.hook.system.SystemGamepad;

/**
 * A game controller's Home button, taken in the system ({@link SystemGamepad}), opens and closes
 * ZUI's drawer: as a tap on our start button does, on the screen the button was pressed for, and
 * on the tablet's when that one has no button showing. Only the system may ask.
 */
final class GamepadDrawer {

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
            app.registerReceiver(new Receiver(), new IntentFilter(SystemGamepad.ACTION_DRAWER),
                    Context.RECEIVER_EXPORTED);
            L.i("gamepad drawer: a controller's Home button opens and closes the drawer");
        } catch (Throwable t) {
            L.w("gamepad drawer: not listening (" + t + ")");
        }
    }

    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (getSentFromUid() != Process.SYSTEM_UID) {
                    L.w("gamepad drawer: refused a request from " + getSentFromPackage());
                    return;
                }
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
