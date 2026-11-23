package com.zuxos.desktopplus.hook.keyboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.zuxos.desktopplus.core.FullScreenWire;
import com.zuxos.desktopplus.core.KeyboardWire;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * HeliBoard's language changed from the monitor's tray ({@link KeyboardWire}), as DeX's taskbar
 * changes it. The keyboard answers with Android's own keyboard calls - the next of its languages
 * ({@code switchToNextInputMethod}), or the one picked ({@code switchInputMethod}) - and follows
 * them as it follows the system's own language switch. Heard only while the keyboard's service
 * lives: from its creation to its end.
 */
public final class HeliLanguage {

    /** Each keyboard service's listener, for its end. */
    private static final Map<Object, BroadcastReceiver> HEARD = new WeakHashMap<>();

    private HeliLanguage() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> ime = Reflect.findClass(HeliNames.LATIN_IME, loader);
            if (ime == null) {
                L.w("heli language: HeliBoard's keyboard not found - the tray cannot change its "
                        + "language");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(ime, HeliNames.ON_CREATE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            listen(param.thisObject);
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(ime, HeliNames.ON_DESTROY,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            stop(param.thisObject);
                        }
                    }).size();
            L.i("heli language: the tray can change the keyboard's language x" + hooked);
        } catch (Throwable t) {
            L.w("heli language: not installed (" + t + ")");
        }
    }

    private static void listen(Object service) {
        try {
            if (!(service instanceof InputMethodService) || HEARD.containsKey(service)) {
                return;
            }
            InputMethodService ime = (InputMethodService) service;
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        answer(ime, intent);
                    } catch (Throwable t) {
                        L.d("heli language: left as it is (" + t + ")");
                    }
                }
            };
            IntentFilter filter = new IntentFilter(KeyboardWire.ACTION_LANGUAGE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ime.registerReceiver(receiver, filter, FullScreenWire.SENDER, null,
                        Context.RECEIVER_EXPORTED);
            } else {
                ime.registerReceiver(receiver, filter, FullScreenWire.SENDER, null);
            }
            HEARD.put(service, receiver);
        } catch (Throwable t) {
            L.w("heli language: the tray not heard (" + t + ")");
        }
    }

    private static void stop(Object service) {
        try {
            BroadcastReceiver receiver = HEARD.remove(service);
            if (receiver != null) {
                ((Context) service).unregisterReceiver(receiver);
            }
        } catch (Throwable t) {
            L.d("heli language: already gone (" + t + ")");
        }
    }

    /** The next language, or the one asked for by its subtype's hash, as the keyboard itself. */
    private static void answer(InputMethodService ime, Intent intent) {
        if (intent.getBooleanExtra(KeyboardWire.EXTRA_NEXT, false)) {
            boolean moved = ime.switchToNextInputMethod(true);
            if (L.once("HeliLanguage.next")) {
                L.i("heli language: the next language, from the tray - " + (moved ? "moved"
                        : "Android had none to move to"));
            }
            return;
        }
        int wanted = intent.getIntExtra(KeyboardWire.EXTRA_SUBTYPE, 0);
        InputMethodManager imm = ime.getSystemService(InputMethodManager.class);
        if (imm == null) {
            return;
        }
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (!info.getPackageName().equals(ime.getPackageName())) {
                continue;
            }
            for (InputMethodSubtype subtype : imm.getEnabledInputMethodSubtypeList(info, true)) {
                if (subtype.hashCode() == wanted) {
                    ime.switchInputMethod(info.getId(), subtype);
                    return;
                }
            }
        }
        if (L.once("HeliLanguage.missing")) {
            L.w("heli language: the language picked in the tray is not one of the keyboard's");
        }
    }
}
