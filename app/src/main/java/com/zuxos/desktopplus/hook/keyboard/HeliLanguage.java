package com.zuxos.desktopplus.hook.keyboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.inputmethod.InputMethodSubtype;

import com.zuxos.desktopplus.core.FullScreenWire;
import com.zuxos.desktopplus.core.KeyboardWire;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.logic.LanguageCode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * HeliBoard's languages in the monitor's tray, as DeX's taskbar has them ({@link KeyboardWire}).
 * HeliBoard switches between its languages itself - Android's keyboard list sees one of them,
 * and never which is on - so it says them itself: the ones it has on, in its own order, and the
 * one on, to ZUX Home's Messenger, when asked and whenever they change. Every switch of its
 * language ends in its subtype change ({@code onCurrentInputMethodSubtypeChanged}), so that is
 * where a change is told; a list changed in its settings is told the next time the keyboard
 * shows. Asked for the next language or one picked, it answers with the switch its own language
 * key ends in ({@code onCurrentInputMethodSubtypeChanged}): the next is the one after the language
 * on in its own list, never another keyboard, as its language key can be set to. Heard only while
 * the keyboard's service lives: from its creation to its end.
 *
 * <p>HeliBoard's release build is shrunk by R8, which reshapes what is reached here: a Kotlin
 * object's methods made static with no {@code INSTANCE} left, small getters dropped for their
 * fields, a one-line alias inlined away. Each piece is taken in either shape, and a missing one is
 * named in the install line.
 */
public final class HeliLanguage {

    /** Each keyboard service's listener, for its end. */
    private static final Map<Object, BroadcastReceiver> HEARD = new WeakHashMap<>();

    /** ZUX Home's Messenger, from its last word; answered on until it is gone. */
    private static Messenger sTray;
    /** HeliBoard's own, sent with each answer, so ZUX Home sees it go if HeliBoard does. */
    private static Messenger sSelf;
    /** What ZUX Home was last told, so the same is not told again. */
    private static String sTold;

    /** Its languages on, in its own order: a Kotlin object's, static once R8 has shrunk it. */
    private static Method sEnabled;
    /** What {@link #sEnabled} is called on: nothing when static, the object itself when not. */
    private static Object sSettings;
    /** Its language manager: a static singleton. */
    private static Field sManager;
    private static Method sCurrent;
    /** The language on, as Android's subtype: a field, since R8 drops its getter. */
    private static Field sRaw;
    /** The switch its own language key ends in. */
    private static Method sSwitch;
    /** HeliBoard's own name for a language; null where it is not found - the locale's then. */
    private static Object sLocaleUtils;
    private static Method sDisplayName;

    private HeliLanguage() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> ime = Reflect.findClass(HeliNames.LATIN_IME, loader);
            Class<?> settings = Reflect.findClass(HeliNames.SUBTYPE_SETTINGS, loader);
            Class<?> manager = Reflect.findClass(HeliNames.RICH_INPUT_METHOD_MANAGER, loader);
            Class<?> rich = Reflect.findClass(HeliNames.RICH_INPUT_METHOD_SUBTYPE, loader);
            if (ime == null || settings == null || manager == null || rich == null) {
                L.w("heli language: HeliBoard's keyboard or its languages not found - the tray "
                        + "shows no language");
                return;
            }
            sEnabled = settings.getMethod(HeliNames.GET_ENABLED_SUBTYPES, boolean.class);
            sSettings = Modifier.isStatic(sEnabled.getModifiers()) ? null
                    : settings.getField(HeliNames.INSTANCE).get(null);
            sManager = manager.getDeclaredField(HeliNames.MANAGER_INSTANCE);
            sManager.setAccessible(true);
            sCurrent = manager.getMethod(HeliNames.GET_CURRENT_SUBTYPE);
            sRaw = rich.getDeclaredField(HeliNames.RAW_SUBTYPE);
            sRaw.setAccessible(true);
            sSwitch = ime.getMethod(HeliNames.ON_CURRENT_INPUT_METHOD_SUBTYPE_CHANGED,
                    InputMethodSubtype.class);
            names(loader);
            int hooked = XposedBridge.hookAllMethods(ime, HeliNames.ON_CREATE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            listen(param.thisObject);
                            tell(true, false);
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(ime, HeliNames.ON_DESTROY,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            stop(param.thisObject);
                            // Another keyboard is taking over: the tray shows none.
                            tell(false, false);
                        }
                    }).size();
            XC_MethodHook changed = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    tell(true, false);
                }
            };
            hooked += XposedBridge.hookAllMethods(ime,
                    HeliNames.ON_CURRENT_INPUT_METHOD_SUBTYPE_CHANGED, changed).size();
            hooked += XposedBridge.hookAllMethods(ime, HeliNames.ON_START_INPUT_VIEW, changed)
                    .size();
            L.i("heli language: the tray shows and changes the keyboard's language x" + hooked);
        } catch (Throwable t) {
            L.w("heli language: not installed, the tray shows no language (" + t + ")");
        }
    }

    /**
     * HeliBoard's own name for a language, from its language list, where it is there: shrunk,
     * only its static form with the default locale is left; whole, the object's own.
     */
    private static void names(ClassLoader loader) {
        try {
            Class<?> utils = Reflect.findClass(HeliNames.SUBTYPE_LOCALE_UTILS, loader);
            if (utils != null) {
                try {
                    sDisplayName = utils.getMethod(HeliNames.DISPLAY_NAME_DEFAULT,
                            InputMethodSubtype.class);
                } catch (NoSuchMethodException whole) {
                    sDisplayName = utils.getMethod(HeliNames.DISPLAY_NAME,
                            InputMethodSubtype.class, Locale.class);
                    sLocaleUtils = utils.getField(HeliNames.INSTANCE).get(null);
                }
            }
        } catch (Throwable t) {
            sDisplayName = null;
        }
        if (sDisplayName == null && L.once("HeliLanguage.names")) {
            L.w("heli language: HeliBoard's names for its languages not found - the tray names "
                    + "them by their locale");
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

    /**
     * ZUX Home's word: its Messenger kept, then the next language, the one picked by its hash,
     * or - asked, or a Messenger not heard before - the languages told at once.
     */
    private static void answer(InputMethodService ime, Intent intent) throws Exception {
        Messenger reply = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableExtra(KeyboardWire.EXTRA_REPLY, Messenger.class)
                : legacyReply(intent);
        boolean fresh = false;
        synchronized (HeliLanguage.class) {
            if (reply != null && !reply.equals(sTray)) {
                sTray = reply;
                fresh = true;
            }
        }
        if (intent.getBooleanExtra(KeyboardWire.EXTRA_NEXT, false)) {
            // The one after the language on, round to the first.
            List<?> languages = enabled();
            if (languages.size() > 1) {
                int at = languages.indexOf(current());
                switchTo(ime, (InputMethodSubtype) languages.get((at + 1) % languages.size()));
            }
        } else if (intent.hasExtra(KeyboardWire.EXTRA_SUBTYPE)) {
            int wanted = intent.getIntExtra(KeyboardWire.EXTRA_SUBTYPE, 0);
            InputMethodSubtype found = null;
            for (Object language : enabled()) {
                if (language instanceof InputMethodSubtype && language.hashCode() == wanted) {
                    found = (InputMethodSubtype) language;
                }
            }
            if (found != null) {
                switchTo(ime, found);
            } else if (L.once("HeliLanguage.missing")) {
                L.w("heli language: the language picked in the tray is not one of the "
                        + "keyboard's");
            }
        } else {
            fresh = true;
        }
        if (fresh) {
            tell(true, true);
        }
    }

    @SuppressWarnings("deprecation")
    private static Messenger legacyReply(Intent intent) {
        return intent.getParcelableExtra(KeyboardWire.EXTRA_REPLY);
    }

    /**
     * HeliBoard's own switch, the one its language key ends in. Its last step saves the language
     * for the app being typed in, and throws with none: the switch is made by then.
     */
    private static void switchTo(InputMethodService ime, InputMethodSubtype language) {
        try {
            sSwitch.invoke(ime, language);
        } catch (Throwable t) {
            L.d("heli language: switched, not saved for the app (" + t + ")");
        }
        if (L.once("HeliLanguage.switched")) {
            L.i("heli language: switched from the tray");
        }
    }

    /**
     * ZUX Home told the languages - or none, while HeliBoard is not the keyboard - unless it
     * was last told the same; {@code again} tells it all the same, to a Messenger new to it.
     */
    private static synchronized void tell(boolean on, boolean again) {
        Messenger tray = sTray;
        if (tray == null) {
            return;
        }
        try {
            Bundle state = new Bundle();
            int count = 0;
            if (on) {
                List<?> languages = enabled();
                int[] ids = new int[languages.size()];
                String[] codes = new String[ids.length];
                String[] names = new String[ids.length];
                String[] tags = new String[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    InputMethodSubtype language = (InputMethodSubtype) languages.get(i);
                    ids[i] = language.hashCode();
                    codes[i] = code(language);
                    names[i] = name(language);
                    tags[i] = tag(language);
                }
                Object current = current();
                state.putIntArray(KeyboardWire.KEY_IDS, ids);
                state.putStringArray(KeyboardWire.KEY_CODES, codes);
                state.putStringArray(KeyboardWire.KEY_NAMES, names);
                state.putStringArray(KeyboardWire.KEY_TAGS, tags);
                state.putInt(KeyboardWire.KEY_CURRENT, current != null ? current.hashCode() : 0);
                count = ids.length;
            }
            String said = Arrays.toString(state.getIntArray(KeyboardWire.KEY_IDS)) + "/"
                    + state.getInt(KeyboardWire.KEY_CURRENT) + "/"
                    + Arrays.toString(state.getStringArray(KeyboardWire.KEY_NAMES));
            if (!again && said.equals(sTold)) {
                return;
            }
            Message message = Message.obtain(null, KeyboardWire.WHAT_LANGUAGES);
            message.setData(state);
            message.replyTo = self();
            tray.send(message);
            sTold = said;
            if (on && L.once("HeliLanguage.told")) {
                L.i("heli language: told the tray " + count + " language(s)");
            }
        } catch (RemoteException gone) {
            // ZUX Home went: the next one asks again.
            sTray = null;
            sTold = null;
        } catch (Throwable t) {
            L.d("heli language: the tray not told (" + t + ")");
        }
    }

    private static List<?> enabled() throws Exception {
        return (List<?>) sEnabled.invoke(sSettings, true);
    }

    /** The language on, as Android's subtype. */
    private static Object current() throws Exception {
        return sRaw.get(sCurrent.invoke(sManager.get(null)));
    }

    /** The language's BCP 47 tag: its own, or its older locale string made into one. */
    @SuppressWarnings("deprecation")
    private static String tag(InputMethodSubtype language) {
        String tag = language.getLanguageTag();
        return tag != null && !tag.isEmpty() ? tag : language.getLocale().replace('_', '-');
    }

    @SuppressWarnings("deprecation")
    private static String code(InputMethodSubtype language) {
        return LanguageCode.of(language.getLanguageTag(), language.getLocale());
    }

    @SuppressWarnings("deprecation")
    private static String name(InputMethodSubtype language) {
        try {
            if (sDisplayName != null) {
                Object name = sDisplayName.getParameterCount() == 1
                        ? sDisplayName.invoke(null, language)
                        : sDisplayName.invoke(sLocaleUtils, language, null);
                if (name instanceof String && !((String) name).isEmpty()) {
                    return (String) name;
                }
            }
        } catch (Throwable t) {
            // The locale's own name, then.
        }
        String tag = language.getLanguageTag();
        Locale locale = tag != null && !tag.isEmpty() ? Locale.forLanguageTag(tag)
                : new Locale(language.getLocale());
        String name = locale.getDisplayName(locale);
        return name.isEmpty() ? code(language) : name;
    }

    /** A Messenger of HeliBoard's own that answers nothing: only its life is wanted of it. */
    private static Messenger self() {
        if (sSelf == null) {
            sSelf = new Messenger(new Handler(Looper.getMainLooper()));
        }
        return sSelf;
    }
}
