package com.zuxos.desktopplus.logic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * A keyboard language's letters on a physical keyboard: each place on a QWERTY keyboard's three
 * letter rows types the key at the same place of the language's on-screen layout, so HeliBoard's
 * Greek layout makes A type α, as the Greek PC layout does. Only a layout of three rows that fit
 * the physical ones and has no Latin letter is used; a Latin one is left to the physical
 * keyboard's own layout, which matches its printed keys.
 */
public final class PhysicalKeys {

    /** No key: the physical key types what it always did. */
    public static final int NONE = -1;
    /** How many keys each physical row has: Q to ], A to ', Z to /. */
    private static final int[] ROW_KEYS = {12, 11, 10};

    private final int[][] mBase;
    private final int[][] mShifted;

    private PhysicalKeys(int[][] base, int[][] shifted) {
        mBase = base;
        mShifted = shifted;
    }

    /**
     * The keys of a layout, or null when it does not apply.
     *
     * @param rows each row's keys, left to right: a key is its code and its code with Shift,
     *             {@link #NONE} where it has none (Shift then gives a letter's capital)
     */
    public static PhysicalKeys of(List<List<int[]>> rows) {
        if (rows == null || rows.size() != ROW_KEYS.length) {
            return null;
        }
        int[][] base = new int[rows.size()][];
        int[][] shifted = new int[rows.size()][];
        boolean letters = false;
        for (int r = 0; r < rows.size(); r++) {
            List<int[]> row = rows.get(r);
            if (row.isEmpty() || row.size() > ROW_KEYS[r]) {
                return null;
            }
            base[r] = new int[row.size()];
            shifted[r] = new int[row.size()];
            for (int c = 0; c < row.size(); c++) {
                int code = row.get(c)[0];
                base[r][c] = code;
                shifted[r][c] = row.get(c)[1];
                if (code > 0 && Character.isLetter(code)) {
                    if (Character.UnicodeScript.of(code) == Character.UnicodeScript.LATIN) {
                        return null;
                    }
                    letters = true;
                }
            }
        }
        return letters ? new PhysicalKeys(base, shifted) : null;
    }

    /**
     * What the physical key at a place types, or {@link #NONE}. A letter is a capital with Shift
     * or Caps Lock, not both, as on any keyboard; any other key takes Shift alone.
     */
    public int code(int row, int column, boolean shift, boolean capsLock) {
        if (row < 0 || row >= mBase.length || column < 0 || column >= mBase[row].length) {
            return NONE;
        }
        int base = mBase[row][column];
        if (base <= 0) {
            return NONE;
        }
        int withShift = mShifted[row][column];
        if (Character.isLetter(base)) {
            if (shift == capsLock) {
                return base;
            }
            return withShift > 0 ? withShift : Character.toUpperCase(base);
        }
        return shift && withShift > 0 ? withShift : base;
    }

    /** Whether a code is an accent typed before its letter, as Greek's ´ and ¨ are. */
    public static boolean isAccent(int code) {
        return code > 0 && Character.getType(code) == Character.NON_SPACING_MARK;
    }

    /** A letter with the accent typed before it: ά for α and ´; the two as they are if none. */
    public static String join(int accent, int letter) {
        String both = new String(Character.toChars(letter)) + new String(Character.toChars(accent));
        return Normalizer.normalize(both, Normalizer.Form.NFC);
    }

    /**
     * The rows of HeliBoard's plain text layout: one key a line, its label first, rows apart by
     * an empty line. A label that is not one character - a key of its own such as
     * {@code !icon/...}, or a whole word - keeps its place and types nothing of ours.
     */
    public static List<List<int[]>> parseText(String text) {
        List<List<int[]>> rows = new ArrayList<>();
        List<int[]> row = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!row.isEmpty()) {
                    rows.add(row);
                    row = new ArrayList<>();
                }
                continue;
            }
            String label = trimmed.split("\\s+", 2)[0];
            int bar = label.indexOf('|');
            if (bar >= 0) {
                label = label.substring(bar + 1);
            }
            if (label.startsWith("\\")) {
                label = label.substring(1);
            }
            row.add(new int[] {single(label), NONE});
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    /** The one character a label types, or {@link #NONE}. */
    public static int single(String label) {
        if (label == null || label.isEmpty() || label.startsWith("!") || label.startsWith("$")
                || label.codePointCount(0, label.length()) != 1) {
            return NONE;
        }
        return label.codePointAt(0);
    }
}
