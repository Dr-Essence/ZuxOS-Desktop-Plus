package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The tray shows a keyboard language as its language alone, in capitals. */
public class LanguageCodeTest {

    @Test
    public void tagGivesItsLanguage() {
        assertEquals("EN", LanguageCode.of("en-US", "en_US"));
        assertEquals("SR", LanguageCode.of("sr-Latn", null));
        assertEquals("EL", LanguageCode.of("el", ""));
    }

    @Test
    public void localeStandsInForAMissingTag() {
        assertEquals("EN", LanguageCode.of("", "en_GB"));
        assertEquals("DE", LanguageCode.of(null, "de"));
    }

    @Test
    public void neitherGivesNothing() {
        assertEquals("", LanguageCode.of(null, null));
        assertEquals("", LanguageCode.of("", ""));
    }

    @Test
    public void capitalsDoNotFollowTheDevicesLocale() {
        // Turkish capitals would make "id" into "İD"; the code is the same everywhere.
        assertEquals("ID", LanguageCode.of("id-ID", null));
    }
}
