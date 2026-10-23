package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The rearranged drawer's preview: every entry drawn in one slot, the gap where the dragged one
 * lands, and the slot under the finger read back to the entry drawn there - never to the entry
 * whose laid-out cell it is, which the preview has moved away: every drop made a folder.
 */
public class ReorderSlotsTest {

    @Test
    public void movingForwardShiftsTheOthersBack() {
        // a b c d e, b (1) dragged to 3: a c d _ e
        assertEquals(0, ReorderSlots.placeOf(1, 3, 0));
        assertEquals(1, ReorderSlots.placeOf(1, 3, 2));
        assertEquals(2, ReorderSlots.placeOf(1, 3, 3));
        assertEquals(4, ReorderSlots.placeOf(1, 3, 4));
        assertEquals(2, ReorderSlots.drawnAt(1, 3, 1));
        assertEquals(3, ReorderSlots.drawnAt(1, 3, 2));
        assertEquals(ReorderSlots.GAP, ReorderSlots.drawnAt(1, 3, 3));
        assertEquals(4, ReorderSlots.drawnAt(1, 3, 4));
    }

    @Test
    public void movingBackShiftsTheOthersOn() {
        // a b c d e, d (3) dragged to 1: a _ b c e
        assertEquals(2, ReorderSlots.placeOf(3, 1, 1));
        assertEquals(3, ReorderSlots.placeOf(3, 1, 2));
        assertEquals(ReorderSlots.GAP, ReorderSlots.drawnAt(3, 1, 1));
        assertEquals(1, ReorderSlots.drawnAt(3, 1, 2));
        assertEquals(2, ReorderSlots.drawnAt(3, 1, 3));
        assertEquals(4, ReorderSlots.drawnAt(3, 1, 4));
    }

    @Test
    public void theGapIsWhereTheDraggedOneWasUntilItMoves() {
        assertEquals(ReorderSlots.GAP, ReorderSlots.drawnAt(2, 2, 2));
        assertEquals(1, ReorderSlots.drawnAt(2, 2, 1));
        assertEquals(3, ReorderSlots.drawnAt(2, 2, 3));
    }

    @Test
    public void anAppFromAFolderOpensAGapOfItsOwn() {
        // a b c, an app from a folder over slot 1: a _ b c
        assertEquals(0, ReorderSlots.placeOf(-1, 1, 0));
        assertEquals(2, ReorderSlots.placeOf(-1, 1, 1));
        assertEquals(3, ReorderSlots.placeOf(-1, 1, 2));
        assertEquals(0, ReorderSlots.drawnAt(-1, 1, 0));
        assertEquals(ReorderSlots.GAP, ReorderSlots.drawnAt(-1, 1, 1));
        assertEquals(1, ReorderSlots.drawnAt(-1, 1, 2));
        assertEquals(2, ReorderSlots.drawnAt(-1, 1, 3));
    }

    @Test
    public void everyEntryIsReadBackFromWhereItIsDrawn() {
        int size = 7;
        for (int from = -1; from < size; from++) {
            for (int to = 0; to < size; to++) {
                for (int index = 0; index < size; index++) {
                    if (index == from) {
                        continue;
                    }
                    int slot = ReorderSlots.placeOf(from, to, index);
                    assertEquals("from " + from + " to " + to + " index " + index, index,
                            ReorderSlots.drawnAt(from, to, slot));
                }
            }
        }
    }
}
