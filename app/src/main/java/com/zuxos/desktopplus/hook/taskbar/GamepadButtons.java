package com.zuxos.desktopplus.hook.taskbar;

import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.hook.system.SystemGamepad;

/**
 * A game controller's Home, L1 and R1, taken in the system ({@link SystemGamepad}).
 *
 * <p>A press of Home opens and closes ZUI's drawer, as a tap on our start button does, on the
 * screen the button was pressed for, and on the tablet's when that one has no button showing.
 * Held, it presses the bar's own Home key there, so ZUI goes home its own way and puts the
 * taskbar's drawer away with it. L1 and R1 take the controller onto the tablet's bar, or one icon
 * along it ({@link TaskbarKeys#shoulder}). Only the system may ask ({@link #SENDER}).
 */
final class GamepadButtons {

    /** What a sender must hold: input injection, the system's and the shell's alone. */
    private static final String SENDER = "android.permission.INJECT_EVENTS";

    private static boolean sListening;
    private static int sSaid;

    private GamepadButtons() {
    }

    /** Listens once per process, from the first start button made. Main thread. */
    static void listen(Context ctx) {
        if (sListening || !(Cfg.gamepadDrawer() || Cfg.gamepadShoulders()) || ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        if (app == null) {
            return;
        }
        sListening = true;
        try {
            IntentFilter filter = new IntentFilter(SystemGamepad.ACTION_DRAWER);
            filter.addAction(SystemGamepad.ACTION_HOME);
            filter.addAction(SystemGamepad.ACTION_BAR);
            // Only the system may ask: it holds every permission, and no app holds this one. The
            // sender's own name is no test - Android gives it only to a sender that shares it,
            // which the system's broadcasts do not: every press was refused (owner, 10:36:45).
            app.registerReceiver(new Receiver(), filter, SENDER, null,
                    Context.RECEIVER_EXPORTED);
            L.i("gamepad buttons: a controller's Home opens the drawer or goes home, L1 and R1"
                    + " move along the taskbar");
        } catch (Throwable t) {
            L.w("gamepad buttons: not listening (" + t + ")");
        }
    }

    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                int display = intent.getIntExtra(SystemGamepad.EXTRA_DISPLAY, 0);
                if (SystemGamepad.ACTION_BAR.equals(action)) {
                    if (Cfg.gamepadShoulders()) {
                        TaskbarKeys.shoulder(intent.getIntExtra(SystemGamepad.EXTRA_STEP, 1));
                    }
                    return;
                }
                if (!Cfg.gamepadDrawer()) {
                    return;
                }
                if (SystemGamepad.ACTION_HOME.equals(action)) {
                    home(context, display);
                    return;
                }
                boolean pressed = TaskbarStart.toggleDrawer(display)
                        || (display != 0 && TaskbarStart.toggleDrawer(0));
                if (sSaid < 3) {
                    sSaid++;
                    L.i("gamepad buttons: Home on a controller for display " + display + " - "
                            + (pressed ? "the start button pressed"
                            : "no start button there to press"));
                }
            } catch (Throwable t) {
                L.w("gamepad buttons: the button left as it is (" + t + ")");
            }
        }
    }

    /** Home held: the bar's own Home key, or Android's home where there is no bar. */
    private static void home(Context context, int display) {
        if (TaskbarStart.pressHome(display)) {
            return;
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display);
        context.startActivity(home, options.toBundle());
        L.i("gamepad buttons: Home held - no bar's Home key on display " + display
                + ", Android's home started");
    }
}
