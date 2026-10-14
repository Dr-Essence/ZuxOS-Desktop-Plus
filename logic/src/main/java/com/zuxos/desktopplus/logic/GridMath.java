package com.zuxos.desktopplus.logic;

/**
 * The home screen's grid when it is made finer or coarser: where items go, how big a widget
 * stays, and how far icons shrink to fit their cells.
 *
 * <p>Places scale by whole cells, rounded down: a cell's start and its end each scale on their
 * own, so two items that did not overlap before never overlap after, and nothing runs past the
 * new grid. A launcher drops an item whose cells are taken; this keeps every item.
 */
public final class GridMath {

    private GridMath() {
    }

    /** The first cell of an item starting at {@code cell} of {@code from} cells, in {@code to}. */
    public static int start(int cell, int from, int to) {
        if (from <= 0 || to <= 0) {
            return cell;
        }
        return Math.max(0, Math.min(to - 1, cell * to / from));
    }

    /**
     * How many of {@code to} cells an item starting at {@code cell} and {@code span} cells long in
     * {@code from} takes: from its scaled start to its scaled end, at least one.
     */
    public static int span(int cell, int span, int from, int to) {
        if (from <= 0 || to <= 0) {
            return span;
        }
        int first = start(cell, from, to);
        int end = Math.min(to, (cell + span) * to / from);
        return Math.max(1, end - first);
    }

    /**
     * A widget's span on a grid of {@code to} cells, made as large on screen as {@code span} was
     * on {@code base} cells: rounded, at least one, at most the grid.
     */
    public static int widgetSpan(int span, int base, int to) {
        if (base <= 0 || to <= 0) {
            return span;
        }
        return Math.max(1, Math.min(to, Math.round(span * (float) to / base)));
    }

    /**
     * How far an icon and its name are shrunk to fit a cell: 1 when they fit. {@code content} is
     * the height they take (icon, the room under it and the name), {@code icon} the icon's width,
     * {@code cellWidth} and {@code cellHeight} the cell's; {@code widthShare} is how much of the
     * cell's width an icon may take, leaving room between neighbours.
     */
    public static float fit(int content, int icon, int cellWidth, int cellHeight,
            float widthShare) {
        float scale = 1f;
        if (content > 0 && cellHeight > 0 && content > cellHeight) {
            scale = cellHeight / (float) content;
        }
        if (icon > 0 && cellWidth > 0 && icon > cellWidth * widthShare) {
            scale = Math.min(scale, cellWidth * widthShare / icon);
        }
        return Math.max(0.1f, scale);
    }
}
