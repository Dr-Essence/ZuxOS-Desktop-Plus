package com.zuxos.desktopplus.core;

/**
 * What ZUX Home asks of HeliBoard about its language. Only a keyboard may change its own
 * language, so the monitor's tray asks it: the next language, or one picked from the tray's
 * menu, by its subtype's hash - the same number in both processes, Android's own id for a
 * keyboard language. HeliBoard registers with {@link FullScreenWire#SENDER}, which ZUX Home holds
 * and no app from a store can.
 */
public final class KeyboardWire {

    public static final String HELIBOARD = "helium314.keyboard";

    /** ZUX Home to HeliBoard: {@link #EXTRA_NEXT}, or the language {@link #EXTRA_SUBTYPE}. */
    public static final String ACTION_LANGUAGE = "com.zuxos.desktopplus.KEYBOARD_LANGUAGE";

    public static final String EXTRA_NEXT = "next";
    public static final String EXTRA_SUBTYPE = "subtype";

    private KeyboardWire() {
    }
}
