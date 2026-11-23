package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** A physical keyboard types a language's on-screen layout, place for place. */
public class PhysicalKeysTest {

    private static final int NONE = PhysicalKeys.NONE;

    /** HeliBoard's Greek layout, as its greek.json has it. */
    private static List<List<int[]>> greek() {
        List<List<int[]>> rows = new ArrayList<>();
        List<int[]> top = new ArrayList<>();
        top.add(new int[] {';', ':'});
        for (char c : "ςερτυθιοπ".toCharArray()) {
            top.add(new int[] {c, NONE});
        }
        rows.add(top);
        List<int[]> middle = new ArrayList<>();
        for (char c : "ασδφγηξκλ".toCharArray()) {
            middle.add(new int[] {c, NONE});
        }
        middle.add(new int[] {0x301, 0x308});
        rows.add(middle);
        List<int[]> bottom = new ArrayList<>();
        for (char c : "ζχψωβνμ".toCharArray()) {
            bottom.add(new int[] {c, NONE});
        }
        rows.add(bottom);
        return rows;
    }

    @Test
    public void greekLettersAtTheirPlaces() {
        PhysicalKeys keys = PhysicalKeys.of(greek());
        assertNotNull(keys);
        assertEquals('α', keys.code(1, 0, false, false));
        assertEquals('ς', keys.code(0, 1, false, false));
        assertEquals('μ', keys.code(2, 6, false, false));
        assertEquals(';', keys.code(0, 0, false, false));
    }

    @Test
    public void shiftAndCapsLockMakeCapitals() {
        PhysicalKeys keys = PhysicalKeys.of(greek());
        assertEquals('Α', keys.code(1, 0, true, false));
        assertEquals('Α', keys.code(1, 0, false, true));
        assertEquals('α', keys.code(1, 0, true, true));
        // The final sigma's capital is the sigma's.
        assertEquals('Σ', keys.code(0, 1, true, false));
    }

    @Test
    public void otherKeysTakeShiftAlone() {
        PhysicalKeys keys = PhysicalKeys.of(greek());
        assertEquals(':', keys.code(0, 0, true, false));
        assertEquals(';', keys.code(0, 0, false, true));
        assertEquals(0x308, keys.code(1, 9, true, false));
        assertEquals(0x301, keys.code(1, 9, false, false));
    }

    @Test
    public void placesBeyondTheLayoutTypeTheirOwn() {
        PhysicalKeys keys = PhysicalKeys.of(greek());
        assertEquals(NONE, keys.code(2, 7, false, false));
        assertEquals(NONE, keys.code(0, 10, false, false));
        assertEquals(NONE, keys.code(3, 0, false, false));
    }

    @Test
    public void latinLayoutsAreLeftToTheKeyboard() {
        assertNull(PhysicalKeys.of(PhysicalKeys.parseText("q\nw\ne\n\na\ns\n\nz\nx\n")));
    }

    @Test
    public void layoutsThatDoNotFitAreLeftAlone() {
        List<List<int[]>> rows = greek();
        rows.remove(2);
        assertNull(PhysicalKeys.of(rows));
        rows = greek();
        for (int i = 0; i < 3; i++) {
            rows.get(0).add(new int[] {'φ', NONE});
        }
        assertNull(PhysicalKeys.of(rows));
    }

    @Test
    public void textLayoutsKeepEveryPlace() {
        List<List<int[]>> rows = PhysicalKeys.parseText(
                "й\nц ц́\n!icon/x|!code/y\n\nф\nы\n\nя\n");
        assertEquals(3, rows.size());
        assertEquals(3, rows.get(0).size());
        assertEquals('ц', rows.get(0).get(1)[0]);
        assertEquals(NONE, rows.get(0).get(2)[0]);
        PhysicalKeys keys = PhysicalKeys.of(rows);
        assertNotNull(keys);
        assertEquals('Й', keys.code(0, 0, true, false));
        assertEquals(NONE, keys.code(0, 2, false, false));
    }

    @Test
    public void anAccentJoinsTheNextLetter() {
        assertTrue(PhysicalKeys.isAccent(0x301));
        assertFalse(PhysicalKeys.isAccent('α'));
        assertEquals("ά", PhysicalKeys.join(0x301, 'α'));
        assertEquals("ϊ", PhysicalKeys.join(0x308, 'ι'));
    }
}
