package com.zuxos.desktopplus.hook.drawer;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.taskbar.BarEdge;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;
import com.zuxos.desktopplus.logic.DrawerArrange;
import com.zuxos.desktopplus.logic.ReorderSlots;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rearranging ZUI's own app drawer by dragging an app within it, the way ZUI rearranges its home
 * screen.
 *
 * <p>While a held app stays a moment over another entry, the icons between where it was and
 * there slide over by one place on a spring, opening the gap it will drop into; held near the
 * top or the bottom of the grid, the grid scrolls. Held still over the middle of another app it
 * folds into it: that app swells, and let go there the two make a folder; over one of our
 * folders, the app goes in it. What it is over is what is drawn under the finger, not what was
 * laid out there ({@link ReorderSlots}): let go in the gap, it moves. An app dragged out of one
 * of our folders opens a gap of its own and, let go, leaves the folder for it. Let go over the
 * grid and the new order is kept - the same order the module's own drawer uses - and ZUI's list
 * re-sorts from where the icons are drawn, so nothing jumps.
 *
 * <p>Only the drawer's views and their geometry are used: the grid's own classes are minified on
 * this firmware, and none of their methods can be counted on by name.
 */
final class DrawerReorder {

    /** How close to the grid's top or bottom edge the drag has to be to scroll it. */
    private static final int EDGE_DP = 56;
    private static final int SCROLL_STEP_DP = 14;
    /** How long a drag rests over the middle of an app before it folds into it, as ZUI's home. */
    private static final long FOLD_MS = 350L;
    /**
     * How long a drag stays over an entry, off its middle, before the gap moves there - as
     * Launcher3's reorder alarm: passing over an entry on the way to another leaves it be.
     */
    private static final long REORDER_MS = 200L;
    /** The middle of a cell that folds, as a part of its icon's size. */
    private static final float FOLD_SPOT = 0.55f;
    /** How much the app folded into swells. */
    private static final float FOLD_SCALE = 1.18f;
    /** How long a drag rests on one of our folders before it opens for it, as ZUI's home. */
    private static final long SPRING_MS = 800L;

    /** Opens one of our folders under a resting drag, ZUI's way; false where it cannot. */
    interface Spring {
        boolean open(View cell);
    }

    private final ViewGroup mRoot;
    private final ViewGroup mGrid;
    private final View mSheet;
    private final String mKey;
    private final int mDisplay;
    private List<String> mOrder;
    /** Where the dragged entry is in the drawer's order; -1 for an app from one of our folders. */
    private int mFrom;
    private int mTo;
    /** Where each icon was last sent, so an unchanged target sends nothing again. */
    private final Map<View, float[]> mSent = new HashMap<>();
    /** The cell the drag rests over the middle of, and the one it has folded into. */
    private View mResting;
    private View mFolding;
    private final Runnable mFold = this::fold;
    /** The slot the gap is about to move to, or -1. */
    private int mPending = -1;
    private final Runnable mReorder = this::reorder;
    private String mOutcome;
    /** Opens a folder the drag rests on; null where folders do not open under a drag. */
    private Spring mSpring;
    private final Runnable mSpringOpen = this::springOpen;
    /** The folder cell opened, not opened again until the drag has left it. */
    private View mSprung;
    /** Apps chosen with the dragged one ({@link DrawerSelect}), going where it goes. */
    private List<String> mCarried = new ArrayList<>();
    /** Put back by {@link #cancel}: the dragged entry showing in its place. */
    private boolean mAtRest;

    private DrawerReorder(ViewGroup root, ViewGroup grid, View sheet, String key, int display) {
        mRoot = root;
        mGrid = grid;
        mSheet = sheet;
        mKey = key;
        mDisplay = display;
        mOrder = NativeDrawerHooks.shownOrder();
        mFrom = mOrder.indexOf(key);
        mTo = mFrom;
    }

    /** For a drag picked up from {@code icon} in the stock drawer; null when it cannot be done. */
    static DrawerReorder begin(View icon) {
        String key = NativeDrawerHooks.keyOfIcon(icon);
        if (key == null || !(icon.getParent() instanceof ViewGroup)
                || !(icon.getRootView() instanceof ViewGroup)) {
            return null;
        }
        ViewGroup grid = (ViewGroup) icon.getParent();
        ViewGroup root = (ViewGroup) icon.getRootView();
        List<View> sheets = Reflect.findByIdNames(root, "bottom_sheet_background");
        DrawerReorder reorder = new DrawerReorder(root, grid,
                sheets.isEmpty() ? grid : sheets.get(0), key, TaskbarTray.displayIdOf(icon));
        return reorder.mFrom >= 0 ? reorder : null;
    }

    /**
     * For a drag of the entry {@code key} over the drawer's {@code grid}: one of its entries, or
     * an app from one of our folders, which the drawer does not show. Null when it cannot be done.
     */
    static DrawerReorder over(ViewGroup grid, String key) {
        if (key == null || !(grid.getRootView() instanceof ViewGroup)) {
            return null;
        }
        DrawerReorder reorder = new DrawerReorder((ViewGroup) grid.getRootView(), grid, grid, key,
                TaskbarTray.displayIdOf(grid));
        if (reorder.mOrder.isEmpty() || (reorder.mFrom < 0
                && DrawerArrange.appOf(key, null) == null)) {
            return null;
        }
        if (reorder.mFrom < 0) {
            // From a folder: it arrives at the end until it is over a place.
            reorder.mTo = reorder.mOrder.size();
        }
        return reorder;
    }

    /**
     * Whether a point of the drawer's window is over the drawer itself: inside its panel, and
     * above the taskbar - the panel runs on under the bar, and the bar is somewhere to drop.
     */
    boolean over(float x, float y) {
        Rect panel = onScreen(mSheet);
        int barTop = BarEdge.bottomBarTop(mDisplay);
        if (barTop > 0) {
            panel.bottom = Math.min(panel.bottom, barTop);
        }
        int[] root = new int[2];
        mRoot.getLocationOnScreen(root);
        return panel.contains(Math.round(x) + root[0], Math.round(y) + root[1]);
    }

    /** The drag is at ({@code x}, {@code y}) of the drawer's window, over the drawer. */
    void moveTo(float x, float y) {
        int[] root = new int[2];
        mRoot.getLocationOnScreen(root);
        moveToScreen(x + root[0], y + root[1]);
    }

    /** The drag is at ({@code sx}, {@code sy}) on the screen, over the drawer. */
    void moveToScreen(float sx, float sy) {
        if (mAtRest) {
            // Back over the drawer: the dragged entry's place is the gap again.
            mAtRest = false;
            preview();
        }
        Rect grid = onScreen(mGrid);
        int edge = Ui.dp(mGrid.getContext(), EDGE_DP);
        int step = Ui.dp(mGrid.getContext(), SCROLL_STEP_DP);
        if (sy < grid.top + edge) {
            mGrid.scrollBy(0, -step);
        } else if (sy > grid.bottom - edge) {
            mGrid.scrollBy(0, step);
        }
        float x = sx - grid.left;
        float y = sy - grid.top;
        // The slot under the finger is the laid-out cell there; what the finger is over is the
        // entry the preview draws in that slot (ReorderSlots) - the laid-out one may have been
        // slid elsewhere. Read by layout alone, the gap's own slot was an app's, and resting in
        // the gap folded into an app drawn somewhere else (21:58: every drop made a folder).
        List<View> icons = visibleIcons();
        View cell = iconAt(icons, x, y);
        String slotKey = cell == null ? null : keyOfCell(cell);
        int slot = slotKey == null ? -1 : mOrder.indexOf(slotKey);
        int drawn = slot < 0 ? ReorderSlots.GAP : ReorderSlots.drawnAt(mFrom, mTo, slot);
        View shown = drawn >= 0 ? cellOf(icons, mOrder.get(drawn)) : null;
        View resting = shown != null && canFold(shown) && inMiddle(cell, x, y) ? shown : null;
        if (mSprung != null && mSprung != resting) {
            // Left the folder that opened: it may open again.
            mSprung = null;
        }
        if (resting != mResting) {
            mGrid.removeCallbacks(mFold);
            mGrid.removeCallbacks(mSpringOpen);
            mResting = resting;
            unfold();
            if (resting != null) {
                mGrid.postDelayed(mFold, FOLD_MS);
                String restingKey = keyOfCell(resting);
                if (mSpring != null && resting != mSprung && restingKey != null
                        && restingKey.startsWith("folder:")) {
                    mGrid.postDelayed(mSpringOpen, SPRING_MS);
                }
            }
        }
        // Over an entry and not resting on it to fold: the gap moves there after a moment. Over
        // the gap itself, or resting on an entry, it stays.
        int target = drawn >= 0 && resting == null ? slot : -1;
        if (target != mPending) {
            mGrid.removeCallbacks(mReorder);
            mPending = target;
            if (target >= 0 && target != mTo) {
                mGrid.postDelayed(mReorder, REORDER_MS);
            }
        }
    }

    /** The apps chosen with the dragged one, to go where it goes. */
    void carry(List<String> others) {
        mCarried = new ArrayList<>(others);
    }

    /** Folders the drag rests on open for it, through {@code spring}. */
    void springFolders(Spring spring) {
        mSpring = spring;
    }

    /**
     * The drag has rested on one of our folders: it opens, ZUI's folder takes the drag from
     * here, and the drawer goes back to how it was behind it.
     */
    private void springOpen() {
        View cell = mResting;
        if (cell == null || mSpring == null || cell == mSprung) {
            return;
        }
        mGrid.removeCallbacks(mFold);
        mGrid.removeCallbacks(mReorder);
        unfold();
        if (mSpring.open(cell)) {
            mSprung = cell;
            mResting = null;
        }
    }

    /** The drag has stayed over an entry: the gap opens in its slot. */
    private void reorder() {
        if (mPending >= 0 && mPending != mTo) {
            mTo = mPending;
            preview();
        }
        mPending = -1;
    }

    /**
     * Back to how the drawer was, for a drag that left the drawer or ended without a drop: every
     * cell home and showing, the dragged one too. Put back by drawing the preview again, the
     * dragged entry's cell stayed hidden, and the drawer's list reused that hidden view for
     * whatever it showed there next - a folder gone until ZUX Home restarted (owner, 22:52).
     */
    void cancel() {
        mGrid.removeCallbacks(mFold);
        mGrid.removeCallbacks(mReorder);
        mGrid.removeCallbacks(mSpringOpen);
        mPending = -1;
        mResting = null;
        unfold();
        mTo = mFrom >= 0 ? mFrom : mOrder.size();
        mSent.clear();
        settle(mGrid, null);
        mAtRest = true;
    }

    /**
     * Let go over the drawer: the order kept and the drawer re-sorted. The icons are left where
     * the preview drew them, and from there they settle into their new places.
     */
    void drop() {
        drop(null);
    }

    /** What the drop did, for the log; null before it. */
    String outcome() {
        return mOutcome;
    }

    /** Where a dropped entry is drawn once the drawer has laid it out. */
    interface Landing {
        /**
         * @param cell the cell drawing it, or null when it is out of sight; hidden, unless the
         *             entry went into it, for the drag's own icon to land on
         * @param into whether the entry went into {@code cell}, a folder
         */
        void at(View cell, boolean into);
    }

    /** The same, and {@code landed} is told where the dropped entry is drawn now. */
    void drop(Landing landed) {
        mGrid.removeCallbacks(mFold);
        mGrid.removeCallbacks(mReorder);
        mGrid.removeCallbacks(mSpringOpen);
        String into = mFolding != null ? keyOfCell(mFolding) : null;
        boolean folded = into != null;
        unfold();
        // Where the gap is laid out, before the list changes: the dropped entry is kept there.
        View gapCell = into == null && mTo < mOrder.size() ? cellOf(mOrder.get(mTo)) : null;
        int gapTop = gapCell != null ? gapCell.getTop() : Integer.MIN_VALUE;
        List<String> after = new ArrayList<>(mOrder);
        if (mFrom >= 0) {
            after.remove(mFrom);
        }
        after.add(Math.min(Math.max(mTo, 0), after.size()), mKey);
        String shown = mKey;
        if (into != null) {
            shown = fold(into);
            mOutcome = "folded into " + into;
        } else if (mFrom < 0) {
            NativeDrawerHooks.rearrange("took " + mKey + " out of its folder to " + mTo,
                    (order, folders) -> DrawerArrange.takeOut(order, folders, mKey, mTo));
            mOutcome = "taken out of its folder to " + mTo;
        } else if (!mCarried.isEmpty()) {
            List<String> carried = mCarried;
            NativeDrawerHooks.rearrange("moved " + mKey + " and " + carried.size()
                    + " chosen with it to " + mTo, (order, folders) -> {
                        boolean moved = DrawerArrange.move(order, mKey, mTo);
                        return DrawerArrange.carryAfter(order, mKey, carried) || moved;
                    });
            mOutcome = "moved from " + mFrom + " to " + mTo + " with " + carried.size()
                    + " chosen";
        } else if (mTo != mFrom) {
            NativeDrawerHooks.moveInDrawer(mKey, mTo);
            mOutcome = "moved from " + mFrom + " to " + mTo;
        } else {
            mOutcome = "let go in its own place - no change";
        }
        String key = shown;
        // The chosen apps moved with it change places all along the grid: they spring there.
        boolean settles = folded || !mCarried.isEmpty();
        // Once the grid has laid out the new order, the dropped entry is handed to whoever lands
        // on it. A move or a take-out leaves every entry where the preview drew it: ZUI's list
        // re-binds its cells where they stand (DiffUtil without moves, no item animator), so
        // each one, its offset dropped at once, shows its new entry in the place the preview
        // had it. Sprung home instead, the cells would swap for a moment and spring back. A fold
        // changes the grid's length: what moved springs into place.
        mGrid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mGrid.getViewTreeObserver().removeOnPreDrawListener(this);
                if (gapTop != Integer.MIN_VALUE) {
                    keepInPlace(key, gapTop, after);
                }
                View there = landed != null ? cellOf(key) : null;
                if (settles) {
                    settle(mGrid, null);
                } else {
                    snap(mGrid, there);
                }
                if (landed != null) {
                    landed.at(there, folded);
                }
                return true;
            }
        });
        mGrid.invalidate();
    }

    /**
     * The list scrolled so the dropped entry is drawn where the gap was. ZUI's list keeps its
     * first row on screen when an entry is put in before it: dropped at the very start, the
     * whole drawer moved down a row and the app landed out of sight above (owner, 23:15). When
     * the entry is not on screen, its row is worked out from one that is.
     */
    private void keepInPlace(String key, int gapTop, List<String> after) {
        View there = cellOf(key);
        int dy;
        if (there != null) {
            dy = there.getTop() - gapTop;
        } else {
            List<View> icons = visibleIcons();
            if (icons.size() < 2) {
                return;
            }
            View first = icons.get(0);
            int columns = 0;
            int rowHeight = 0;
            for (View icon : icons) {
                if (icon.getTop() == first.getTop()) {
                    columns++;
                } else if (rowHeight == 0) {
                    rowHeight = icon.getTop() - first.getTop();
                }
            }
            int at = after.indexOf(keyOfCell(first));
            int to = after.indexOf(key);
            if (columns == 0 || rowHeight <= 0 || at < 0 || to < 0) {
                return;
            }
            dy = first.getTop() + (to / columns - at / columns) * rowHeight - gapTop;
        }
        if (dy != 0) {
            mGrid.scrollBy(0, dy);
        }
    }

    /** The dragged app folded into the entry {@code into}; the key of what it is now in. */
    private String fold(String into) {
        Item dragged = DrawerArrange.appOf(mKey, NativeDrawerHooks.labelOf(mKey));
        List<Item> carried = new ArrayList<>();
        for (String other : mCarried) {
            Item app = DrawerArrange.appOf(other, NativeDrawerHooks.labelOf(other));
            if (app != null && !other.equals(into)) {
                carried.add(app);
            }
        }
        if (into.startsWith("folder:")) {
            String id = into.substring("folder:".length());
            NativeDrawerHooks.rearrange("put " + mKey + (carried.isEmpty() ? ""
                    : " and " + carried.size() + " chosen with it") + " into a folder",
                    (order, folders) -> {
                        for (Item folder : folders) {
                            if (folder.id.equals(id)) {
                                boolean in = DrawerArrange.fileInto(order, folders, folder,
                                        dragged);
                                for (Item app : carried) {
                                    in |= DrawerArrange.fileInto(order, folders, folder, app);
                                }
                                return in;
                            }
                        }
                        return false;
                    });
            return into;
        }
        Item target = DrawerArrange.appOf(into, NativeDrawerHooks.labelOf(into));
        String[] made = new String[1];
        NativeDrawerHooks.rearrange("made a folder of " + mKey + " and " + into,
                (order, folders) -> {
                    Item folder = DrawerArrange.merge(order, folders, dragged, target, "Folder");
                    made[0] = folder == null ? null : folder.key();
                    if (folder != null) {
                        for (Item app : carried) {
                            DrawerArrange.fileInto(order, folders, folder, app);
                        }
                    }
                    return folder != null;
                });
        return made[0] != null ? made[0] : mKey;
    }

    /** Whether the drag can fold into this cell: an app into an app or one of our folders. */
    private boolean canFold(View cell) {
        String key = keyOfCell(cell);
        return key != null && !key.equals(mKey) && !mKey.startsWith("folder:")
                && (key.startsWith("folder:") || DrawerArrange.appOf(key, null) != null);
    }

    /** Whether a point of the grid is over the middle of a cell's icon. */
    private static boolean inMiddle(View cell, float x, float y) {
        float size = Math.min(cell.getWidth(), cell.getHeight()) * FOLD_SPOT;
        float cx = cell.getLeft() + cell.getWidth() / 2f;
        // The icon sits in the upper part of a cell, its name below it.
        float cy = cell.getTop() + Math.min(cell.getHeight(), cell.getWidth()) / 2f;
        return Math.abs(x - cx) < size / 2f && Math.abs(y - cy) < size / 2f;
    }

    /** The drag has rested long enough: the app under it swells to take it. */
    private void fold() {
        View cell = mResting;
        if (cell == null || cell == mFolding) {
            return;
        }
        mFolding = cell;
        touched(cell);
        cell.animate().scaleX(FOLD_SCALE).scaleY(FOLD_SCALE).setDuration(Motion.SPRING_MS)
                .setInterpolator(Motion.SPRING).start();
    }

    private void unfold() {
        View cell = mFolding;
        mFolding = null;
        if (cell != null) {
            cell.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS)
                    .setInterpolator(Motion.SPRING_FIRM).start();
        }
    }

    /** Opens the gap at the target: every icon between the two places moves over by one. */
    private void preview() {
        List<View> icons = visibleIcons();
        if (icons.size() < 2) {
            return;
        }
        Map<Integer, View> byIndex = new HashMap<>();
        for (View icon : icons) {
            String key = keyOfCell(icon);
            int index = key == null ? -1 : mOrder.indexOf(key);
            if (index >= 0) {
                byIndex.put(index, icon);
            }
        }
        Geometry grid = Geometry.of(icons);
        for (Map.Entry<Integer, View> entry : byIndex.entrySet()) {
            int index = entry.getKey();
            View icon = entry.getValue();
            if (index == mFrom) {
                // Its place is the gap now; the drag shadow is the app.
                touched(icon);
                icon.setAlpha(0f);
                send(icon, 0f, 0f);
                continue;
            }
            icon.setAlpha(1f);
            int place = ReorderSlots.placeOf(mFrom, mTo, index);
            if (place == index) {
                send(icon, 0f, 0f);
                continue;
            }
            // The slot's laid-out cell, or, past the end or out of sight, the next one along.
            View there = byIndex.get(place);
            float[] cell = there != null
                    ? new float[]{there.getLeft(), there.getTop()}
                    : grid.next(icon, place < index);
            send(icon, cell[0] - icon.getLeft(), cell[1] - icon.getTop());
        }
    }

    private void send(View icon, float dx, float dy) {
        float[] last = mSent.get(icon);
        if (last != null && last[0] == dx && last[1] == dy) {
            return;
        }
        mSent.put(icon, new float[]{dx, dy});
        touched(icon);
        icon.animate().translationX(dx).translationY(dy).setDuration(Motion.SPRING_MS)
                .setInterpolator(Motion.SPRING_FIRM).start();
    }

    /**
     * Every icon home and showing, on a spring from wherever it is - but {@code landing}, which
     * stays hidden for the drag's own icon to land on.
     */
    private static void settle(ViewGroup grid, View landing) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            if (child.getTranslationX() != 0f || child.getTranslationY() != 0f) {
                child.animate().translationX(0f).translationY(0f).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING_FIRM).start();
            }
            if (child == landing) {
                touched(child);
                child.setAlpha(0f);
                continue;
            }
            if (child.getAlpha() < 1f) {
                child.setScaleX(0.8f);
                child.setScaleY(0.8f);
                child.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING).start();
            }
        }
    }

    /**
     * Every cell in its laid-out place and showing, at once - but {@code landing}, which stays
     * hidden for the drag's own icon to land on.
     */
    private static void snap(ViewGroup grid, View landing) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            child.animate().cancel();
            child.setTranslationX(0f);
            child.setTranslationY(0f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            if (child == landing) {
                touched(child);
            }
            child.setAlpha(child == landing ? 0f : 1f);
        }
    }

    /**
     * A drawer cell whose look the rearranging changes - hidden, slid or swelled - is put back
     * to rest the moment the drawer's list takes it off screen. The list keeps such cells to
     * show other entries later; one taken while hidden or slid came back as a blank cell, or as
     * an entry drawn a place along, until ZUX Home restarted (owner, 23:15 and 23:24).
     */
    static void touched(View cell) {
        cell.removeOnAttachStateChangeListener(RESET);
        cell.addOnAttachStateChangeListener(RESET);
    }

    private static final View.OnAttachStateChangeListener RESET =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                    v.animate().cancel();
                    v.setTranslationX(0f);
                    v.setTranslationY(0f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setAlpha(1f);
                }
            };

    /** The grid's cell drawing the entry {@code key}, or null when it is out of sight. */
    private View cellOf(String key) {
        List<View> children = new ArrayList<>();
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            children.add(mGrid.getChildAt(i));
        }
        return cellOf(children, key);
    }

    private static View cellOf(List<View> cells, String key) {
        for (View cell : cells) {
            if (key.equals(keyOfCell(cell))) {
                return cell;
            }
        }
        return null;
    }

    /**
     * The key a grid cell draws: an app's, or one of our folders' ({@link ZuiFolders}). Kept per
     * entry the cell is bound to, as every cell is asked on every finger move; a cell bound to
     * another entry carries another one, so its key is worked out again.
     */
    static String keyOfCell(View cell) {
        if (cell == null) {
            return null;
        }
        Object basis = cell.getTag();
        View child = cell instanceof ViewGroup && ((ViewGroup) cell).getChildCount() > 0
                ? ((ViewGroup) cell).getChildAt(0) : null;
        if (basis == null && child != null) {
            basis = child.getTag();
        }
        if (basis == null) {
            return null;
        }
        String known = KEYS.get(basis);
        if (known != null) {
            return known;
        }
        String key = NativeDrawerHooks.keyOfIcon(cell);
        if (key == null && child != null) {
            Item folder = ZuiFolders.folderOfIcon(child);
            key = folder != null ? folder.key() : null;
        }
        // Only a key found is kept: one not known yet (a folder still being registered) is
        // asked again next time.
        if (key != null) {
            KEYS.put(basis, key);
        }
        return key;
    }

    /** Keys by the entry a cell is bound to. Main thread. */
    private static final Map<Object, String> KEYS = new java.util.WeakHashMap<>();

    /** The grid's entries on screen, apps and folders, in reading order. */
    private List<View> visibleIcons() {
        List<View> icons = new ArrayList<>();
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            View child = mGrid.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0
                    && keyOfCell(child) != null) {
                icons.add(child);
            }
        }
        Collections.sort(icons, (a, b) -> a.getTop() != b.getTop()
                ? Integer.compare(a.getTop(), b.getTop())
                : Integer.compare(a.getLeft(), b.getLeft()));
        return icons;
    }

    /** The icon whose laid-out cell holds this point of the grid, or null. */
    private static View iconAt(List<View> icons, float x, float y) {
        for (View icon : icons) {
            if (x >= icon.getLeft() && x < icon.getRight()
                    && y >= icon.getTop() && y < icon.getBottom()) {
                return icon;
            }
        }
        return null;
    }

    private static Rect onScreen(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /** The grid's columns and row height, for a cell whose icon is scrolled out of sight. */
    private static final class Geometry {
        final List<Integer> lefts = new ArrayList<>();
        int rowHeight;

        static Geometry of(List<View> icons) {
            Geometry g = new Geometry();
            int firstTop = icons.get(0).getTop();
            for (View icon : icons) {
                if (icon.getTop() == firstTop) {
                    g.lefts.add(icon.getLeft());
                } else if (g.rowHeight == 0) {
                    g.rowHeight = icon.getTop() - firstTop;
                }
            }
            if (g.rowHeight == 0) {
                g.rowHeight = icons.get(0).getHeight();
            }
            return g;
        }

        /** The cell one place on from {@code icon}, forward or back, wrapping at the row ends. */
        float[] next(View icon, boolean back) {
            int column = lefts.indexOf(icon.getLeft());
            if (column < 0) {
                return new float[]{icon.getLeft(), icon.getTop()};
            }
            if (back) {
                return column == 0
                        ? new float[]{lefts.get(lefts.size() - 1), icon.getTop() - rowHeight}
                        : new float[]{lefts.get(column - 1), icon.getTop()};
            }
            return column == lefts.size() - 1
                    ? new float[]{lefts.get(0), icon.getTop() + rowHeight}
                    : new float[]{lefts.get(column + 1), icon.getTop()};
        }
    }
}
