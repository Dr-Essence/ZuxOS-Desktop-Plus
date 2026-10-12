package com.zuxos.desktopplus.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * An icon shown elsewhere for lack of room keeps its own place: a rotated tablet or a smaller
 * monitor must not rearrange the desktop for good.
 */
public class ItemHomeTest {

    private static Item at(int x, int y) {
        Item item = Item.app("p", "c", 0, "A");
        item.x = x;
        item.y = y;
        return item;
    }

    @Test
    public void shownElsewhereSavesItsOwnPlace() throws Exception {
        Item item = at(12, 3);
        item.showAt(2, 1);

        assertTrue(item.displaced());
        assertEquals(2, item.x);
        assertEquals(1, item.y);
        assertEquals(12, item.toJson().getInt("x"));
        assertEquals(3, item.toJson().getInt("y"));
    }

    @Test
    public void goesBackWhenThereIsRoom() {
        Item item = at(12, 3);
        item.showAt(2, 1);
        item.goHome();

        assertFalse(item.displaced());
        assertEquals(12, item.x);
        assertEquals(3, item.y);
    }

    @Test
    public void movedByTheUserMeanwhileStaysWhereItWasPut() throws Exception {
        Item item = at(12, 3);
        item.showAt(2, 1);
        item.x = 4;
        item.y = 0;

        assertFalse(item.displaced());
        assertEquals(4, item.toJson().getInt("x"));
        item.goHome();
        assertEquals(4, item.x);
        assertEquals(0, item.y);
    }

    @Test
    public void shownElsewhereTwiceKeepsTheFirstPlace() {
        Item item = at(12, 3);
        item.showAt(2, 1);
        item.showAt(5, 0);

        assertEquals(12, item.homeX());
        assertEquals(3, item.homeY());
        item.goHome();
        assertEquals(12, item.x);
    }

    @Test
    public void putThereOnPurposeStaysEvenOnTheSameCell() throws Exception {
        Item item = at(12, 3);
        item.showAt(2, 1);
        item.x = 2;
        item.y = 1;
        item.stay();

        assertFalse(item.displaced());
        assertEquals(2, item.toJson().getInt("x"));
    }

    @Test
    public void neverDisplacedIsItsOwnHome() {
        Item item = at(1, 2);

        assertFalse(item.displaced());
        assertEquals(1, item.homeX());
        item.goHome();
        assertEquals(1, item.x);
        assertEquals(2, item.y);
    }
}
