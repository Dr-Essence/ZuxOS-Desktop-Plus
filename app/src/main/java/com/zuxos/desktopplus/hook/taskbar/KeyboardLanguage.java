package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.zuxos.desktopplus.core.KeyboardWire;
import com.zuxos.desktopplus.core.L;

import java.util.List;
import java.util.Locale;

/**
 * The keyboard's language in the monitor's tray, as DeX's taskbar shows it: Android's own word on
 * the current keyboard, its languages and which one is on. Only a keyboard may change its own
 * language, so HeliBoard is asked ({@link KeyboardWire}); any other keyboard's languages are left
 * to its settings, opened on the tray's screen.
 */
final class KeyboardLanguage {

    /** Android's setting of the current keyboard's language: hidden from the SDK, by name. */
    private static final String SELECTED_SUBTYPE = "selected_input_method_subtype";

    /** The current keyboard, its languages and which is on. */
    static final class State {
        final String pkg;
        final List<InputMethodSubtype> languages;
        final InputMethodSubtype current;

        State(String pkg, List<InputMethodSubtype> languages, InputMethodSubtype current) {
            this.pkg = pkg;
            this.languages = languages;
            this.current = current;
        }
    }

    private KeyboardLanguage() {
    }

    /** The current keyboard's state, or null where Android says nothing of it. */
    static State read(Context ctx) {
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        String id = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (imm == null || id == null) {
            return null;
        }
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (id.equals(info.getId())) {
                State state = new State(info.getPackageName(),
                        imm.getEnabledInputMethodSubtypeList(info, true),
                        imm.getCurrentInputMethodSubtype());
                if (L.once("KeyboardLanguage.read")) {
                    L.i("keyboard language: " + state.pkg + " has " + state.languages.size()
                            + " language(s), on now " + code(state.current));
                }
                return state;
            }
        }
        return null;
    }

    /** A language's short code for the tray: "EN" for en-US. */
    static String code(InputMethodSubtype subtype) {
        if (subtype == null) {
            return "";
        }
        String tag = subtype.getLanguageTag();
        if (tag == null || tag.isEmpty()) {
            tag = subtype.getLocale();
        }
        return tag == null ? "" : tag.split("[-_]")[0].toUpperCase(Locale.ROOT);
    }

    /** A language's name for the tray's menu, as the keyboard names it. */
    static String name(Context ctx, State state, InputMethodSubtype subtype) {
        try {
            ApplicationInfo app = ctx.getPackageManager().getApplicationInfo(state.pkg, 0);
            CharSequence name = subtype.getDisplayName(ctx, state.pkg, app);
            if (name != null && name.length() > 0) {
                return name.toString();
            }
        } catch (Throwable t) {
            // Its code, then.
        }
        return code(subtype);
    }

    /** The next language: HeliBoard asked, any other keyboard's settings opened. */
    static void next(Context ctx, int display) {
        State state = read(ctx);
        if (state != null && KeyboardWire.HELIBOARD.equals(state.pkg)) {
            ctx.sendBroadcast(new Intent(KeyboardWire.ACTION_LANGUAGE)
                    .setPackage(KeyboardWire.HELIBOARD)
                    .putExtra(KeyboardWire.EXTRA_NEXT, true));
            return;
        }
        settings(ctx, display);
    }

    /** One language picked from the tray's menu. */
    static void pick(Context ctx, InputMethodSubtype subtype) {
        ctx.sendBroadcast(new Intent(KeyboardWire.ACTION_LANGUAGE)
                .setPackage(KeyboardWire.HELIBOARD)
                .putExtra(KeyboardWire.EXTRA_SUBTYPE, subtype.hashCode()));
    }

    /** Android's keyboard settings, on the tray's screen. */
    static void settings(Context ctx, int display) {
        TaskbarMenu.open(ctx, Settings.ACTION_INPUT_METHOD_SETTINGS, display);
    }

    /**
     * Told when the keyboard, its languages or the one on change - Android's own settings, so
     * nothing is asked while nothing changes. Taken off again with {@link #unwatch}.
     */
    static ContentObserver watch(Context ctx, Runnable changed) {
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                changed.run();
            }
        };
        for (String key : new String[]{Settings.Secure.DEFAULT_INPUT_METHOD,
                Settings.Secure.ENABLED_INPUT_METHODS, SELECTED_SUBTYPE}) {
            ctx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer);
        }
        return observer;
    }

    static void unwatch(Context ctx, ContentObserver observer) {
        if (observer != null) {
            ctx.getContentResolver().unregisterContentObserver(observer);
        }
    }
}
