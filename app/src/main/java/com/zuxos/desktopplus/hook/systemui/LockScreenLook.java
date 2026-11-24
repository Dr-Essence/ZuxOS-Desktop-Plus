package com.zuxos.desktopplus.hook.systemui;

import android.app.Presentation;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The monitor's lock screen in the tablet's look: ZUI's Smart Connect lock screen
 * ({@link LockScreenDisplays}) dressed in ZUI's own pieces, its password check and unlock left
 * as they are. Its clock is Motorola's ({@code PtClockView}), on the desktop wallpaper.
 *
 * <p>So its clock is the one ZUI's own code gives a lock screen on another screen: a
 * {@code ConnectedDisplayKeyguardPresentation} is made by ZUI's own factory (never shown), and its
 * clock - ZUI's {@code ClockRegistry} clock, whose only provider in this SystemUI is Android's own
 * - is put in Motorola's clock's place, attached, ticked and let go with the lock screen
 * ({@code setClock}, {@code onAttachedToWindow}, {@code onDetachedFromWindow}). Motorola's clock
 * stays there unseen, so its own showing and hiding still works, and this one, beside it in the
 * lock screen's plain root, follows it. Its background is the lock wallpaper, the tablet's, where
 * one is set apart from the home wallpaper. Each piece that fails is written once and leaves
 * Motorola's. Whether the tablet's own lock screen asks the registry for the same face is written
 * down once ({@link #traceClock}), for the look to follow the tablet's.
 */
final class LockScreenLook {

    /** Each lock screen's borrowed ZUI clock lock screen, which runs its clock. */
    private static final Map<Object, Object> CLOCKS = new WeakHashMap<>();

    private LockScreenLook() {
    }

    /** ZUI's pieces put on its Smart Connect lock screen as it is made, shown and let go. */
    static int install(Class<?> lock) {
        return XposedBridge.hookAllMethods(lock, SysUiNames.ON_CREATE, MADE).size()
                + XposedBridge.hookAllMethods(lock, SysUiNames.ON_ATTACHED_TO_WINDOW, SHOWN)
                .size()
                + XposedBridge.hookAllMethods(lock, SysUiNames.ON_DETACHED_FROM_WINDOW, GONE)
                .size();
    }

    /**
     * Evidence for the look: which clock each lock screen asks ZUI's registry for - the tablet's
     * own and the monitor's borrowed one - with the faces the registry offers and the tablet's
     * setting for its face, once for each that asks. Read-only.
     */
    static int traceClock(ClassLoader loader) {
        Class<?> registry = Reflect.findClass(SysUiNames.CLOCK_REGISTRY_CLASS, loader);
        return registry == null ? 0
                : XposedBridge.hookAllMethods(registry, SysUiNames.CREATE_CURRENT_CLOCK, ASKED)
                .size();
    }

    private static final XC_MethodHook ASKED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String asker = asker();
                if (!L.once("LockScreenLook.asked." + asker)) {
                    return;
                }
                Object id = Reflect.call(param.thisObject, SysUiNames.GET_CURRENT_CLOCK_ID);
                Object clocks = Reflect.field(param.thisObject, SysUiNames.AVAILABLE_CLOCKS);
                Object context = Reflect.field(param.thisObject, SysUiNames.CONTEXT);
                String face = context instanceof Context ? Settings.Secure.getString(
                        ((Context) context).getContentResolver(),
                        SysUiNames.LOCK_SCREEN_CUSTOM_CLOCK_FACE) : "?";
                Object clock = param.getResult();
                L.i("lock screen: " + asker + " asks ZUI's clock registry for '" + id
                        + "' and gets " + (clock == null ? "none" : clock.getClass().getName())
                        + "; faces offered " + (clocks instanceof Map
                        ? ((Map<?, ?>) clocks).keySet() : "?") + ", the tablet's setting "
                        + face);
            } catch (Throwable ignored) {
                // A line less; the clock went through untouched.
            }
        }
    };

    /** The first caller outside the registry and the hooking itself. */
    private static String asker() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith(SysUiNames.CLOCK_REGISTRY_CLASS) || cls.startsWith("java.")
                    || cls.startsWith("dalvik.") || cls.startsWith("de.robv.")
                    || cls.startsWith("org.lsposed.") || cls.startsWith("LSPHooker")
                    || cls.startsWith(LockScreenLook.class.getName() + "$")) {
                continue;
            }
            return cls + "." + frame.getMethodName();
        }
        return "?";
    }

    private static final XC_MethodHook MADE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.hasThrowable() || !LockScreenDisplays.dressed()) {
                return;
            }
            clock(param.thisObject);
            wallpaper(param.thisObject);
        }
    };

    private static final XC_MethodHook SHOWN = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            run(param.thisObject, SysUiNames.ON_ATTACHED_TO_WINDOW);
        }
    };

    private static final XC_MethodHook GONE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            run(param.thisObject, SysUiNames.ON_DETACHED_FROM_WINDOW);
        }
    };

    /** The tablet's clock face in Motorola's clock's place. */
    private static void clock(Object lock) {
        try {
            Object container = Reflect.field(lock, SysUiNames.M_KEYGUARD_CLOCK_CONTAINER);
            Object root = Reflect.field(lock, SysUiNames.M_ROOT_VIEW);
            Object manager = LockScreenDisplays.manager();
            Object factory = Reflect.field(manager, SysUiNames.M_CONNECTED_DISPLAY_FACTORY);
            Display display = ((Presentation) lock).getDisplay();
            if (!(container instanceof ViewGroup) || !(root instanceof FrameLayout)
                    || factory == null || display == null) {
                return;
            }
            Object zui = Reflect.call(factory, SysUiNames.CREATE, display);
            Object registry = Reflect.field(zui, SysUiNames.CLOCK_REGISTRY);
            Object face = Reflect.call(registry, SysUiNames.CREATE_CURRENT_CLOCK);
            if (zui == null || face == null) {
                return;
            }
            ViewGroup motorola = (ViewGroup) container;
            FrameLayout frame = (FrameLayout) root;
            FrameLayout holder = new FrameLayout(motorola.getContext());
            zui.getClass().getField(SysUiNames.ROOT_VIEW).set(zui, holder);
            Reflect.call(zui, SysUiNames.SET_CLOCK, face);
            if (holder.getChildCount() == 0) {
                return;
            }
            // Beside Motorola's clock in the plain root, not inside its own clock view, whose
            // layout is Motorola's; shown and hidden with it, which stays there unseen.
            motorola.setAlpha(0f);
            frame.addView(holder, frame.indexOfChild(motorola) + 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            holder.setVisibility(motorola.getVisibility());
            motorola.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (holder.getVisibility() != motorola.getVisibility()) {
                    holder.setVisibility(motorola.getVisibility());
                }
            });
            CLOCKS.put(lock, zui);
            if (L.once("LockScreenLook.clock")) {
                L.i("lock screen: the tablet's clock face on the monitor's lock screen");
            }
        } catch (Throwable t) {
            if (L.once("LockScreenLook.clockFailed")) {
                L.w("lock screen: Motorola's clock kept on the monitor (" + t + ")");
            }
        }
    }

    /** The borrowed clock's own attaching and letting go, with the lock screen's. */
    private static void run(Object lock, String step) {
        Object zui = CLOCKS.get(lock);
        if (zui == null) {
            return;
        }
        try {
            Reflect.call(zui, step);
        } catch (Throwable t) {
            if (L.once("LockScreenLook." + step)) {
                L.w("lock screen: the tablet's clock not " + step + " (" + t + ")");
            }
        }
        if (SysUiNames.ON_DETACHED_FROM_WINDOW.equals(step)) {
            CLOCKS.remove(lock);
        }
    }

    /**
     * The lock wallpaper, as the tablet's lock screen has it, where one is set apart: a copy, as
     * Motorola's own, which it recycles when it goes.
     */
    private static void wallpaper(Object lock) {
        try {
            Object root = Reflect.field(lock, SysUiNames.M_ROOT_VIEW);
            if (!(root instanceof View)) {
                return;
            }
            View view = (View) root;
            WallpaperManager wallpapers = WallpaperManager.getInstance(view.getContext());
            Drawable lockPaper = wallpapers.getDrawable(WallpaperManager.FLAG_LOCK);
            if (!(lockPaper instanceof BitmapDrawable)
                    || ((BitmapDrawable) lockPaper).getBitmap() == null) {
                return;
            }
            Bitmap copy = ((BitmapDrawable) lockPaper).getBitmap()
                    .copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) {
                return;
            }
            Drawable before = view.getBackground();
            view.setBackground(new BitmapDrawable(view.getResources(), copy));
            if (before instanceof BitmapDrawable && ((BitmapDrawable) before).getBitmap() != null) {
                ((BitmapDrawable) before).getBitmap().recycle();
            }
            if (L.once("LockScreenLook.wallpaper")) {
                L.i("lock screen: the lock wallpaper on the monitor's lock screen");
            }
        } catch (Throwable t) {
            if (L.once("LockScreenLook.wallpaperFailed")) {
                L.w("lock screen: Motorola's wallpaper kept on the monitor (" + t + ")");
            }
        }
    }
}
