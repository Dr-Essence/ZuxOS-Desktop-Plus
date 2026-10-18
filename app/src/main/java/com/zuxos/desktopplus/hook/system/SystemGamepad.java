package com.zuxos.desktopplus.hook.system;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Threads;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: a game controller's Home button opens and closes the app drawer.
 *
 * <p>Android hands the button ({@code KEYCODE_BUTTON_MODE}) to the app in front and, when that
 * app does nothing with it, presses Home in its place ({@code Generic.kcm}: fallback HOME). Here
 * it is taken before any app sees it ({@code PhoneWindowManager.interceptKeyBeforeDispatching}
 * answering -1: never dispatched, so no Home either), and on its first press ZUX Home is told to
 * toggle its drawer ({@link #ACTION_DRAWER}), which it does as a tap on the start button does.
 * Inside the drawer the controller moves as Android moves any focus: the stick and D-pad as arrow
 * keys, A as select, B as Back ({@code Generic.kcm}).
 *
 * <p>The hook runs on the input dispatcher's thread: a key test there and nothing else; the
 * message goes from a thread of our own.
 */
public final class SystemGamepad {

    public static final String ACTION_DRAWER = "com.zuxos.desktopplus.action.GAMEPAD_DRAWER";
    public static final String EXTRA_DISPLAY = "display";
    private static final String LAUNCHER = "com.zui.launcher";

    private static boolean sInstalled;
    private static Handler sHandler;
    private static boolean sSaidKey;
    private static boolean sSaidHome;
    private static int sErrors;

    private SystemGamepad() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> policy = Class.forName("com.android.server.policy.PhoneWindowManager",
                    false, loader);
            int hooked = 0;
            for (Method m : policy.getDeclaredMethods()) {
                if (m.getName().equals("interceptKeyBeforeDispatching")
                        && m.getReturnType() == long.class && takesKey(m)) {
                    XposedBridge.hookMethod(m, KEY);
                    hooked++;
                }
            }
            if (hooked == 0) {
                L.i("system gamepad: the key policy's dispatch not found - the controller's"
                        + " Home button stays Android's");
                return;
            }
            HandlerThread thread = Threads.guarded(new HandlerThread("zux-gamepad"));
            thread.start();
            sHandler = new Handler(thread.getLooper());
            L.i("system gamepad: a controller's Home button opens the drawer x" + hooked);
        } catch (Throwable t) {
            L.i("system gamepad: not available on this build (" + t + ")");
        }
    }

    private static boolean takesKey(Method m) {
        for (Class<?> type : m.getParameterTypes()) {
            if (type == KeyEvent.class) {
                return true;
            }
        }
        return false;
    }

    private static final XC_MethodHook KEY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                KeyEvent event = null;
                for (Object arg : param.args) {
                    if (arg instanceof KeyEvent) {
                        event = (KeyEvent) arg;
                    }
                }
                if (event == null || !fromController(event)) {
                    return;
                }
                if (!sSaidKey) {
                    sSaidKey = true;
                    L.i("system gamepad: first controller key seen - "
                            + KeyEvent.keyCodeToString(event.getKeyCode()));
                }
                if (event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_MODE || sHandler == null) {
                    return;
                }
                boolean first = event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0;
                int display = first ? displayOf(event) : 0;
                // Never dispatched: no app sees it and Android presses no Home for it.
                param.setResult(-1L);
                if (first) {
                    sHandler.post(() -> tell(display));
                }
            } catch (Throwable t) {
                if (sErrors++ < 3) {
                    L.w("system gamepad: a key left to Android (" + t + ")");
                }
            }
        }
    };

    /** The screen the key is for; the tablet's when Android does not say. */
    private static int displayOf(KeyEvent event) {
        try {
            return Math.max(event.getDisplayId(), 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean fromController(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** ZUX Home told to open or close its drawer. Our own thread. */
    private static void tell(int display) {
        try {
            Context ctx = SystemBridge.systemContext();
            if (ctx == null) {
                return;
            }
            Intent intent = new Intent(ACTION_DRAWER).setPackage(LAUNCHER)
                    .putExtra(EXTRA_DISPLAY, display)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcastAsUser(intent, UserHandle.ALL);
            if (!sSaidHome) {
                sSaidHome = true;
                L.i("system gamepad: Home pressed on a controller - ZUX Home told to toggle its"
                        + " drawer on display " + display);
            }
        } catch (Throwable t) {
            L.w("system gamepad: ZUX Home not told (" + t + ")");
        }
    }
}
