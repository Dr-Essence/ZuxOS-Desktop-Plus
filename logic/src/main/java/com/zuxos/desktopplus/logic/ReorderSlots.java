package com.zuxos.desktopplus.logic;

/**
 * Where a drawer being rearranged draws its entries while one is dragged over it.
 *
 * <p>The grid's cells keep their laid-out places (slots); the preview only slides the entries
 * between where the dragged one was ({@code from}) and where it would land ({@code to}) over by
 * one, leaving the slot {@code to} empty - the gap. An entry dragged out of a folder was in no
 * slot ({@code from} -1): every entry from {@code to} on moves one slot further. Indexes and
 * slots are both positions in the drawer's order.
 */
public final class ReorderSlots {

    /** What {@link #drawnAt} answers for the gap. */
    public static final int GAP = -1;

    private ReorderSlots() {
    }

    /** The slot the preview draws the entry {@code index} in; {@code from} itself is the gap. */
    public static int placeOf(int from, int to, int index) {
        if (from < 0) {
            return index >= to ? index + 1 : index;
        }
        if (index == from) {
            return to;
        }
        if (from < to && index > from && index <= to) {
            return index - 1;
        }
        if (to < from && index >= to && index < from) {
            return index + 1;
        }
        return index;
    }

    /** The entry the preview draws in {@code slot}, or {@link #GAP} for the gap. */
    public static int drawnAt(int from, int to, int slot) {
        if (slot == to) {
            return GAP;
        }
        if (from < 0) {
            return slot > to ? slot - 1 : slot;
        }
        if (from < to && slot >= from && slot < to) {
            return slot + 1;
        }
        if (to < from && slot > to && slot <= from) {
            return slot - 1;
        }
        return slot;
    }
}
