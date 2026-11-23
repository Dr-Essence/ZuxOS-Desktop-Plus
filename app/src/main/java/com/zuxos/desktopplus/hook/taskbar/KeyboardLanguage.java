package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.provider.Settings;

import com.zuxos.desktopplus.core.KeyboardWire;
import com.zuxos.desktopplus.core.L;

import java.util.ArrayList;
import java.util.List;

/**
 * The keyboard's language in the monitor's tray, as DeX's taskbar shows it. HeliBoard keeps its
 * languages to itself - Android's keyboard list sees one of them, and never which is on - so it
 * says them itself ({@link KeyboardWire}): asked when the first tray comes on screen, it answers
 * on ZUX Home's Messenger, and again whenever they change. Its own Messenger comes with each
 * answer, so a HeliBoard that dies takes the button with it, and it is asked again the next time
 * the keyboard comes up. Only a keyboard may change its own language, so the tray asks it to;
 * with any other keyboard the button stays away. Main thread only.
 */
final class KeyboardLanguage {

    /** HeliBoard's languages as it last said them, in its own order, and the one on. */
    static final class State {
        private final int[] mIds;
        private final String[] mCodes;
        private final String[] mNames;
        /** Where the one on is in the list; -1 when it is none of them. */
        final int current;

        State(int[] ids, String[] codes, String[] names, int on) {
            mIds = ids;
            mCodes = codes;
            mNames = names;
            int at = -1;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == on) {
                    at = i;
                }
            }
            current = at;
        }

        int count() {
            return mIds.length;
        }

        int id(int i) {
            return mIds[i];
        }

        String code(int i) {
            return mCodes[i];
        }

        String name(int i) {
            return mNames[i];
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper(),
            KeyboardLanguage::heard);
    /** HeliBoard gone: its button with it, until it answers again. */
    private static final IBinder.DeathRecipient GONE = () -> MAIN.post(KeyboardLanguage::gone);
    /** The trays on screen, told when the languages change. */
    private static final List<Runnable> TRAYS = new ArrayList<>();

    private static Context sApp;
    /** Ours, which HeliBoard answers on: made with the first tray. */
    private static Messenger sReply;
    /** HeliBoard's own, while it lives and has answered. */
    private static IBinder sHeli;
    private static State sState;

    private KeyboardLanguage() {
    }

    /** HeliBoard's languages, or null where it has said none. */
    static State state() {
        return sState;
    }

    /** A tray on screen, told when the languages change; the first one asks HeliBoard. */
    static void listen(Context ctx, Runnable changed) {
        sApp = ctx.getApplicationContext();
        boolean first = TRAYS.isEmpty();
        if (!TRAYS.contains(changed)) {
            TRAYS.add(changed);
        }
        if (first && sHeli == null) {
            ask(ctx);
        }
    }

    static void unlisten(Runnable changed) {
        TRAYS.remove(changed);
    }

    /**
     * The keyboard came up on a tray's screen: a HeliBoard that has not answered - started after
     * the tray asked, or started again - is asked now. Only while HeliBoard is the keyboard.
     */
    static void keyboardShown(Context ctx) {
        if (sHeli != null || TRAYS.isEmpty()) {
            return;
        }
        String current = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (current != null && current.startsWith(KeyboardWire.HELIBOARD + "/")) {
            ask(ctx);
        }
    }

    /** The next language: HeliBoard asked; with nothing from it, the keyboard settings. */
    static void next(Context ctx, int display) {
        if (sState == null) {
            settings(ctx, display);
            return;
        }
        send(ctx, new Intent(KeyboardWire.ACTION_LANGUAGE)
                .putExtra(KeyboardWire.EXTRA_NEXT, true));
    }

    /** One language picked from the tray's menu, by its hash. */
    static void pick(Context ctx, int id) {
        send(ctx, new Intent(KeyboardWire.ACTION_LANGUAGE)
                .putExtra(KeyboardWire.EXTRA_SUBTYPE, id));
    }

    /** Android's keyboard settings, on the tray's screen. */
    static void settings(Context ctx, int display) {
        TaskbarMenu.open(ctx, Settings.ACTION_INPUT_METHOD_SETTINGS, display);
    }

    private static void ask(Context ctx) {
        send(ctx, new Intent(KeyboardWire.ACTION_LANGUAGE));
    }

    /** To HeliBoard alone, with our Messenger: every word renews its way back. */
    private static void send(Context ctx, Intent intent) {
        if (sReply == null) {
            sReply = new Messenger(MAIN);
        }
        ctx.sendBroadcast(intent.setPackage(KeyboardWire.HELIBOARD)
                .putExtra(KeyboardWire.EXTRA_REPLY, sReply));
    }

    /** HeliBoard's answer - only HeliBoard's: its languages kept, its life watched. */
    private static boolean heard(Message message) {
        if (message.what != KeyboardWire.WHAT_LANGUAGES) {
            return false;
        }
        try {
            if (!fromHeliBoard(message.sendingUid)) {
                if (L.once("KeyboardLanguage.stranger")) {
                    L.w("keyboard language: an answer not from HeliBoard ignored (uid "
                            + message.sendingUid + ")");
                }
                return true;
            }
            if (!watch(message.replyTo)) {
                // Gone already, between its answer and now.
                gone();
                return true;
            }
            Bundle data = message.getData();
            int[] ids = data.getIntArray(KeyboardWire.KEY_IDS);
            String[] codes = data.getStringArray(KeyboardWire.KEY_CODES);
            String[] names = data.getStringArray(KeyboardWire.KEY_NAMES);
            boolean some = ids != null && codes != null && names != null
                    && codes.length == ids.length && names.length == ids.length;
            sState = some ? new State(ids, codes, names, data.getInt(KeyboardWire.KEY_CURRENT))
                    : null;
            if (sState != null && L.once("KeyboardLanguage.heard")) {
                L.i("keyboard language: HeliBoard has " + sState.count() + " language(s), on now "
                        + (sState.current >= 0 ? sState.code(sState.current) : "none of them"));
            }
            changed();
        } catch (Throwable t) {
            L.d("keyboard language: HeliBoard's answer not read (" + t + ")");
        }
        return true;
    }

    private static boolean fromHeliBoard(int uid) {
        String[] packages = sApp == null ? null : sApp.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if (KeyboardWire.HELIBOARD.equals(pkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * HeliBoard's own Messenger: its death heard, once for each HeliBoard. False when it is
     * dead already.
     */
    private static boolean watch(Messenger heli) {
        IBinder binder = heli == null ? null : heli.getBinder();
        if (binder == null || binder == sHeli) {
            return true;
        }
        if (sHeli != null) {
            sHeli.unlinkToDeath(GONE, 0);
            sHeli = null;
        }
        try {
            binder.linkToDeath(GONE, 0);
            sHeli = binder;
            return true;
        } catch (Throwable dead) {
            return false;
        }
    }

    private static void gone() {
        sHeli = null;
        sState = null;
        changed();
    }

    private static void changed() {
        for (Runnable tray : new ArrayList<>(TRAYS)) {
            tray.run();
        }
    }
}
