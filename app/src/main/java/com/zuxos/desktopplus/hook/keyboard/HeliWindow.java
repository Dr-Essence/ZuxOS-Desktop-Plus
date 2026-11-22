package com.zuxos.desktopplus.hook.keyboard;

import android.app.Dialog;
import android.inputmethodservice.InputMethodService;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * HeliBoard's strip and keys above the taskbar, on both screens.
 *
 * <p>Android lays a keyboard's window out to the screen's bottom edge
 * ({@code InputMethodService} fits it to the bars on every side but the bottom) and leaves the
 * keyboard to keep its keys above the bar, through its window's padding. Edge to edge is forced on
 * a keyboard built for Android 15 and later, which takes that padding away, and HeliBoard adds
 * none of its own: its strip and bottom row lay under the taskbar, whose window is above the
 * keyboard's.
 *
 * <p>A window works out its own frame here, from its own attributes, as well as in the system
 * ({@code ViewRootImpl}), and keeps the one it worked out; so the change is to the window's
 * attributes in HeliBoard itself, or the two disagree and the keyboard falls back under the bar.
 * The window fits the bottom too, and ends where ZUI tells keyboards the bar starts
 * ({@code taskbar_ime_size}, the bar's own height). With the bar hidden by the app, or reporting
 * 1 px in ZUI's game mode (our Full screen), there is nothing to fit and the keyboard keeps the
 * bottom.
 */
public final class HeliWindow {

    private HeliWindow() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> ime = Reflect.findClass(HeliNames.LATIN_IME, loader);
            if (ime == null) {
                L.w("heli window: HeliBoard's keyboard not found - its keys may lie under the "
                        + "taskbar");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(ime, HeliNames.ON_CREATE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                fitBottom(param.thisObject);
                            } catch (Throwable t) {
                                L.w("heli window: the keyboard's window left as it is (" + t
                                        + ")");
                            }
                        }
                    }).size();
            L.i("heli window: the keyboard stops at the taskbar x" + hooked);
        } catch (Throwable t) {
            L.w("heli window: not installed, the keyboard lays out as it asks (" + t + ")");
        }
    }

    private static void fitBottom(Object service) {
        Dialog dialog = service instanceof InputMethodService
                ? ((InputMethodService) service).getWindow() : null;
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) {
            return;
        }
        WindowManager.LayoutParams attrs = window.getAttributes();
        int sides = attrs.getFitInsetsSides();
        if ((sides & WindowInsets.Side.BOTTOM) != 0) {
            return;
        }
        attrs.setFitInsetsSides(sides | WindowInsets.Side.BOTTOM);
        window.setAttributes(attrs);
        if (L.once("HeliWindow")) {
            L.i("heli window: the keyboard's window fits the bottom - it ends at the taskbar");
        }
    }
}
