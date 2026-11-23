package com.zuxos.desktopplus.hook.system;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.LocaleList;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.logic.KeyboardLayoutPick;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Inside the system, for the system bridge: each physical keyboard given the layout of a
 * language, as ZUX Home asks when HeliBoard's language changes. A physical keyboard types with
 * Android's own layout for it, which a keyboard app switching its languages by itself never
 * reaches. Android looks a keyboard's layout up first in the user's choice for that keyboard and
 * the language the system has for the current keyboard app - what its Physical keyboard settings
 * write ({@code setKeyboardLayoutForInputDevice}) - then in a device override, then its own
 * guess; so the layout is written where those settings write it, and it wins as theirs does.
 * HeliBoard never changes the language the system has for it, so each switch rewrites the same
 * choice. The layout is one of Android's own ({@code getKeyboardLayouts}), picked by
 * {@link KeyboardLayoutPick}. What Android then says it uses, and what the keyboard's A key types
 * a moment later, are written down. Run on the bridge's thread, as the system, in try/catch.
 */
final class KeyboardLayouts {

    /** Android's {@code KeyboardLayoutSelectionResult} criteria, for the log. */
    private static final String[] CRITERIA = {"unspecified", "the user's choice", "the device",
            "the keyboard app's language", "the default"};
    /** How long after a choice the keyboard's keys are read: the layouts reload on their own. */
    private static final long READ_BACK_MS = 500L;

    private KeyboardLayouts() {
    }

    /** Every physical keyboard to the layout of this language (a BCP 47 tag). */
    static void follow(Context ctx, Handler handler, String tag) throws Exception {
        // In the system the service is the input manager itself: its methods run as the system.
        Object input = Class.forName(SystemNames.SERVICE_MANAGER)
                .getMethod(SystemNames.GET_SERVICE, String.class)
                .invoke(null, SystemNames.INPUT_SERVICE);
        Method layoutsOf = method(input, SystemNames.GET_KEYBOARD_LAYOUTS, 0);
        Method set = method(input, SystemNames.SET_KEYBOARD_LAYOUT_FOR_INPUT_DEVICE, 5);
        Method used = method(input, SystemNames.GET_KEYBOARD_LAYOUT_FOR_INPUT_DEVICE, 4);
        if (layoutsOf == null || set == null) {
            if (L.once("KeyboardLayouts.calls")) {
                L.w("system bridge: this firmware's input manager has no way to set a physical "
                        + "keyboard's layout - left as it is");
            }
            return;
        }
        Object[] layouts = (Object[]) layoutsOf.invoke(input);
        List<String[]> locales = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (Object layout : layouts) {
            Object list = Reflect.call(layout, SystemNames.GET_LOCALES);
            String tags = list instanceof LocaleList ? ((LocaleList) list).toLanguageTags() : "";
            locales.add(tags.isEmpty() ? new String[0] : tags.split(","));
            Object type = Reflect.call(layout, SystemNames.GET_LAYOUT_TYPE);
            types.add(type instanceof String ? (String) type : null);
        }
        int at = KeyboardLayoutPick.pick(locales, types, tag);
        if (at < 0) {
            if (L.once("KeyboardLayouts.none." + tag)) {
                L.i("system bridge: no physical keyboard layout for " + tag + " - left as it is");
            }
            return;
        }
        Object descriptor = Reflect.call(layouts[at], SystemNames.GET_DESCRIPTOR);
        Object label = Reflect.call(layouts[at], SystemNames.GET_LABEL);
        // The keyboard app and its language as the system has them: the key Android's own
        // settings write the choice under.
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        InputMethodInfo ime = imm != null && Build.VERSION.SDK_INT >= 34
                ? imm.getCurrentInputMethodInfo() : null;
        if (!(descriptor instanceof String) || ime == null) {
            if (L.once("KeyboardLayouts.ime")) {
                L.w("system bridge: no current keyboard app known to the system - physical "
                        + "keyboards left as they are");
            }
            return;
        }
        InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
        int user = currentUser();
        InputManager manager = ctx.getSystemService(InputManager.class);
        for (int id : manager.getInputDeviceIds()) {
            InputDevice device = manager.getInputDevice(id);
            if (device == null || !device.isExternal() || device.isVirtual()
                    || !device.isEnabled()
                    || device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                continue;
            }
            // InputDeviceIdentifier is hidden from the SDK: by name.
            Object identifier = Reflect.call(device, SystemNames.GET_IDENTIFIER);
            if (identifier == null) {
                continue;
            }
            Object now;
            long identity = Binder.clearCallingIdentity();
            try {
                set.invoke(input, identifier, user, ime, subtype, descriptor);
                now = used != null ? used.invoke(input, identifier, user, ime, subtype) : null;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            String name = device.getName();
            L.i("system bridge: physical keyboard " + name + " - " + label + " for " + tag
                    + "; Android now uses " + describe(now));
            handler.postDelayed(() -> typesA(manager, id, name), READ_BACK_MS);
        }
    }

    /** What Android says a keyboard uses: the layout's own name, and by which of its rules. */
    private static String describe(Object result) {
        if (result == null) {
            return "?";
        }
        Object layout = Reflect.call(result, SystemNames.GET_LAYOUT_DESCRIPTOR);
        Object criteria = Reflect.call(result, SystemNames.GET_SELECTION_CRITERIA);
        String name = layout instanceof String
                ? ((String) layout).substring(((String) layout).lastIndexOf('/') + 1) : "nothing";
        String by = criteria instanceof Integer && (Integer) criteria >= 0
                && (Integer) criteria < CRITERIA.length
                ? CRITERIA[(Integer) criteria] : String.valueOf(criteria);
        return name + ", by " + by;
    }

    /** The letter the keyboard's A key types now: the layout Android has really loaded. */
    private static void typesA(InputManager manager, int id, String name) {
        try {
            InputDevice device = manager.getInputDevice(id);
            if (device != null) {
                int letter = device.getKeyCharacterMap().get(KeyEvent.KEYCODE_A, 0);
                L.i("system bridge: physical keyboard " + name + " - its A key types '"
                        + (letter != 0 ? new String(Character.toChars(letter)) : "") + "'");
            }
        } catch (Throwable t) {
            L.d("system bridge: the keyboard's keys not read (" + t + ")");
        }
    }

    /** The user in front, whose choice it is; the system's own (0) if it cannot be read. */
    private static int currentUser() {
        try {
            Object user = ActivityManager.class.getMethod(SystemNames.GET_CURRENT_USER)
                    .invoke(null);
            return user instanceof Integer ? (Integer) user : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Method method(Object target, String name, int params) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) {
                return m;
            }
        }
        return null;
    }
}
