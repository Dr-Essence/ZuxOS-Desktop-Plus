package com.zuxos.desktopplus.hook.system;

import android.os.Binder;
import android.view.InputDevice;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: the monitor's touchscreen tied to the monitor, through Android's own
 * association of a touch device with a screen.
 *
 * <p>Android ties a touchscreen to a screen through viewports: each display gets one by the kind of
 * touch its device reports - internal, external, or a virtual one by unique id
 * ({@code DisplayManagerService.getViewportType}) - and the list goes to the input system in one
 * call ({@code InputManagerService.setDisplayViewportsInternal}). An external touchscreen is put on
 * the external viewport only by default, and without one at the moment it is configured it falls
 * back to the internal screen. After a replug the monitor's touchscreen acted on the tablet and the
 * monitor took no touch at all. Nothing on this firmware ties input to a screen explicitly - no ZUI
 * or Motorola app calls the input system's associations.
 *
 * <p>So the input system's own association by device ({@code addUniqueIdAssociationByDescriptor},
 * the call Android has for exactly this) ties each external touchscreen to the external screen
 * whenever either changes: after the screens are handed to the input system, and after the input
 * devices change. Only with one external screen, only wired touchscreens and pens - the tablet's
 * own are internal or Bluetooth - and taken off again when the screen goes, so the next one is
 * tied afresh. The screens handed over are written to the log at each change, and so are the
 * mice (they are never tied: they follow the pointer's screen). Nothing runs at load
 * time but the hooks; everything is in try/catch, on the threads the system already runs these
 * on.
 */
public final class TouchScreens {

    /** {@code DisplayViewport.VIEWPORT_EXTERNAL}. */
    private static final int EXTERNAL = 2;

    private static boolean sInstalled;
    /** The list last written, so an unchanged one is not written again. */
    private static String sLast;
    /** The mice last written, so the log has each one coming, going or turned off, once. */
    private static String sMice = "";
    /** The touchscreens we tied, by descriptor, to the screen's unique id. Under its own lock. */
    private static final Map<String, String> TIED = new HashMap<>();
    /** What each tied device is, for the log: its name and sources. Under {@link #TIED}'s lock. */
    private static final Map<String, String> NAMES = new HashMap<>();
    private static Field sViewports;
    private static Method sAdd;
    private static Method sRemove;

    private TouchScreens() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> input = Class.forName(SystemNames.INPUT_MANAGER_SERVICE, false, loader);
            sViewports = input.getDeclaredField(SystemNames.M_DISPLAY_VIEWPORT_LIST);
            sViewports.setAccessible(true);
            sAdd = input.getMethod(SystemNames.ADD_UNIQUE_ID_ASSOCIATION_BY_DESCRIPTOR,
                    String.class, String.class);
            sRemove = input.getMethod(SystemNames.REMOVE_UNIQUE_ID_ASSOCIATION_BY_DESCRIPTOR,
                    String.class);
            int hooked = XposedBridge.hookAllMethods(input,
                    SystemNames.SET_DISPLAY_VIEWPORTS_INTERNAL, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            write(param.args);
                            tie(param.thisObject);
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(input, SystemNames.DELIVER_INPUT_DEVICES_CHANGED,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            tie(param.thisObject);
                        }
                    }).size();
            L.i("touch screens: the monitor's touchscreen tied to the monitor x" + hooked);
        } catch (Throwable t) {
            L.w("touch screens: not watched, touchscreens left to Android (" + t + ")");
        }
    }

    private static void write(Object[] args) {
        try {
            if (args.length == 0 || !(args[0] instanceof List)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Object viewport : (List<?>) args[0]) {
                sb.append("\n  ").append(viewport);
            }
            String now = sb.toString();
            if (now.equals(sLast)) {
                return;
            }
            sLast = now;
            L.i("touch screens: the input system's screens are now" + (now.isEmpty()
                    ? " none" : now));
        } catch (Throwable ignored) {
            // A line less; the system's call went through untouched.
        }
    }

    /** Each external touchscreen to the one external screen; ours untied when it goes. */
    private static void tie(Object input) {
        try {
            String screen = Cfg.monitorTouch() ? externalScreen(input) : null;
            List<String> touch = new ArrayList<>();
            Map<String, String> names = new HashMap<>();
            StringBuilder mice = new StringBuilder();
            for (InputDevice device : (InputDevice[]) input.getClass()
                    .getMethod(SystemNames.GET_INPUT_DEVICES).invoke(input)) {
                if (onTheMonitor(device)) {
                    touch.add(device.getDescriptor());
                    names.put(device.getDescriptor(), describe(device));
                }
                if (device != null && device.isExternal() && !device.isVirtual()
                        && device.supportsSource(InputDevice.SOURCE_MOUSE)) {
                    mice.append("\n  ").append(device.getName()).append(", id ")
                            .append(device.getId()).append(device.isEnabled() ? "" : ", OFF");
                }
            }
            writeMice(mice.toString());
            synchronized (TIED) {
                for (String descriptor : new ArrayList<>(TIED.keySet())) {
                    if (screen == null || !touch.contains(descriptor)) {
                        call(sRemove, input, descriptor);
                        TIED.remove(descriptor);
                        L.i("touch screens: untied - the screen or the device went: "
                                + NAMES.remove(descriptor));
                    }
                }
                if (screen == null) {
                    return;
                }
                for (String descriptor : touch) {
                    if (!screen.equals(TIED.get(descriptor))) {
                        call(sAdd, input, descriptor, screen);
                        TIED.put(descriptor, screen);
                        NAMES.put(descriptor, names.get(descriptor));
                        // Named, with what else the device is: a mouse's receiver that also
                        // reports touch or a pen would take the mouse to the monitor with it.
                        L.i("touch screens: tied to the monitor (" + screen + "): "
                                + names.get(descriptor));
                    }
                }
            }
        } catch (Throwable t) {
            L.d("touch screens: left as Android has them (" + t + ")");
        }
    }

    /**
     * The mice, written down as they come, go or are turned off: one stopped until it was
     * replugged, and neither log said whether Android still had it, or had turned it off.
     */
    private static synchronized void writeMice(String now) {
        if (now.equals(sMice)) {
            return;
        }
        sMice = now;
        L.i("touch screens: the mice are now" + (now.isEmpty() ? " none" : now));
    }

    /**
     * A wired touchscreen or pen: the monitor's. A touchscreen's pen is often an input device of
     * its own, pen only, and stayed on the tablet with the touch. Bluetooth devices are left alone
     * - the tablet's own pen and keyboards are Bluetooth, and belong wherever the user takes them.
     * Mice and touchpads keep following the pointer's screen.
     */
    private static boolean onTheMonitor(InputDevice device) {
        return device != null && device.isExternal() && !device.isVirtual()
                // getBluetoothAddress is hidden from the SDK: by name.
                && Reflect.call(device, SystemNames.GET_BLUETOOTH_ADDRESS) == null
                && (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
                || device.supportsSource(InputDevice.SOURCE_STYLUS));
    }

    /** A device for the log: its name, its sources, and whether it is also a mouse. */
    private static String describe(InputDevice device) {
        return device.getName() + ", sources 0x" + Integer.toHexString(device.getSources())
                + (device.supportsSource(InputDevice.SOURCE_MOUSE) ? ", also a mouse" : "")
                + (device.supportsSource(InputDevice.SOURCE_TOUCHPAD) ? ", also a touchpad" : "");
    }

    /** The one valid external screen's unique id, or null for none or more than one. */
    private static String externalScreen(Object input) throws Exception {
        List<?> viewports = (List<?>) sViewports.get(input);
        String found = null;
        synchronized (viewports) {
            for (Object viewport : viewports) {
                // DisplayViewport is hidden from apps: its public fields, by name.
                Class<?> c = viewport.getClass();
                Object uniqueId = c.getField(SystemNames.UNIQUE_ID).get(viewport);
                if (c.getField(SystemNames.TYPE).getInt(viewport) != EXTERNAL
                        || !c.getField(SystemNames.VALID).getBoolean(viewport)
                        || !(uniqueId instanceof String)) {
                    continue;
                }
                if (found != null && !found.equals(uniqueId)) {
                    return null;
                }
                found = (String) uniqueId;
            }
        }
        return found;
    }

    /** One of the input system's own calls, as the system itself. */
    private static void call(Method method, Object input, Object... args) throws Exception {
        long identity = Binder.clearCallingIdentity();
        try {
            method.invoke(input, args);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
