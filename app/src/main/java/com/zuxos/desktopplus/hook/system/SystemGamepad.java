package com.zuxos.desktopplus.hook.system;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.WindowInsets;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Threads;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: a game controller's Home button opens and closes the app drawer, or, held,
 * goes to the home screen; its L1 and R1 move along the tablet's taskbar.
 *
 * <p>Android hands the Home button ({@code KEYCODE_BUTTON_MODE}) to the app in front and, when that
 * app does nothing with it, presses Home in its place ({@code Generic.kcm}: fallback HOME). Here
 * it is taken before any app sees it ({@code PhoneWindowManager.interceptKeyBeforeDispatching}
 * answering -1: never dispatched, so no Home either). Let go within {@link #HOLD_MS}, ZUX Home is
 * told to toggle its drawer ({@link #ACTION_DRAWER}), as a tap on the start button does; held,
 * to go home ({@link #ACTION_HOME}), as a tap on its bar's Home key does - which also puts the
 * taskbar's drawer away. Inside the drawer the controller moves as Android moves any focus: the
 * stick and D-pad as arrow keys, A as select, B as Back ({@code Generic.kcm}).
 *
 * <p>L1 and R1 are taken the same way, and ZUX Home told ({@link #ACTION_BAR}), only while the
 * tablet's screen has the focus, with no lock screen, and the app in front does not hide the
 * navigation bar ({@code DisplayPolicy.topAppHidesSystemBar}): a full-screen game or video keeps
 * them. Each release goes the way its press went.
 *
 * <p>The hook runs on the input dispatcher's thread: a key test there and nothing else; the
 * messages, the hold's timer and the settings go to a thread of our own. The settings are read
 * there after a controller key, once a second at most, and the hook goes by the last read: with
 * a switch off, its button is Android's again.
 */
public final class SystemGamepad {

    public static final String ACTION_DRAWER = "com.zuxos.desktopplus.action.GAMEPAD_DRAWER";
    public static final String ACTION_HOME = "com.zuxos.desktopplus.action.GAMEPAD_HOME";
    public static final String ACTION_BAR = "com.zuxos.desktopplus.action.GAMEPAD_BAR";
    public static final String EXTRA_DISPLAY = "display";
    /** -1 for L1, 1 for R1. */
    public static final String EXTRA_STEP = "step";
    private static final String LAUNCHER = "com.zui.launcher";
    /** How long Home is held for the home screen rather than the drawer. */
    private static final long HOLD_MS = 500L;
    private static final int L1 = 1;
    private static final int R1 = 2;

    private static boolean sInstalled;
    private static Handler sHandler;
    /** {@code InputEvent.getDisplayId}, looked up once; null if this build has none. */
    private static Method sDisplayId;
    private static boolean sSaidKey;
    private static boolean sSaidHome;
    private static boolean sSaidHeld;
    private static boolean sSaidBar;
    private static int sErrors;

    /** The switches as last read. */
    private static volatile boolean sHomeOn = true;
    private static volatile boolean sBarOn = true;
    /** When the settings were last asked for. Input thread. */
    private static long sReadAt;
    /** Home taken at its press, and L1 and R1 ({@link #L1}, {@link #R1}). Input thread. */
    private static boolean sHomeTaken;
    private static int sShoulders;

    /** The system's state read for L1 and R1, found on their first press. */
    private static Field sFocusedDisplay;
    private static Field sDisplayPolicy;
    private static Method sKeyguard;
    private static Method sHidesBar;

    /** Home's hold: its timer, and whether it went off. Our thread. */
    private static Runnable sHold;
    private static boolean sHeld;

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
                        + " Home, L1 and R1 stay Android's");
                return;
            }
            try {
                sDisplayId = InputEvent.class.getMethod("getDisplayId");
            } catch (Throwable t) {
                // Every press goes to the tablet's screen.
            }
            HandlerThread thread = Threads.guarded(new HandlerThread("zux-gamepad"));
            thread.start();
            sHandler = new Handler(thread.getLooper());
            L.i("system gamepad: a controller's Home opens the drawer, L1 and R1 move along the"
                    + " taskbar x" + hooked);
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
                if (event == null || !fromController(event) || sHandler == null) {
                    return;
                }
                if (!sSaidKey) {
                    sSaidKey = true;
                    L.i("system gamepad: first controller key seen - "
                            + KeyEvent.keyCodeToString(event.getKeyCode()));
                }
                if (event.getEventTime() - sReadAt > 1000L) {
                    sReadAt = event.getEventTime();
                    sHandler.post(SystemGamepad::readSettings);
                }
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
                    home(param, event);
                } else if (code == KeyEvent.KEYCODE_BUTTON_L1
                        || code == KeyEvent.KEYCODE_BUTTON_R1) {
                    shoulder(param, event, code == KeyEvent.KEYCODE_BUTTON_L1 ? L1 : R1);
                }
            } catch (Throwable t) {
                if (sErrors++ < 3) {
                    L.w("system gamepad: a key left to Android (" + t + ")");
                }
            }
        }
    };

    /** Home: its press starts the hold's timer, its release opens the drawer if it never went. */
    private static void home(XC_MethodHook.MethodHookParam param, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() == 0) {
            sHomeTaken = sHomeOn;
            if (sHomeTaken) {
                int display = displayOf(event);
                sHandler.post(() -> pressed(display));
            }
        }
        if (!sHomeTaken) {
            // Android's own: the app's, then its fallback Home.
            return;
        }
        // Never dispatched: no app sees it and Android presses no Home for it.
        param.setResult(-1L);
        if (!down) {
            sHomeTaken = false;
            boolean cancelled = event.isCanceled();
            int display = displayOf(event);
            sHandler.post(() -> released(display, cancelled));
        }
    }

    /** L1 or R1: taken, and ZUX Home told, where nothing full screen has the tablet. */
    private static void shoulder(XC_MethodHook.MethodHookParam param, KeyEvent event, int which) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() == 0) {
            if (sBarOn && barFree(param.thisObject)) {
                sShoulders |= which;
                int step = which == L1 ? -1 : 1;
                sHandler.post(() -> bar(step));
            } else {
                sShoulders &= ~which;
            }
        }
        if ((sShoulders & which) == 0) {
            return;
        }
        param.setResult(-1L);
        if (!down) {
            sShoulders &= ~which;
        }
    }

    /**
     * Whether L1 and R1 are the bar's now: the tablet's screen has the focus, no lock screen is
     * up, and the app in front shows the navigation bar. Not when any of it cannot be read.
     */
    private static boolean barFree(Object policy) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return false;
            }
            if (sHidesBar == null) {
                Class<?> pwm = policy.getClass();
                sFocusedDisplay = field(pwm, "mTopFocusedDisplayId");
                sDisplayPolicy = field(pwm, "mDefaultDisplayPolicy");
                sKeyguard = method(pwm, "isKeyguardShowingAndNotOccluded");
                Object display = sDisplayPolicy == null ? null : sDisplayPolicy.get(policy);
                sHidesBar = display == null ? null
                        : method(display.getClass(), "topAppHidesSystemBar", int.class);
                if (sFocusedDisplay == null || sKeyguard == null || sHidesBar == null) {
                    throw new NoSuchMethodException("the policy's state");
                }
            }
            Object focused = sFocusedDisplay.get(policy);
            if (focused instanceof Integer && (Integer) focused > 0) {
                return false;
            }
            if (Boolean.TRUE.equals(sKeyguard.invoke(policy))) {
                return false;
            }
            Object display = sDisplayPolicy.get(policy);
            return display != null && !Boolean.TRUE.equals(
                    sHidesBar.invoke(display, WindowInsets.Type.navigationBars()));
        } catch (Throwable t) {
            if (sErrors++ < 3) {
                L.w("system gamepad: L1 and R1 left to the app (" + t + ")");
            }
            return false;
        }
    }

    private static Field field(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // On to the superclass.
            }
        }
        return null;
    }

    private static Method method(Class<?> cls, String name, Class<?>... types) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // On to the superclass.
            }
        }
        return null;
    }

    /**
     * The screen the key is for; the tablet's when Android does not say. Android's
     * {@code InputEvent.getDisplayId} is not in the public SDK the module is built with.
     */
    private static int displayOf(KeyEvent event) {
        try {
            Object display = sDisplayId == null ? null : sDisplayId.invoke(event);
            return display instanceof Integer ? Math.max((Integer) display, 0) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean fromController(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    // --- our own thread ---------------------------------------------------------------------

    /** The switches, read away from the input thread. */
    private static void readSettings() {
        try {
            boolean on = Cfg.enabled();
            sHomeOn = on && Cfg.gamepadDrawer();
            sBarOn = on && Cfg.gamepadBar() && Cfg.gamepadShoulders();
        } catch (Throwable t) {
            // The last values stand.
        }
    }

    private static void pressed(int display) {
        if (sHold != null) {
            sHandler.removeCallbacks(sHold);
        }
        sHeld = false;
        sHold = () -> {
            sHeld = true;
            send(new Intent(ACTION_HOME).putExtra(EXTRA_DISPLAY, display));
            if (!sSaidHeld) {
                sSaidHeld = true;
                L.i("system gamepad: Home held on a controller - ZUX Home told to go home on"
                        + " display " + display);
            }
        };
        sHandler.postDelayed(sHold, HOLD_MS);
    }

    private static void released(int display, boolean cancelled) {
        if (sHold != null) {
            sHandler.removeCallbacks(sHold);
            sHold = null;
        }
        if (!sHeld && !cancelled) {
            tell(display);
        }
        sHeld = false;
    }

    /**
     * The user in front ({@code UserHandle.CURRENT}, not in the public SDK the module is built
     * with); this process's own if it cannot be read.
     */
    private static UserHandle currentUser() {
        try {
            Object current = UserHandle.class.getField("CURRENT").get(null);
            if (current instanceof UserHandle) {
                return (UserHandle) current;
            }
        } catch (Throwable t) {
            // This process's own below.
        }
        return Process.myUserHandle();
    }

    /** ZUX Home told to open or close its drawer. */
    private static void tell(int display) {
        send(new Intent(ACTION_DRAWER).putExtra(EXTRA_DISPLAY, display));
        if (!sSaidHome) {
            sSaidHome = true;
            L.i("system gamepad: Home pressed on a controller - ZUX Home told to toggle its"
                    + " drawer on display " + display);
        }
    }

    /** ZUX Home told to take the controller onto its bar, or one icon along it. */
    private static void bar(int step) {
        send(new Intent(ACTION_BAR).putExtra(EXTRA_STEP, step));
        if (!sSaidBar) {
            sSaidBar = true;
            L.i("system gamepad: " + (step < 0 ? "L1" : "R1") + " on a controller - ZUX Home"
                    + " told to move along its taskbar");
        }
    }

    private static void send(Intent intent) {
        try {
            Context ctx = SystemBridge.systemContext();
            if (ctx == null) {
                return;
            }
            ctx.sendBroadcastAsUser(intent.setPackage(LAUNCHER)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), currentUser());
        } catch (Throwable t) {
            L.w("system gamepad: ZUX Home not told (" + t + ")");
        }
    }
}
