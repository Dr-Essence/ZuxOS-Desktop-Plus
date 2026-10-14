package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zuxos.desktopplus.model.Item;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The drawer rearranged by drag, as ZUI's home screen is: moves, folders made by dropping one
 * app on another, apps put into folders and taken out of them, and an app in the drawer once.
 */
public class DrawerArrangeTest {

    private static Item app(String name) {
        return Item.app("p." + name, "C", 0, name);
    }

    private static List<String> order(Item... items) {
        List<String> keys = new ArrayList<>();
        for (Item item : items) {
            keys.add(item.key());
        }
        return keys;
    }

    @Test
    public void moveCountsTheIndexBeforeTheMove() {
        Item a = app("a");
        Item b = app("b");
        Item c = app("c");
        Item d = app("d");
        List<String> order = order(a, b, c, d);

        assertTrue(DrawerArrange.move(order, a.key(), 2));
        assertEquals(order(b, c, a, d), order);
        assertFalse(DrawerArrange.move(order, "nobody", 0));
    }

    @Test
    public void mergeMakesAFolderWhereTheTargetWas() {
        Item a = app("a");
        Item b = app("b");
        Item c = app("c");
        List<String> order = order(a, b, c);
        List<Item> folders = new ArrayList<>();

        Item folder = DrawerArrange.merge(order, folders, a, c, "Folder");

        assertNotNull(folder);
        assertEquals(Arrays.asList(b.key(), folder.key()), order);
        assertEquals(1, folders.size());
        assertEquals(c.key(), folder.children.get(0).key());
        assertEquals(a.key(), folder.children.get(1).key());
    }

    @Test
    public void mergeRefusesFoldersAndTheSameApp() {
        Item a = app("a");
        Item folder = Item.folder("F");
        List<String> order = order(a, folder);
        List<Item> folders = new ArrayList<>(Arrays.asList(folder));

        assertNull(DrawerArrange.merge(order, folders, a, a, "Folder"));
        assertNull(DrawerArrange.merge(order, folders, folder, a, "Folder"));
        assertNull(DrawerArrange.merge(order, folders, a, folder, "Folder"));
    }

    @Test
    public void fileIntoLeavesTheOrderAndAnyOtherFolder() {
        Item a = app("a");
        Item b = app("b");
        Item c = app("c");
        Item x = app("x");
        Item first = Item.folder("First");
        first.children.addAll(Arrays.asList(a, b, x));
        Item second = Item.folder("Second");
        second.children.add(app("y"));
        second.children.add(app("z"));
        List<String> order = order(first, c, second);
        List<Item> folders = new ArrayList<>(Arrays.asList(first, second));

        assertTrue(DrawerArrange.fileInto(order, folders, second, c));
        assertEquals(order(first, second), order);
        assertEquals(3, second.children.size());

        assertTrue(DrawerArrange.fileInto(order, folders, second, x));
        assertEquals(2, first.children.size());
        assertFalse(DrawerArrange.fileInto(order, folders, second, x));
    }

    @Test
    public void carryAfterBringsTheSelectionToTheDraggedApp() {
        Item a = app("a");
        Item b = app("b");
        Item c = app("c");
        Item d = app("d");
        Item e = app("e");
        List<String> order = order(a, b, c, d, e);

        // d dragged, a and e chosen with it.
        assertTrue(DrawerArrange.carryAfter(order, d.key(), order(a, d, e)));
        assertEquals(order(b, c, d, a, e), order);

        assertFalse(DrawerArrange.carryAfter(order, d.key(), order(d, app("x"))));
        assertFalse(DrawerArrange.carryAfter(order, app("y").key(), order(a)));
    }

    @Test
    public void fileIntoAtPlacesTheAppWhereItWasLetGo() {
        Item a = app("a");
        Item b = app("b");
        Item c = app("c");
        Item d = app("d");
        Item folder = Item.folder("F");
        folder.children.addAll(Arrays.asList(a, b, c));
        List<String> order = order(folder, d);
        List<Item> folders = new ArrayList<>(Arrays.asList(folder));

        // From the drawer, between a and b.
        assertTrue(DrawerArrange.fileIntoAt(order, folders, folder, d, 1));
        assertEquals(order(folder), order);
        assertEquals(order(a, d, b, c), order(folder.children.toArray(new Item[0])));

        // Already in it: moved to the end, past the last place.
        assertTrue(DrawerArrange.fileIntoAt(order, folders, folder, a, 9));
        assertEquals(order(d, b, c, a), order(folder.children.toArray(new Item[0])));

        assertFalse(DrawerArrange.fileIntoAt(order, folders, folder, Item.folder("G"), 0));
    }

    @Test
    public void aFolderLeftWithOneAppGivesItsPlaceToIt() {
        Item a = app("a");
        Item b = app("b");
        Item c = app("c");
        Item folder = Item.folder("F");
        folder.children.addAll(Arrays.asList(a, b));
        List<String> order = order(c, folder);
        List<Item> folders = new ArrayList<>(Arrays.asList(folder));

        assertTrue(DrawerArrange.takeOut(order, folders, a.key(), 0));

        assertEquals(order(a, c, b), order);
        assertTrue(folders.isEmpty());
    }

    @Test
    public void takeOutToAPlaceAfterTheFolder() {
        Item a = app("a");
        Item b = app("b");
        Item c = app("c");
        Item d = app("d");
        Item folder = Item.folder("F");
        folder.children.addAll(Arrays.asList(a, b, c));
        List<String> order = order(folder, d);
        List<Item> folders = new ArrayList<>(Arrays.asList(folder));

        assertTrue(DrawerArrange.takeOut(order, folders, b.key(), 2));

        assertEquals(Arrays.asList(folder.key(), d.key(), b.key()), order);
        assertEquals(2, folder.children.size());
        assertFalse(DrawerArrange.takeOut(order, folders, d.key(), 0));
    }

    @Test
    public void dedupeKeepsTheFirstCopyOfEachApp() {
        Item a = app("a");
        Item b = app("b");
        Item c = app("c");
        Item first = Item.folder("First");
        first.children.addAll(Arrays.asList(a, b, app("a"), b));
        Item second = Item.folder("Second");
        second.children.addAll(Arrays.asList(app("b"), c, app("c")));
        List<Item> folders = new ArrayList<>(Arrays.asList(first, second));

        assertEquals(4, DrawerArrange.dedupe(folders));

        assertEquals(order(a, b), order(first.children.toArray(new Item[0])));
        assertEquals(order(c), order(second.children.toArray(new Item[0])));
        assertEquals(0, DrawerArrange.dedupe(folders));
    }

    @Test
    public void appOfReadsAnAppKeyOnly() {
        Item app = DrawerArrange.appOf("com.x/com.x.Main#10", "X");
        assertNotNull(app);
        assertEquals("com.x", app.pkg);
        assertEquals("com.x.Main", app.cls);
        assertEquals(10, app.userSerial);
        assertEquals("com.x/com.x.Main#10", app.key());
        assertNull(DrawerArrange.appOf("folder:abc", null));
        assertNull(DrawerArrange.appOf("com.x//shortcut#0", null));
        assertNull(DrawerArrange.appOf("nonsense", null));
    }
}
