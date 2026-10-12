package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The quick panel's tiles from a saved edit. */
public class TileOrderMathTest {

    private static final List<String> KNOWN =
            Arrays.asList("wifi", "bluetooth", "torch", "rotation", "flight", "eye");

    private static List<String> list(String... ids) {
        return Arrays.asList(ids);
    }

    @Test
    public void noEditIsTheDefaultOrder() {
        assertEquals(KNOWN, TileOrderMath.shown(list(), list(), KNOWN));
        assertEquals(list(), TileOrderMath.hidden(list(), KNOWN));
    }

    @Test
    public void savedOrderComesFirst() {
        assertEquals(list("torch", "wifi", "bluetooth", "rotation", "flight", "eye"),
                TileOrderMath.shown(list("torch", "wifi"), list(), KNOWN));
    }

    @Test
    public void hiddenTilesAreLeftOut() {
        assertEquals(list("wifi", "torch", "rotation", "eye"),
                TileOrderMath.shown(list(), list("bluetooth", "flight"), KNOWN));
        assertEquals(list("bluetooth", "flight"),
                TileOrderMath.hidden(list("bluetooth", "flight"), KNOWN));
    }

    @Test
    public void unknownIdsAreDroppedAndNewTilesGoAtTheEnd() {
        // "nfc" is gone in this build; "eye" is new to the saved edit.
        List<String> known = list("wifi", "torch", "eye");
        assertEquals(list("torch", "wifi", "eye"),
                TileOrderMath.shown(list("nfc", "torch", "wifi"), list("nfc"), known));
        assertEquals(list(), TileOrderMath.hidden(list("nfc"), known));
    }

    @Test
    public void duplicatesCountOnce() {
        assertEquals(list("torch", "wifi", "bluetooth", "rotation", "flight", "eye"),
                TileOrderMath.shown(list("torch", "torch", "wifi"), list(), KNOWN));
        assertEquals(list("eye"), TileOrderMath.hidden(list("eye", "eye"), KNOWN));
    }

    @Test
    public void everyTileHiddenLeavesNoneShown() {
        assertEquals(Collections.emptyList(), TileOrderMath.shown(KNOWN, KNOWN, KNOWN));
    }

    @Test
    public void aTileBothOrderedAndHiddenIsHidden() {
        assertEquals(list("bluetooth", "torch", "rotation", "flight", "eye"),
                TileOrderMath.shown(list("wifi"), list("wifi"), KNOWN));
    }
}
