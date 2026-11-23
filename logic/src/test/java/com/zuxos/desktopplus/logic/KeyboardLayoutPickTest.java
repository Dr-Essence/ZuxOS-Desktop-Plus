package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** A physical keyboard takes the layout of the language HeliBoard is on. */
public class KeyboardLayoutPickTest {

    /** Part of Android's own list, in its own order, as its InputDevices package declares it. */
    private static final List<String[]> LOCALES = Arrays.asList(
            new String[]{"en-Latn-US"},                 // 0 English (US), international
            new String[]{"en-Latn-US"},                 // 1 English (US), Dvorak
            new String[]{"en-Latn", "en-Latn-US"},      // 2 English (US)
            new String[]{"en-Latn-US"},                 // 3 English (US), Colemak
            new String[]{"el-Grek"},                    // 4 Greek
            new String[]{"de-Latn"});                   // 5 German
    private static final List<String> TYPES = Arrays.asList(
            "extended", "dvorak", "qwerty", "colemak", "undefined", "qwertz");

    @Test
    public void greekGoesToTheGreekLayout() {
        assertEquals(4, KeyboardLayoutPick.pick(LOCALES, TYPES, "el"));
    }

    @Test
    public void usEnglishIsPlainQwertyOverTheOthers() {
        assertEquals(2, KeyboardLayoutPick.pick(LOCALES, TYPES, "en-US"));
    }

    @Test
    public void ukEnglishTakesTheUkLayoutWhenThereIsOne() {
        List<String[]> withUk = Arrays.asList(LOCALES.get(0), LOCALES.get(2),
                new String[]{"en-Latn-GB"});
        List<String> types = Arrays.asList("extended", "qwerty", "qwerty");
        assertEquals(2, KeyboardLayoutPick.pick(withUk, types, "en-GB"));
        // With none, English by its language: the plain one.
        assertEquals(2, KeyboardLayoutPick.pick(LOCALES, TYPES, "en-GB"));
    }

    @Test
    public void aLanguageWithNoLayoutGetsNone() {
        assertEquals(-1, KeyboardLayoutPick.pick(LOCALES, TYPES, "ja-JP"));
    }

    @Test
    public void noTagGetsNone() {
        assertEquals(-1, KeyboardLayoutPick.pick(LOCALES, TYPES, ""));
        assertEquals(-1, KeyboardLayoutPick.pick(LOCALES, TYPES, null));
    }

    @Test
    public void unknownTypesStillMatchByLanguage() {
        // No type known as QWERTY: the first English one in the system's order.
        assertEquals(0, KeyboardLayoutPick.pick(LOCALES, Arrays.asList("x", null), "en"));
    }
}
