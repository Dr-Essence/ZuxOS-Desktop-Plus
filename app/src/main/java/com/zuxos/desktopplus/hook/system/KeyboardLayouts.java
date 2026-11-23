package com.zuxos.desktopplus.hook.system;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.LocaleList;
import android.view.InputDevice;

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
 * reaches; Android's way to set one is the input manager's override for the device
 * ({@code setKeyboardLayoutOverrideForInputDevice}), which needs a permission only the system
 * has, stores the choice for the keyboard's model and reloads the layouts at once. The layout is
 * one of Android's own ({@code getKeyboardLayouts}), picked by {@link KeyboardLayoutPick}. A
 * layout the user chose for that keyboard in Android's settings still comes first, as Android
 * orders them. Run on the bridge's thread, as the system, in try/catch.
 */
final class KeyboardLayouts {

    private KeyboardLayouts() {
    }

    /** Every physical keyboard to the layout of this language (a BCP 47 tag). */
    static void follow(Context ctx, String tag) throws Exception {
        // In the system the service is the input manager itself: its methods run as the system.
        Object input = Class.forName(SystemNames.SERVICE_MANAGER)
                .getMethod(SystemNames.GET_SERVICE, String.class)
                .invoke(null, SystemNames.INPUT_SERVICE);
        Method layoutsOf = method(input, SystemNames.GET_KEYBOARD_LAYOUTS, 0);
        Method set = method(input, SystemNames.SET_KEYBOARD_LAYOUT_OVERRIDE_FOR_INPUT_DEVICE, 2);
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
        if (!(descriptor instanceof String)) {
            return;
        }
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
            long identity = Binder.clearCallingIdentity();
            try {
                set.invoke(input, identifier, descriptor);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            L.i("system bridge: physical keyboard " + device.getName() + " - " + label + " for "
                    + tag);
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
