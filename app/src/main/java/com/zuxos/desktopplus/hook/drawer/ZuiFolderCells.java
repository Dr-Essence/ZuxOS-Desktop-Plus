package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.Detached;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Our folders in ZUI's drawer lists: born there as entries of our own cell type
 * ({@link ZuiFolders#TYPE}), their cells made and bound here as ZUI's adapter makes and binds its
 * own, and each folder icon sized and aligned to the drawer's app icons and names around it.
 */
public final class ZuiFolderCells {

    private ZuiFolderCells() {
    }

    /**
     * ZUI's entries for the apps in our folders, from its app store; null when the store could
     * not be read, which keeps the ones last seen.
     */
    static void remember(Map<String, Object> filed) {
        if (filed != null) {
            ZuiFolders.sFiled = filed;
            Runnable known = sAppsKnown;
            if (!filed.isEmpty() && sBarWaiting && known != null) {
                sBarWaiting = false;
                ZuiFolders.MAIN.post(known);
            }
        }
    }

    /** A bar asked for its folders before ZUI's app list was read. */
    static volatile boolean sBarWaiting;
    /** Who rebuilds the bar's folders once ZUI's app list is read. */
    private static volatile Runnable sAppsKnown;

    /**
     * Who to tell once ZUI's app list is known, when a bar built its folders before it was: the
     * bar is put up at boot a moment before ZUI builds its drawer's list, which is where our
     * folders' apps come from. Without it, the bar's folders stayed ours for the whole session.
     */
    public static void onAppsKnown(Runnable known) {
        sAppsKnown = known;
    }

    /**
     * The device's icon shape or icon theme changed. ZUI reloads its model and hands its new app
     * list over; until then our folders' icons are ZUI's old ones, so every one is made again
     * from the new list, as at boot. Without ZUI's folders the bars are rebuilt at once.
     */
    public static void forgetIcons() {
        ZuiFolders.BUILT.clear();
        Runnable known = sAppsKnown;
        if (ZuiFolders.on()) {
            sBarWaiting = true;
        } else if (known != null) {
            ZuiFolders.MAIN.post(known);
        }
    }

    /** Whether the list ZUI is building now is a drawer's on the tablet, for its items' birth. */
    private static final ThreadLocal<Boolean> TABLET_LIST = new ThreadLocal<>();

    /**
     * ZUI starts building a drawer's list. Its items are told apart from one another by their
     * type ({@code AdapterItem.isSameAs}), so ours get our type as ZUI makes them, not after:
     * changed afterwards, every rebuild would take our folders for new items.
     */
    static void building(Object appsList) {
        TABLET_LIST.set(ZuiFolders.on() && onTablet(appsList));
    }

    static void built() {
        TABLET_LIST.remove();
    }

    private static boolean onTablet(Object appsList) {
        try {
            Object adapter = ZuiFolders.adapterOf(appsList);
            Object activity = adapter == null ? null : ZuiFolders.sActivity.get(adapter);
            return activity instanceof Context
                    && ZuiFolders.displayOf((Context) activity) == Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    /** One of ZUI's list items as it is made: ours, in a tablet drawer, get our type. */
    static void born(Object item) {
        try {
            if (!Boolean.TRUE.equals(TABLET_LIST.get()) || item == null) {
                return;
            }
            Object info = ZuiFolders.sItemInfo.get(item);
            if (info != null && NativeDrawerHooks.folderOfEntry(info) != null) {
                ZuiFolders.sViewType.setInt(item, ZuiFolders.TYPE);
            }
        } catch (Throwable t) {
            ZuiFolders.off("could not mark our folders in ZUI's list (" + t + ")");
        }
    }

    /** A cell for one of our folders: the size of ZUI's own drawer cells, filled when bound. */
    static Object cell(ViewGroup parent, Object adapter) {
        FrameLayout cell = new FrameLayout(parent.getContext());
        int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        try {
            Object activity = ZuiFolders.sActivity.get(adapter);
            Object profile = Reflect.call(activity, "getDeviceProfile");
            Object cellHeight = Reflect.field(profile, "allAppsCellHeightPx");
            if (cellHeight instanceof Integer) {
                height = (Integer) cellHeight;
            }
            cell.setLayoutParams(new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
            return ZuiFolders.sHolder.newInstance(cell);
        } catch (Throwable t) {
            ZuiFolders.off("could not make a cell (" + t + ")");
            try {
                return ZuiFolders.sHolder.newInstance(cell);
            } catch (Throwable never) {
                throw new IllegalStateException(never);
            }
        }
    }

    /** Fills one of our cells with ZUI's folder icon; false for every cell that is not ours. */
    static boolean bind(Object adapter, Object holder, int position) {
        try {
            // Every cell of the drawer comes through here: the type first, nothing else for
            // ZUI's own.
            if (ZuiFolders.sType == null
                    || (Integer) ZuiFolders.sType.invoke(holder) != ZuiFolders.TYPE) {
                return false;
            }
            View cell = (View) ZuiFolders.sItemView.get(holder);
            if (!(cell instanceof FrameLayout)) {
                return false;
            }
            Object list = Reflect.field(adapter, "mApps");
            Object items = Reflect.call(list, "getAdapterItems");
            if (!(items instanceof List) || position < 0 || position >= ((List<?>) items).size()) {
                return false;
            }
            Object item = ((List<?>) items).get(position);
            if (ZuiFolders.sViewType.getInt(item) != ZuiFolders.TYPE) {
                return false;
            }
            Item folder = NativeDrawerHooks.folderOfEntry(ZuiFolders.sItemInfo.get(item));
            Object activity = ZuiFolders.sActivity.get(adapter);
            FrameLayout frame = (FrameLayout) cell;
            View icon = folder == null ? null : iconFor((Context) activity, frame, folder);
            if (icon == null) {
                frame.removeAllViews();
                return true;
            }
            if (icon.getParent() != frame) {
                if (icon.getParent() instanceof ViewGroup) {
                    ((ViewGroup) icon.getParent()).removeView(icon);
                }
                frame.removeAllViews();
                frame.addView(icon, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            }
            align(icon);
            ZuiFolders.describeOnce(icon);
            return true;
        } catch (Throwable t) {
            L.d("zui folders: cell not filled (" + t + ")");
            return false;
        }
    }

    /**
     * ZUI lays its folder icon out with the home screen's sizes; in the drawer its box was a tenth
     * bigger than the apps beside it and sat higher. Measured once laid out: the folder's preview
     * ({@code getPreviewBounds}) is scaled to a drawer icon's size and moved to where that icon is
     * drawn (its top compound drawable, at its top padding). Its name follows ({@link #fitName}).
     */
    static void align(View icon) {
        if (icon.isLaidOut()) {
            alignNow(icon, true);
        }
    }

    /** Aligns a folder icon of the drawer each time it is laid out, in the same frame. */
    private static final View.OnLayoutChangeListener ALIGN =
            (v, l, t, r, b, ol, ot, or, ob) -> alignNow(v, true);

    /**
     * {@code retry}: when the list has no app icon of this orientation laid out to measure - a
     * screen of folders, laid out before any app - once more after this frame. Until an app is
     * measured the folder takes the drawer's icon size from ZUI's profile
     * ({@code allAppsIconSizePx}), and every folder of the list is aligned again the moment one
     * is ({@link #drawerIconIn}). A folder laid out before any app used to keep ZUI's home size
     * - in landscape smaller than the apps beside it, and than the folders aligned after it.
     */
    private static void alignNow(View icon, boolean retry) {
        try {
            ViewGroup list = icon.getParent() != null
                    && icon.getParent().getParent() instanceof ViewGroup
                    ? (ViewGroup) icon.getParent().getParent() : null;
            Look drawer = drawerIconIn(list);
            android.graphics.Rect preview = new android.graphics.Rect();
            icon.getClass().getMethod("getPreviewBounds", android.graphics.Rect.class)
                    .invoke(icon, preview);
            int size = drawer != null ? drawer.size : profileIconSize(icon);
            if (size <= 0 || preview.width() <= 0) {
                if (retry) {
                    icon.post(() -> alignNow(icon, false));
                }
                return;
            }
            float scale = size / (float) preview.width();
            icon.setPivotX(preview.exactCenterX());
            icon.setPivotY(preview.top);
            icon.setScaleX(scale);
            icon.setScaleY(scale);
            if (drawer == null) {
                // Sized, and placed once an app shows where the drawer draws its icons.
                icon.setTranslationY(0f);
                if (retry) {
                    icon.post(() -> alignNow(icon, false));
                }
            } else {
                icon.setTranslationY(drawer.top - preview.top - icon.getTop());
                fitName(icon, drawer, scale);
            }
            View.OnFocusChangeListener zui = focusOf(list, drawer);
            if (zui != null && icon.getOnFocusChangeListener() == null) {
                // Reached by a keyboard or a controller, marked as ZUI marks its apps - guarded, as
                // ZUI made its mark for app icons. ZUI draws the mark where the view is in its list
                // (FocusIndicatorHelper.viewToRect: its left and top in its parent), so it is told
                // of the folder's cell, the list's own child: told of the folder inside the cell,
                // the mark stayed on the list's first cell wherever the focus went.
                icon.setOnFocusChangeListener((v, focused) -> {
                    try {
                        View cell = v.getParent() instanceof View ? (View) v.getParent() : v;
                        zui.onFocusChange(cell, focused);
                        if (focused) {
                            // The whole cell into view: the list scrolls to the focused view's
                            // laid-out bounds, and the folder is drawn larger than those, so a
                            // folder at the top or bottom stayed half out of the list.
                            cell.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                                    cell.getWidth(), cell.getHeight()), false);
                        }
                    } catch (Throwable t) {
                        L.d("zui folders: ZUI's focus mark not drawn on a folder (" + t + ")");
                    }
                });
            }
            String said = icon.getResources().getConfiguration().orientation + ":" + size + ":"
                    + preview.width() + ":" + (drawer != null);
            if (SAID_ALIGN.size() < 16 && SAID_ALIGN.add(said)) {
                L.i("zui folders: preview " + preview.width() + "px at " + preview.top
                        + " to the drawer's " + size + "px"
                        + (drawer != null ? " at " + drawer.top + ", measured on its apps"
                        : ", from ZUI's profile until an app is laid out")
                        + " (x" + scale + ", " + (icon.getResources().getConfiguration()
                        .orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        ? "landscape" : "portrait") + ")");
            }
        } catch (Throwable t) {
            L.d("zui folders: not aligned (" + t + ")");
        }
    }

    /** What the alignment has told the log: each orientation, size and source once. */
    private static final java.util.Set<String> SAID_ALIGN = new java.util.HashSet<>();

    /**
     * ZUI's focus mark for the icons of a drawer list: the listener its adapter gives each app
     * ({@code BaseAllAppsAdapter.mIconFocusListener}, ZUI's {@code FocusedItemDecorator}), so a
     * folder has it before any app is laid out - a drawer that opens on rows of folders had no mark
     * on them until an app scrolled into view. An app's own listener when the adapter's is not
     * found; null for neither.
     */
    private static View.OnFocusChangeListener focusOf(ViewGroup list, Look drawer) {
        Object adapter = list == null ? null : Reflect.call(list, "getAdapter");
        Object focus = adapter == null ? null : Reflect.field(adapter, "mIconFocusListener");
        if (!(focus instanceof View.OnFocusChangeListener) && adapter != null) {
            for (Field f : Mirror.fields(adapter.getClass())) {
                if (f.getType() == View.OnFocusChangeListener.class) {
                    focus = Mirror.get(f, adapter);
                    break;
                }
            }
        }
        if (focus instanceof View.OnFocusChangeListener) {
            return (View.OnFocusChangeListener) focus;
        }
        return drawer != null ? drawer.focus : null;
    }

    /** The drawer's icon size in ZUI's profile for the icon's window, or 0. */
    private static int profileIconSize(View icon) {
        try {
            Object profile = Reflect.call(ZuiFolders.lookup(icon.getContext()), "getDeviceProfile");
            Object size = Reflect.field(profile, "allAppsIconSizePx");
            return size instanceof Integer ? (Integer) size : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The folder's name as ZUI writes the app names beside it. ZUI's folder name is made for the
     * home screen and does not shorten to its width, so in a portrait drawer, narrower than a home
     * cell and scaled with the icon, long names ran out of the cell on both sides. It takes the
     * cell's width and the drawer names' size, lines and shortening, each divided by the icon's
     * scale so that, scaled, it is exactly as wide and as large as ZUI's own.
     */
    private static void fitName(View icon, Look drawer, float scale) {
        int id = icon.getResources().getIdentifier("folder_icon_name", "id",
                icon.getContext().getPackageName());
        View view = id != 0 ? icon.findViewById(id) : null;
        if (!(view instanceof android.widget.TextView) || icon.getWidth() <= 0 || scale <= 0f) {
            if (!(view instanceof android.widget.TextView)) {
                traceName(icon, view == null ? "no name found" : "its name a "
                        + view.getClass().getSimpleName());
            }
            return;
        }
        android.widget.TextView name = (android.widget.TextView) view;
        int width = Math.round(icon.getWidth() / scale);
        ViewGroup.LayoutParams lp = name.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            traceName(icon, "its name's place is " + lp);
        } else if (name.isLaidOut() && !name.isLayoutRequested() && lp.width == width
                && Math.abs(name.getWidth() - width) > 2) {
            traceName(icon, "its name laid out " + name.getWidth() + "x" + name.getHeight()
                    + " for " + width + " (text " + name.getTextSize() + " px, "
                    + name.getMaxLines() + " line(s))");
        }
        if (lp instanceof FrameLayout.LayoutParams && lp.width != width) {
            FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) lp;
            frame.width = width;
            frame.leftMargin = 0;
            frame.rightMargin = 0;
            frame.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            name.setLayoutParams(frame);
        }
        // Each only when it differs: every one of them asks for a layout, and this runs on each
        // of the icon's layouts.
        float textPx = drawer.textPx / scale;
        if (Math.abs(name.getTextSize() - textPx) > 0.01f) {
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx);
        }
        if (name.getMaxLines() != drawer.lines) {
            name.setMaxLines(drawer.lines);
        }
        if (name.getEllipsize() != drawer.ellipsize) {
            name.setEllipsize(drawer.ellipsize);
        }
    }

    /** What the name trace has said, by orientation and what it saw. */
    private static final java.util.Set<String> SAID_NAME = new java.util.HashSet<>();

    /**
     * Read-only: a folder whose name could not be fitted. Three of ZUX Home's drawer folders kept
     * ZUI's home-size name though they were aligned; what the probe cannot show is said here, once
     * per orientation and kind.
     */
    private static void traceName(View icon, String what) {
        String said = icon.getResources().getConfiguration().orientation + ":"
                + what.substring(0, Math.min(14, what.length()));
        if (SAID_NAME.size() < 6 && SAID_NAME.add(said)) {
            L.i("zui folders: trace: a folder's name not fitted - " + what);
        }
    }

    /** ZUI's own app icons in a drawer list: icon size and top, and how their names are set. */
    private static final class Look {
        final int size;
        final int top;
        final float textPx;
        final int lines;
        final android.text.TextUtils.TruncateAt ellipsize;
        /** The list's orientation and width it was measured at: rotated, it is measured again. */
        final int orientation;
        final int width;
        /** ZUI's mark for the icon a keyboard or controller is on, which its apps draw. */
        final View.OnFocusChangeListener focus;

        Look(android.widget.TextView app, int size, ViewGroup list) {
            this.size = size;
            this.top = app.getPaddingTop();
            this.textPx = app.getTextSize();
            // -1 when ZUI sets no limit; a name never takes more than two lines of a cell.
            this.lines = app.getMaxLines() > 0 ? Math.min(app.getMaxLines(), 2) : 1;
            this.ellipsize = app.getEllipsize() != null ? app.getEllipsize()
                    : android.text.TextUtils.TruncateAt.END;
            this.orientation = list.getResources().getConfiguration().orientation;
            this.width = list.getWidth();
            this.focus = app.getOnFocusChangeListener();
        }

        /** Still the list's look: the same orientation and width. */
        boolean holdsFor(ViewGroup list) {
            return orientation == list.getResources().getConfiguration().orientation
                    && width == list.getWidth();
        }

        boolean sameAs(Look other) {
            return other != null && size == other.size && top == other.top
                    && textPx == other.textPx && lines == other.lines
                    && orientation == other.orientation && width == other.width;
        }
    }

    /** Each drawer list's look, as measured on one of ZUI's own icons in it. */
    private static final Map<ViewGroup, Look> DRAWER_ICON = new WeakHashMap<>();

    /**
     * The look of ZUI's own app icons in a drawer list, measured on one of them (its top
     * compound drawable, drawn at its top padding, and its name) and kept for when none is on
     * screen - while the list keeps its orientation and width. Measured different from before (a
     * first app laid out, a rotation), every folder of the list is aligned to it, not only those
     * laid out from now on.
     */
    private static Look drawerIconIn(ViewGroup list) {
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                android.graphics.drawable.Drawable art =
                        ((android.widget.TextView) c).getCompoundDrawables()[1];
                if (art != null && art.getBounds().width() > 0) {
                    Look measured = new Look((android.widget.TextView) c,
                            art.getBounds().width(), list);
                    Look before = DRAWER_ICON.put(list, measured);
                    if (before == null) {
                        // ZUI's focus listener kept in the look holds the list: let go of it
                        // with the list's window.
                        Detached.forget(list, DRAWER_ICON, list);
                    }
                    if (!measured.sameAs(before)) {
                        list.post(() -> realign(list));
                    }
                    return measured;
                }
            }
        }
        Look kept = DRAWER_ICON.get(list);
        return kept != null && kept.holdsFor(list) ? kept : null;
    }

    /** Every folder of ours a drawer list shows, aligned again. */
    private static void realign(ViewGroup list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View cell = list.getChildAt(i);
            if (cell instanceof FrameLayout && ((FrameLayout) cell).getChildCount() == 1) {
                View icon = ((FrameLayout) cell).getChildAt(0);
                if (icon.getClass().getName().endsWith(".FolderIcon") && icon.isLaidOut()) {
                    alignNow(icon, false);
                }
            }
        }
    }

    /** ZUI's folder icon for one of our folders in this context, built once per change. */
    private static View iconFor(Context activity, ViewGroup cell, Item folder) throws Exception {
        String content = ZuiFolders.contentOf(folder);
        Map<String, ZuiFolders.Built> mine = ZuiFolders.builtFor(activity);
        ZuiFolders.Built built = mine.get(folder.id);
        if (built != null && (built.content.equals(content) || ZuiFolders.isOpen(built))) {
            return built.icon;
        }
        Object info = ZuiFolders.folderInfo(activity, folder);
        if (info == null) {
            return null;
        }
        int layout = activity.getResources().getIdentifier("folder_icon", "layout",
                activity.getPackageName());
        View icon = (View) ZuiFolders.sInflate.invoke(null, layout, activity, cell, info);
        ZuiFolders.drawerLabel(activity, icon);
        if (!ZuiFolders.isLauncher(activity)) {
            // ZUI's taskbar would open it in the bar's window and close the drawer
            // (expandFolder); in the drawer it opens where it is, as ZUI's taskbar opens its own.
            icon.setOnClickListener(v -> ZuiFolders.openHere(v));
            ZuiFolders.noteDelegate(icon);
        }
        // Held, our hold hook gives it ZUI's folder menu; ZUI's own hold needs it long-clickable.
        icon.setOnLongClickListener(v -> true);
        // A keyboard's or a controller's arrows stop on it, and select opens it, as an app.
        icon.setFocusable(true);
        // Aligned as it is laid out, before it is ever drawn at ZUI's home size.
        icon.addOnLayoutChangeListener(ALIGN);
        mine.put(folder.id, new ZuiFolders.Built(icon, content));
        return icon;
    }
}
