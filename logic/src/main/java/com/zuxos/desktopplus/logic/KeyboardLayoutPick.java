package com.zuxos.desktopplus.logic;

import java.util.List;
import java.util.Locale;

/**
 * Which of Android's physical keyboard layouts goes with a keyboard language, so a keyboard
 * plugged into the tablet types the language HeliBoard is on: Greek letters for {@code el}. A
 * layout whose locales have the language and the region comes first ({@code en-US} is
 * {@code en-Latn-US}; the script is not compared), then one with the language alone; among
 * equals a QWERTY one, so English is plain US rather than Dvorak or Colemak, else the first in
 * the system's own order.
 */
public final class KeyboardLayoutPick {

    /** Android's name for the common layout type ({@code KeyboardLayout.getLayoutType}). */
    private static final String QWERTY = "qwerty";

    private KeyboardLayoutPick() {
    }

    /**
     * The index of the layout for a language, or -1 for none.
     *
     * @param locales     each layout's locales, as BCP 47 tags
     * @param layoutTypes each layout's type, as Android names it; null where unknown
     * @param tag         the language, as a BCP 47 tag ({@code el}, {@code en-US})
     */
    public static int pick(List<String[]> locales, List<String> layoutTypes, String tag) {
        if (tag == null || tag.isEmpty()) {
            return -1;
        }
        Locale wanted = Locale.forLanguageTag(tag);
        String language = wanted.getLanguage();
        String region = wanted.getCountry();
        if (language.isEmpty()) {
            return -1;
        }
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < locales.size(); i++) {
            int match = 0;
            for (String each : locales.get(i)) {
                Locale locale = Locale.forLanguageTag(each);
                if (language.equals(locale.getLanguage())) {
                    match = Math.max(match,
                            !region.isEmpty() && region.equals(locale.getCountry()) ? 2 : 1);
                }
            }
            if (match == 0) {
                continue;
            }
            String type = i < layoutTypes.size() ? layoutTypes.get(i) : null;
            int score = match * 2 + (QWERTY.equals(type) ? 1 : 0);
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }
}
