package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zuxos.desktopplus.model.Item;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** A stored app follows its app's launcher entry when an update moves it. */
public class MovedEntryTest {

    private static final String OLD =
            "org.fdroid.fdroid/org.fdroid.fdroid.views.main.MainActivity#0";
    private static final String NOW = "org.fdroid.fdroid/org.fdroid.IconActivity#0";

    @Test
    public void aMovedEntryGetsTheOneItHasNow() {
        List<String> apps = Arrays.asList("com.a/com.a.Main#0", NOW, "org.fdroid.fdroid.x/X#0");
        assertEquals(NOW, MovedEntry.replacement(OLD, apps));
    }

    @Test
    public void anEntryStillThereStays() {
        assertNull(MovedEntry.replacement(NOW, Collections.singletonList(NOW)));
    }

    @Test
    public void anAppWithNoEntryIsLeftAlone() {
        // Being updated, or gone: not this one's to decide.
        assertNull(MovedEntry.replacement(OLD, Collections.singletonList("com.a/com.a.Main#0")));
    }

    @Test
    public void onlyTheSamePackageAndUser() {
        List<String> apps = Arrays.asList("org.fdroid.fdroid.x/X#0",
                "org.fdroid.fdroid/org.fdroid.IconActivity#10");
        assertNull(MovedEntry.replacement(OLD, apps));
    }

    @Test
    public void keysThatAreNotAppsAreLeftAlone() {
        List<String> apps = Collections.singletonList(NOW);
        assertNull(MovedEntry.replacement("folder:3919230c", apps));
        assertNull(MovedEntry.replacement(null, apps));
        assertNull(MovedEntry.className("folder:3919230c"));
    }

    @Test
    public void aStoredAppFollows() {
        Item item = Item.app("org.fdroid.fdroid", "org.fdroid.fdroid.views.main.MainActivity", 0,
                "F-Droid");
        assertTrue(MovedEntry.follow(item, Collections.singletonList(NOW)));
        assertEquals("org.fdroid.IconActivity", item.cls);
        assertEquals(NOW, item.key());
        assertFalse(MovedEntry.follow(item, Collections.singletonList(NOW)));
    }

    @Test
    public void theClassOfAKey() {
        assertEquals("org.fdroid.IconActivity", MovedEntry.className(NOW));
    }
}
