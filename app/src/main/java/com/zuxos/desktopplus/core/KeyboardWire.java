package com.zuxos.desktopplus.core;

/**
 * What ZUX Home and HeliBoard say to each other about the keyboard's language. HeliBoard keeps
 * its languages to itself - Android's keyboard list sees one, and never which is on - so it says
 * them itself, and only a keyboard may change its own language, so the monitor's tray asks it.
 *
 * <p>ZUX Home asks with a broadcast to HeliBoard, which registers with
 * {@link FullScreenWire#SENDER}: ZUX Home holds it and no app from a store can. Each carries ZUX
 * Home's {@link android.os.Messenger} ({@link #EXTRA_REPLY}), and HeliBoard answers on it - now,
 * and at each change - a binder call, which no package visibility stands in the way of. The
 * answer carries HeliBoard's own Messenger as its {@code replyTo}, so ZUX Home sees it go if
 * HeliBoard does. A language is named by its subtype's hash, the same number in both processes.
 */
public final class KeyboardWire {

    public static final String HELIBOARD = "helium314.keyboard";

    /**
     * ZUX Home to HeliBoard: {@link #EXTRA_NEXT}, the language {@link #EXTRA_SUBTYPE}, or with
     * neither only an ask for its languages. Always with {@link #EXTRA_REPLY}.
     */
    public static final String ACTION_LANGUAGE = "com.zuxos.desktopplus.KEYBOARD_LANGUAGE";

    public static final String EXTRA_NEXT = "next";
    public static final String EXTRA_SUBTYPE = "subtype";
    public static final String EXTRA_REPLY = "reply";

    /**
     * HeliBoard's answer: its languages' hashes ({@link #KEY_IDS}), codes and names, in its own
     * order, and the hash of the one on ({@link #KEY_CURRENT}). None while it is not the
     * keyboard.
     */
    public static final int WHAT_LANGUAGES = 1;
    public static final String KEY_IDS = "ids";
    public static final String KEY_CODES = "codes";
    public static final String KEY_NAMES = "names";
    public static final String KEY_CURRENT = "current";

    private KeyboardWire() {
    }
}
