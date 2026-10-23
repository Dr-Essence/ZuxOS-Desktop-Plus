package com.zuxos.desktopplus.hook.keyboard;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.logic.KeyGrid;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A game controller typing on HeliBoard, the owner's keyboard.
 *
 * <p>While a keyboard is up, Android hands it every key first. HeliBoard takes a hardware
 * keyboard's keys there ({@code LatinIME.onKeyDown}) and has nothing for a controller's: its
 * D-pad went on to the app, and A, X and Y did nothing. While HeliBoard's main keyboard is shown,
 * a controller's keys are taken here, before HeliBoard's own handling:
 * <ul>
 *   <li>the D-pad and the left stick move a ring over its keys, and up to its suggestion
 *       strip;</li>
 *   <li>A presses the key under the ring as a finger does: a touch fed into HeliBoard's own
 *       {@code MainKeyboardView.processMotionEvent}, under a pointer id no finger uses, so its
 *       preview, sounds, shift, long-press popup keys and repeats are all its own. Held with a
 *       popup open, the D-pad picks among the popup's keys, and the release types the one
 *       picked. On the strip, a tap clicks and a hold long-clicks, as a finger's;</li>
 *   <li>X, Y and Start press its delete, space and action keys the same way, where they sit on
 *       the layout, and L2 its shift key - held, it chords, as a held finger does;</li>
 *   <li>R2 locks and unlocks capitals, as its caps lock key does;</li>
 *   <li>L1 and R1 move the text cursor with its own arrow codes, as its toolbar does; the
 *       system leaves them to the keyboard while it is up ({@code SystemGamepad});</li>
 *   <li>B is left to Android, whose Back closes the keyboard.</li>
 * </ul>
 * The ring is HeliBoard's accent, in its window's overlay, and glides from key to key; a touch
 * takes it away. Its emoji and clipboard views, a hardware keyboard and touch are left as they
 * are.
 *
 * <p>HeliBoard ships shrunk by R8 but not renamed ({@code -dontobfuscate}), so every name here is
 * its own; each read falls back to its field, where a small method may have been inlined away.
 * Whatever is not found leaves HeliBoard as it is, said once.
 */
public final class HeliPad {

    /** HeliBoard's codes: its delete, space and action keys, and its cursor arrows. */
    private static final int CODE_DELETE = -7;
    private static final int CODE_SPACE = ' ';
    private static final int CODE_ENTER = '\n';
    private static final int CODE_LEFT = -21;
    private static final int CODE_RIGHT = -22;
    private static final int CODE_SHIFT = -11;
    private static final int CODE_CAPS_LOCK = -13;
    private static final int NO_COORDINATE = -1;
    /** HeliBoard's {@code Key.BACKGROUND_TYPE_ACTION}: its action key, Enter's place. */
    private static final int BACKGROUND_ACTION = 3;
    /** Pointer ids no finger uses, one per button. */
    private static final int ID_A = 9;
    private static final int ID_X = 8;
    private static final int ID_Y = 7;
    private static final int ID_START = 6;
    private static final int ID_SHIFT = 5;
    private static final int ID_CAPS = 4;
    /** The ring's stroke, and how far it stands out from the key, in dp. */
    private static final float RING_DP = 2f;
    private static final int FALLBACK_ACCENT = 0xFF4D8EFF;

    /** One place the ring can be: a key (in the main keyboard's coordinates) or a strip item. */
    private static final class Target {
        final Object key;
        final View item;
        /** Where it is in the keyboard window's root. */
        final Rect rect;
        final int code;
        /** Its centre in the main keyboard's own coordinates, for a touch. */
        final float x;
        final float y;

        Target(Object key, View item, Rect rect, int code, float x, float y) {
            this.key = key;
            this.item = item;
            this.rect = rect;
            this.code = code;
            this.x = x;
            this.y = y;
        }
    }

    /** A button holding a key down: its pointer, where, since when, and in which view. */
    private static final class Press {
        final int id;
        final View keys;
        final long down;
        float x;
        float y;

        Press(int id, View keys, long down, float x, float y) {
            this.id = id;
            this.keys = keys;
            this.down = down;
            this.x = x;
            this.y = y;
        }
    }

    /** Main thread, all of it: HeliBoard's keys and views live there. */
    private static boolean sOn;
    private static Object sKey;
    private static WeakReference<View> sItem;
    private static Rect sAt;
    private static int sColumnX = -1;
    private static int sLastCode;
    private static GradientDrawable sRing;
    private static WeakReference<View> sRingHost;
    private static ValueAnimator sGlide;
    private static final SparseArray<Press> PRESSES = new SparseArray<>();
    private static WeakReference<View> sStripHeld;
    private static Runnable sStripLong;
    private static boolean sStripLonged;
    private static Method sProcess;
    private static boolean sNoProcess;
    private static int sErrors;

    private HeliPad() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> ime = Reflect.findClass(HeliNames.LATIN_IME, loader);
            Class<?> keys = Reflect.findClass(HeliNames.MAIN_KEYBOARD_VIEW, loader);
            if (ime == null || keys == null) {
                L.w("heli pad: HeliBoard's keyboard not found - the controller types nothing");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(ime, HeliNames.ON_KEY_DOWN, KEY).size()
                    + XposedBridge.hookAllMethods(ime, HeliNames.ON_KEY_UP, KEY).size();
            XC_MethodHook gone = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    hide();
                }
            };
            hooked += XposedBridge.hookAllMethods(ime, HeliNames.ON_FINISH_INPUT_VIEW, gone).size()
                    + XposedBridge.hookAllMethods(ime, HeliNames.ON_WINDOW_HIDDEN, gone).size();
            hooked += XposedBridge.hookAllMethods(keys, HeliNames.SET_KEYBOARD,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sOn && param.thisObject instanceof View) {
                                View view = (View) param.thisObject;
                                view.post(() -> follow(view));
                            }
                        }
                    }).size();
            Class<?> input = Reflect.findClass(HeliNames.INPUT_VIEW, loader);
            if (input != null) {
                hooked += XposedBridge.hookAllMethods(input, HeliNames.ON_INTERCEPT_TOUCH_EVENT,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (sOn && param.args.length == 1
                                        && param.args[0] instanceof MotionEvent
                                        && ((MotionEvent) param.args[0]).getActionMasked()
                                        == MotionEvent.ACTION_DOWN) {
                                    // A finger: the keyboard is touch's again.
                                    ringOff();
                                }
                            }
                        }).size();
            }
            L.i("heli pad: a game controller types on HeliBoard x" + hooked);
        } catch (Throwable t) {
            L.w("heli pad: not installed (" + t + ")");
        }
    }

    private static final XC_MethodHook KEY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args.length == 2 && param.args[1] instanceof KeyEvent
                    && key(param.thisObject, (KeyEvent) param.args[1])) {
                param.setResult(Boolean.TRUE);
            }
        }
    };

    // --- the buttons -----------------------------------------------------------------------

    /** True when the key was the controller's on HeliBoard's keys, and goes no further. */
    private static boolean key(Object ime, KeyEvent event) {
        try {
            int code = event.getKeyCode();
            if (!fromController(event) || !ours(code)) {
                return false;
            }
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (!down && release(code)) {
                // The end of a press already given to HeliBoard, whatever is shown now.
                return true;
            }
            View keys = mainKeyboard(ime);
            Object root = Reflect.field(ime, HeliNames.M_INPUT_VIEW);
            if (!Cfg.gamepadKeyboard() || keys == null || !(root instanceof ViewGroup)
                    || !keys.isShown()) {
                // Its emoji or clipboard view, or no keyboard: HeliBoard's own.
                if (sOn) {
                    ringOff();
                }
                return false;
            }
            ViewGroup input = (ViewGroup) root;
            if (L.once("HeliPad.first")) {
                L.i("heli pad: first controller key on HeliBoard - "
                        + KeyEvent.keyCodeToString(code));
            }
            if (!down) {
                return true;
            }
            boolean first = event.getRepeatCount() == 0;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    move(code, keys, input, ime);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    if (first) {
                        pressRing(code, keys, input, ime);
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_X:
                    return pressCode(first, code, ID_X, keys, input, ime, CODE_DELETE);
                case KeyEvent.KEYCODE_BUTTON_Y:
                    return pressCode(first, code, ID_Y, keys, input, ime, CODE_SPACE);
                case KeyEvent.KEYCODE_BUTTON_START:
                    return pressCode(first, code, ID_START, keys, input, ime, CODE_ENTER);
                case KeyEvent.KEYCODE_BUTTON_L1:
                    codeInput(ime, CODE_LEFT);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    codeInput(ime, CODE_RIGHT);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_L2:
                    return pressCode(first, code, ID_SHIFT, keys, input, ime, CODE_SHIFT);
                case KeyEvent.KEYCODE_BUTTON_R2:
                    if (first) {
                        capsLock(code, keys, input, ime);
                    }
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            if (sErrors++ < 3) {
                L.w("heli pad: a key left to HeliBoard (" + t + ")");
            }
            return false;
        }
    }

    private static boolean ours(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                return true;
            default:
                return false;
        }
    }

    private static boolean fromController(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** The D-pad: the ring onto the keyboard, or one step; in a popup held open, its keys. */
    private static void move(int code, View keys, ViewGroup input, Object ime) {
        if (PRESSES.get(KeyEvent.KEYCODE_BUTTON_A) != null
                || PRESSES.get(KeyEvent.KEYCODE_DPAD_CENTER) != null) {
            if (popupStep(code)) {
                return;
            }
        }
        List<Target> all = targets(keys, input, ime);
        Target at = sOn ? current(all) : null;
        if (at == null) {
            begin(all, keys, input);
            return;
        }
        Target next = next(all, at, code, true);
        if (next != null) {
            show(next, input, true);
        }
    }

    /** The ring shown where it starts. */
    private static void begin(List<Target> all, View keys, ViewGroup input) {
        Target at = all.isEmpty() ? null : start(all, keys, input);
        if (at == null) {
            said("no keys read off HeliBoard's keyboard");
            return;
        }
        sColumnX = at.rect.centerX();
        show(at, input, false);
    }

    /** A: the key under the ring pressed, or the strip's item; the first A shows the ring. */
    private static void pressRing(int button, View keys, ViewGroup input, Object ime) {
        List<Target> all = targets(keys, input, ime);
        Target at = sOn ? current(all) : null;
        if (at == null) {
            begin(all, keys, input);
            return;
        }
        if (at.item != null) {
            stripDown(at.item);
            return;
        }
        sLastCode = at.code;
        touch(button, ID_A, keys, at.x, at.y);
    }

    /**
     * X, Y or Start: HeliBoard's own key with that code, where it sits on the layout; for Start,
     * its action key, which sends Enter's code only for a plain Enter.
     */
    private static boolean pressCode(boolean first, int button, int id, View keys,
            ViewGroup input, Object ime, int code) {
        if (!first) {
            return true;
        }
        Target found = keyWith(code, keys, input, ime);
        if (found != null) {
            touch(button, id, keys, found.x, found.y);
        }
        // None on this layout: the button does nothing rather than reach the app.
        return true;
    }

    /** The key of the shown layout that sends {@code code}; for Enter, its action key. */
    private static Target keyWith(int code, View keys, ViewGroup input, Object ime) {
        Target found = null;
        for (Target t : targets(keys, input, ime)) {
            if (t.key == null) {
                continue;
            }
            if (code == CODE_ENTER && intOf(t.key, HeliNames.GET_BACKGROUND_TYPE,
                    HeliNames.M_BACKGROUND_TYPE) == BACKGROUND_ACTION) {
                // The action key, whatever it sends: Enter, a shifted Enter, Go, Search, Next.
                return t;
            }
            if (found == null && t.code == code) {
                found = t;
            }
        }
        return found;
    }

    /**
     * R2: caps lock on or off. A caps lock key of the layout is pressed where it sits; with none,
     * which is usual, the press goes to HeliBoard's own listener as {@code PointerTracker} sends
     * a key's - {@code onPressKey}, {@code onCodeInput}, {@code onReleaseKey} - and its
     * {@code KeyboardState} locks or unlocks the shift on the release.
     */
    private static void capsLock(int button, View keys, ViewGroup input, Object ime) {
        Target key = keyWith(CODE_CAPS_LOCK, keys, input, ime);
        if (key != null) {
            touch(button, ID_CAPS, keys, key.x, key.y);
            return;
        }
        Object listener = Reflect.field(ime, HeliNames.M_KEYBOARD_ACTION_LISTENER);
        Method press = null;
        Method input4 = null;
        Method release = null;
        for (Method m : listener == null ? new Method[0] : listener.getClass().getMethods()) {
            if (m.getName().equals(HeliNames.ON_PRESS_KEY) && m.getParameterCount() == 4) {
                press = m;
            } else if (m.getName().equals(HeliNames.ON_CODE_INPUT) && m.getParameterCount() == 4) {
                input4 = m;
            } else if (m.getName().equals(HeliNames.ON_RELEASE_KEY) && m.getParameterCount() == 2) {
                release = m;
            }
        }
        if (release == null) {
            said("HeliBoard's key release not found - R2 does nothing");
            return;
        }
        try {
            if (press != null) {
                press.invoke(listener, CODE_CAPS_LOCK, 0, 1, keyPress(press));
            }
            if (input4 != null) {
                input4.invoke(listener, CODE_CAPS_LOCK, NO_COORDINATE, NO_COORDINATE, false);
            }
            release.invoke(listener, CODE_CAPS_LOCK, false);
        } catch (Throwable t) {
            L.d("heli pad: caps lock not toggled (" + t + ")");
        }
    }

    /** HeliBoard's {@code HapticEvent.KEY_PRESS}, the last argument of its key press. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object keyPress(Method press) {
        Class<?> type = press.getParameterTypes()[3];
        if (!type.isEnum()) {
            return null;
        }
        try {
            return Enum.valueOf((Class<? extends Enum>) type, HeliNames.KEY_PRESS);
        } catch (IllegalArgumentException renamed) {
            Object[] all = type.getEnumConstants();
            return all.length > 0 ? all[0] : null;
        }
    }

    /** A finger down at {@code x, y} of the main keyboard, held for {@code button}. */
    private static void touch(int button, int id, View keys, float x, float y) {
        if (PRESSES.get(button) != null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        Press press = new Press(id, keys, now, x, y);
        PRESSES.put(button, press);
        send(press, MotionEvent.ACTION_DOWN);
    }

    /** The button let go: its finger lifted where it is now. False with no press of it. */
    private static boolean release(int button) {
        if (button == KeyEvent.KEYCODE_BUTTON_A || button == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (stripUp()) {
                return true;
            }
        }
        Press press = PRESSES.get(button);
        if (press == null) {
            return false;
        }
        PRESSES.remove(button);
        send(press, press.keys.isAttachedToWindow() ? MotionEvent.ACTION_UP
                : MotionEvent.ACTION_CANCEL);
        if (sRing != null) {
            // Back from a popup, where the ring stood aside.
            sRing.setAlpha(255);
        }
        return true;
    }

    private static void send(Press press, int action) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = press.id;
        props.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords at = new MotionEvent.PointerCoords();
        at.x = press.x;
        at.y = press.y;
        at.pressure = 1f;
        at.size = 1f;
        MotionEvent event = MotionEvent.obtain(press.down, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{props}, new MotionEvent.PointerCoords[]{at},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (sProcess == null && !sNoProcess) {
                try {
                    sProcess = press.keys.getClass().getMethod(HeliNames.PROCESS_MOTION_EVENT,
                            MotionEvent.class);
                } catch (NoSuchMethodException inlined) {
                    sNoProcess = true;
                    said("processMotionEvent not in this HeliBoard - its onTouchEvent takes the"
                            + " controller's presses");
                }
            }
            if (sProcess != null) {
                sProcess.invoke(press.keys, event);
            } else {
                // Inlined away: the view's own touch entry, which leads to the same place.
                press.keys.onTouchEvent(event);
            }
        } catch (Throwable t) {
            // HeliBoard's own handling threw: the touch is not fed again elsewhere.
            L.d("heli pad: a press not taken by HeliBoard (" + t + ")");
        } finally {
            event.recycle();
        }
    }

    /**
     * While A holds a key whose popup keys are open: the D-pad moves the same finger to the next
     * popup key that way ({@code PointerTracker.mPopupKeysPanel}, laid at its {@code mOriginX},
     * {@code mOriginY} in the main keyboard). False with no popup open.
     */
    private static boolean popupStep(int code) {
        int button = PRESSES.get(KeyEvent.KEYCODE_BUTTON_A) != null
                ? KeyEvent.KEYCODE_BUTTON_A : KeyEvent.KEYCODE_DPAD_CENTER;
        Press press = PRESSES.get(button);
        if (press == null || code == KeyEvent.KEYCODE_DPAD_CENTER) {
            return false;
        }
        try {
            Class<?> trackers = Class.forName(HeliNames.POINTER_TRACKER, false,
                    press.keys.getClass().getClassLoader());
            Object tracker = null;
            for (Method m : trackers.getMethods()) {
                if (m.getName().equals(HeliNames.GET_POINTER_TRACKER)
                        && m.getParameterCount() == 1) {
                    tracker = m.invoke(null, press.id);
                }
            }
            Object panel = Reflect.field(tracker, HeliNames.M_POPUP_KEYS_PANEL);
            if (!(panel instanceof View)) {
                return false;
            }
            View popup = (View) panel;
            Object originX = Reflect.field(popup, HeliNames.M_ORIGIN_X);
            Object originY = Reflect.field(popup, HeliNames.M_ORIGIN_Y);
            if (!(originX instanceof Integer) || !(originY instanceof Integer)) {
                return false;
            }
            if (sRing != null) {
                // The popup marks its own picked key.
                sRing.setAlpha(0);
            }
            List<Target> keys = new ArrayList<>();
            for (Object key : keysOf(popup)) {
                int x = intOf(key, HeliNames.GET_X, HeliNames.M_X) + popup.getPaddingLeft()
                        + (Integer) originX;
                int y = intOf(key, HeliNames.GET_Y, HeliNames.M_Y) + popup.getPaddingTop()
                        + (Integer) originY;
                int w = intOf(key, HeliNames.GET_WIDTH, HeliNames.M_WIDTH);
                int h = intOf(key, HeliNames.GET_HEIGHT, HeliNames.M_HEIGHT);
                if (w > 0 && h > 0) {
                    keys.add(new Target(key, null, new Rect(x, y, x + w, y + h), 0,
                            x + w / 2f, y + h / 2f));
                }
            }
            Target at = nearest(keys, (int) press.x, (int) press.y);
            Target next = at == null ? null : next(keys, at, code, false);
            if (next != null) {
                press.x = next.x;
                press.y = next.y;
                send(press, MotionEvent.ACTION_MOVE);
            }
            return true;
        } catch (Throwable t) {
            L.d("heli pad: popup keys not reached (" + t + ")");
            return false;
        }
    }

    /** L1 or R1: HeliBoard's own cursor arrow, as its toolbar sends it. */
    private static void codeInput(Object ime, int code) {
        Object listener = Reflect.field(ime, HeliNames.M_KEYBOARD_ACTION_LISTENER);
        if (listener == null) {
            said("HeliBoard's key listener not found - L1 and R1 do nothing");
            return;
        }
        for (Method m : listener.getClass().getMethods()) {
            if (m.getName().equals(HeliNames.ON_CODE_INPUT) && m.getParameterCount() == 4) {
                try {
                    m.invoke(listener, code, NO_COORDINATE, NO_COORDINATE, false);
                } catch (Throwable t) {
                    L.d("heli pad: no cursor move (" + t + ")");
                }
                return;
            }
        }
    }

    // --- the strip -------------------------------------------------------------------------

    /** A on a strip item: a hold long-clicks it once held long enough, as a finger's. */
    private static void stripDown(View item) {
        stripCancel();
        sStripLonged = false;
        sStripHeld = new WeakReference<>(item);
        sStripLong = () -> {
            sStripLonged = true;
            item.performLongClick();
        };
        item.postDelayed(sStripLong, ViewConfiguration.getLongPressTimeout());
    }

    /** A let go on a strip item: a click unless the hold already long-clicked it. */
    private static boolean stripUp() {
        View item = sStripHeld != null ? sStripHeld.get() : null;
        if (item == null) {
            return false;
        }
        boolean longed = sStripLonged;
        stripCancel();
        if (!longed && item.isAttachedToWindow()) {
            item.performClick();
        }
        return true;
    }

    private static void stripCancel() {
        View item = sStripHeld != null ? sStripHeld.get() : null;
        if (item != null && sStripLong != null) {
            item.removeCallbacks(sStripLong);
        }
        sStripHeld = null;
        sStripLong = null;
    }

    // --- where the ring can go -------------------------------------------------------------

    /** HeliBoard's main keyboard view ({@code KeyboardSwitcher.getMainKeyboardView}). */
    private static View mainKeyboard(Object ime) {
        Object switcher = Reflect.field(ime, HeliNames.M_KEYBOARD_SWITCHER);
        Object view = Reflect.call(switcher, HeliNames.GET_MAIN_KEYBOARD_VIEW);
        if (view == null) {
            view = Reflect.field(switcher, HeliNames.M_KEYBOARD_VIEW);
        }
        return view instanceof View ? (View) view : null;
    }

    /** Every key of the shown keyboard and every item of the strip, in the window's root. */
    private static List<Target> targets(View keys, ViewGroup input, Object ime) {
        List<Target> all = keyTargets(keys, input);
        Object strip = Reflect.field(ime, HeliNames.M_SUGGESTION_STRIP_VIEW);
        if (strip instanceof ViewGroup && ((View) strip).isShown()) {
            addItems((ViewGroup) strip, input, all);
        }
        return all;
    }

    /**
     * The keys, spacers left out: where each is in the window's root, and its centre where a
     * finger on it lands ({@code KeyboardView}'s padding in, its vertical correction out, as
     * its {@code KeyDetector} takes them).
     */
    private static List<Target> keyTargets(View keys, ViewGroup input) {
        List<Target> all = new ArrayList<>();
        float correction = floatOf(Reflect.call(keys, HeliNames.GET_VERTICAL_CORRECTION));
        for (Object key : keysOf(keys)) {
            if (key.getClass().getName().endsWith(HeliNames.SPACER)) {
                continue;
            }
            int x = intOf(key, HeliNames.GET_X, HeliNames.M_X) + keys.getPaddingLeft();
            int y = intOf(key, HeliNames.GET_Y, HeliNames.M_Y) + keys.getPaddingTop();
            int w = intOf(key, HeliNames.GET_WIDTH, HeliNames.M_WIDTH);
            int h = intOf(key, HeliNames.GET_HEIGHT, HeliNames.M_HEIGHT);
            if (w <= 0 || h <= 0) {
                continue;
            }
            Rect rect = new Rect(x, y, x + w, y + h);
            input.offsetDescendantRectToMyCoords(keys, rect);
            all.add(new Target(key, null, rect, intOf(key, HeliNames.GET_CODE, HeliNames.M_CODE),
                    x + w / 2f, y + h / 2f - correction));
        }
        return all;
    }

    /** The strip's words and toolbar keys: shown, clickable, with nothing clickable inside. */
    private static void addItems(ViewGroup group, ViewGroup input, List<Target> all) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!child.isShown() || child.getWidth() <= 0) {
                continue;
            }
            int before = all.size();
            if (child instanceof ViewGroup) {
                addItems((ViewGroup) child, input, all);
            }
            Rect seen = new Rect();
            if (all.size() == before && child.isClickable() && child.isEnabled()
                    && child.getGlobalVisibleRect(seen)) {
                Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
                input.offsetDescendantRectToMyCoords(child, rect);
                all.add(new Target(null, child, rect, 0, 0f, 0f));
            }
        }
    }

    private static List<?> keysOf(View view) {
        Object keyboard = Reflect.call(view, HeliNames.GET_KEYBOARD);
        if (keyboard == null) {
            keyboard = Reflect.field(view, HeliNames.M_KEYBOARD);
        }
        Object keys = Reflect.call(keyboard, HeliNames.GET_SORTED_KEYS);
        if (keys == null) {
            keys = Reflect.field(keyboard, HeliNames.M_SORTED_KEYS);
        }
        return keys instanceof List ? (List<?>) keys : new ArrayList<>();
    }

    private static int intOf(Object o, String method, String field) {
        Object v = Reflect.call(o, method);
        if (v == null) {
            v = Reflect.field(o, field);
        }
        return v instanceof Integer ? (Integer) v : 0;
    }

    private static float floatOf(Object v) {
        return v instanceof Float ? (Float) v : 0f;
    }

    /** Where the ring is: its key or item, else what is now at its place. */
    private static Target current(List<Target> all) {
        View item = sItem != null ? sItem.get() : null;
        for (Target t : all) {
            if ((sKey != null && t.key == sKey) || (item != null && t.item == item)) {
                return t;
            }
        }
        return sAt == null ? null : nearest(all, sAt.centerX(), sAt.centerY());
    }

    /** Where the ring starts: the key last pressed, else the one nearest the middle. */
    private static Target start(List<Target> all, View keys, ViewGroup input) {
        for (Target t : all) {
            if (t.key != null && sLastCode != 0 && t.code == sLastCode) {
                return t;
            }
        }
        Rect middle = new Rect(0, 0, keys.getWidth(), keys.getHeight());
        input.offsetDescendantRectToMyCoords(keys, middle);
        List<Target> keyOnly = new ArrayList<>();
        for (Target t : all) {
            if (t.key != null) {
                keyOnly.add(t);
            }
        }
        return nearest(keyOnly, middle.centerX(), middle.centerY());
    }

    private static Target nearest(List<Target> all, int x, int y) {
        int[][] grid = grid(all);
        int i = KeyGrid.nearest(grid[0], grid[1], x, y);
        return i == KeyGrid.NONE ? null : all.get(i);
    }

    /**
     * The next place that way ({@link KeyGrid#next}), keeping the column the ring came down when
     * {@code column}; null at an edge.
     */
    private static Target next(List<Target> all, Target at, int code, boolean column) {
        int way = code == KeyEvent.KEYCODE_DPAD_LEFT ? KeyGrid.LEFT
                : code == KeyEvent.KEYCODE_DPAD_RIGHT ? KeyGrid.RIGHT
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? KeyGrid.DOWN : KeyGrid.UP;
        int[][] grid = grid(all);
        int i = KeyGrid.next(grid[0], grid[1], grid[2], all.indexOf(at), way,
                column ? sColumnX : -1);
        if (i == KeyGrid.NONE) {
            return null;
        }
        if (column && (way == KeyGrid.LEFT || way == KeyGrid.RIGHT)) {
            sColumnX = grid[0][i];
        }
        return all.get(i);
    }

    /** The places' centres across and down, and their heights. */
    private static int[][] grid(List<Target> all) {
        int[][] grid = new int[3][all.size()];
        for (int i = 0; i < all.size(); i++) {
            Rect r = all.get(i).rect;
            grid[0][i] = r.centerX();
            grid[1][i] = r.centerY();
            grid[2][i] = r.height();
        }
        return grid;
    }

    /** The layout changed under the ring - shift, symbols, another language: the ring stays. */
    private static void follow(View keys) {
        try {
            View host = sRingHost != null ? sRingHost.get() : null;
            View item = sItem != null ? sItem.get() : null;
            if (!sOn || !(host instanceof ViewGroup) || sAt == null || item != null) {
                // Off, or on the strip, which the layout does not move.
                return;
            }
            Target at = nearest(keyTargets(keys, (ViewGroup) host), sAt.centerX(),
                    sAt.centerY());
            if (at != null) {
                show(at, (ViewGroup) host, true);
            }
        } catch (Throwable t) {
            L.d("heli pad: the ring not kept in place (" + t + ")");
        }
    }

    // --- the ring --------------------------------------------------------------------------

    private static void show(Target target, ViewGroup input, boolean glide) {
        View host = sRingHost != null ? sRingHost.get() : null;
        if (sRing == null || host != input) {
            ringOff();
            float density = input.getResources().getDisplayMetrics().density;
            sRing = new GradientDrawable();
            sRing.setStroke(Math.round(RING_DP * density), accent(input));
            sRingHost = new WeakReference<>(input);
            input.getOverlay().add(sRing);
            glide = false;
        }
        sOn = true;
        sKey = target.key;
        sItem = target.item != null ? new WeakReference<>(target.item) : null;
        sAt = new Rect(target.rect);
        Rect to = new Rect(target.rect);
        int out = Math.round(RING_DP * input.getResources().getDisplayMetrics().density / 2f);
        to.inset(-out, -out);
        sRing.setAlpha(255);
        sRing.setCornerRadius(Math.min(to.width(), to.height()) * 0.2f);
        if (sGlide != null) {
            sGlide.cancel();
        }
        if (!glide) {
            sRing.setBounds(to);
            sRing.invalidateSelf();
            return;
        }
        Rect from = new Rect(sRing.getBounds());
        GradientDrawable ring = sRing;
        sGlide = ValueAnimator.ofFloat(0f, 1f);
        sGlide.setDuration(Motion.SHORT);
        sGlide.setInterpolator(Motion.EASE);
        sGlide.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            ring.setBounds(Math.round(from.left + (to.left - from.left) * f),
                    Math.round(from.top + (to.top - from.top) * f),
                    Math.round(from.right + (to.right - from.right) * f),
                    Math.round(from.bottom + (to.bottom - from.bottom) * f));
            ring.invalidateSelf();
        });
        sGlide.start();
    }

    /** The ring taken away; presses held go on until their buttons are let go. */
    private static void ringOff() {
        if (sGlide != null) {
            sGlide.cancel();
            sGlide = null;
        }
        View host = sRingHost != null ? sRingHost.get() : null;
        if (host != null && sRing != null) {
            host.getOverlay().remove(sRing);
        }
        sRing = null;
        sRingHost = null;
        sOn = false;
        sKey = null;
        sItem = null;
        sAt = null;
        sColumnX = -1;
    }

    /** The keyboard gone: the ring, the strip's hold and every held key with it. */
    private static void hide() {
        try {
            ringOff();
            stripCancel();
            for (int i = PRESSES.size() - 1; i >= 0; i--) {
                Press press = PRESSES.valueAt(i);
                PRESSES.removeAt(i);
                send(press, MotionEvent.ACTION_CANCEL);
            }
        } catch (Throwable t) {
            L.d("heli pad: not tidied (" + t + ")");
        }
    }

    /** HeliBoard's accent: its action key's colour ({@code ColorType.ACTION_KEY_BACKGROUND}). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int accent(View input) {
        try {
            ClassLoader loader = input.getClass().getClassLoader();
            Class<?> settings = Class.forName(HeliNames.SETTINGS, false, loader);
            Object values;
            try {
                values = settings.getMethod(HeliNames.GET_VALUES).invoke(null);
            } catch (NoSuchMethodException inlined) {
                java.lang.reflect.Field one = settings.getDeclaredField(HeliNames.S_INSTANCE);
                one.setAccessible(true);
                values = Reflect.field(one.get(null), HeliNames.M_SETTINGS_VALUES);
            }
            Object colors = Reflect.field(values, HeliNames.M_COLORS);
            Class<? extends Enum> type = (Class<? extends Enum>) Class.forName(
                    HeliNames.COLOR_TYPE, false, loader);
            Object accent = Enum.valueOf(type, HeliNames.ACTION_KEY_BACKGROUND);
            Object colour = colors.getClass().getMethod(HeliNames.GET, type).invoke(colors, accent);
            if (colour instanceof Integer) {
                return (Integer) colour;
            }
        } catch (Throwable t) {
            said("HeliBoard's accent not read, the ring in ours (" + t + ")");
        }
        return FALLBACK_ACCENT;
    }

    /** Each thing not found told once. */
    private static void said(String what) {
        if (L.once("HeliPad." + what)) {
            L.i("heli pad: " + what);
        }
    }
}
