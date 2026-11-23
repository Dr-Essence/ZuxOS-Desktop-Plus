package com.zuxos.desktopplus.logic;

import java.util.Locale;

/**
 * A keyboard language's short code for the monitor's tray, as DeX's taskbar shows it: the
 * language alone, in capitals - "EN" for {@code en-US}, "SR" for {@code sr-Latn}.
 */
public final class LanguageCode {

    private LanguageCode() {
    }

    /**
     * The code of a keyboard language: from its BCP 47 tag, or, where it has none, from its
     * older locale string ({@code en_US}); empty when it has neither.
     */
    public static String of(String tag, String locale) {
        String from = tag != null && !tag.isEmpty() ? tag : locale;
        if (from == null || from.isEmpty()) {
            return "";
        }
        return from.split("[-_]", 2)[0].toUpperCase(Locale.ROOT);
    }
}
