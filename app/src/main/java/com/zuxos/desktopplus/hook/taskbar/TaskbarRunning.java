package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.glass.ScreenBackdrop;
import com.zuxos.desktopplus.core.motion.FrameRate;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.KeepAlive;
import com.zuxos.desktopplus.hook.Windows;
import com.zuxos.desktopplus.hook.drawer.ZuiFolderCells;
import com.zuxos.desktopplus.logic.PinList;
import com.zuxos.desktopplus.logic.RunningOrder;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A taskbar of what is actually open.
 *
 * <p>The launcher's own switch for this turned out to be inert here - {@code
 * setCanShowRunningApps(true)} reads back true and the controller shows nothing, because on this
 * firmware ZUI fills the bar itself with the pinned hotseat. So the bar is driven from here
 * instead: the apps that are not open are hidden, and the ones that are open but not pinned are
 * added.
 *
 * <p>Ours go in a row of our own, because nothing of ours may be a child of {@code TaskbarView} -
 * that crash-looped the launcher 62 times, since it animates its children through a property that
 * casts every one of them to {@code Reorderable}. But a row of our own parked out by the tray is
 * what made the bar look like two taskbars, so it is placed where it belongs instead: hard against
 * the last icon the launcher is showing, at the launcher's own icon size and the launcher's own
 * spacing, so the whole thing reads as one row.
 *
 * <p>Everything is reversible and nothing is destroyed. Hiding is a visibility change on the
 * launcher's own icons, remembered so it can be put back; the added icons are ours and are
 * removed with the setting.
 */
public final class TaskbarRunning {

    /**
     * How often the list is re-read while the taskbar is up: every few seconds only when the
     * system's task events cannot be heard. With them, a change arrives the moment it happens
     * ({@link #soon}), and this is no more than a slow safety net.
     */
    private static final long REFRESH_MS = 3000L;
    private static final long REFRESH_HEARD_MS = 30_000L;

    /** As many open apps as the row will ever draw at once, however many are running. */
    private static final int MOST_ICONS = 24;

    /** Icons the launcher put there which we have hidden, so they can be shown again. */
    private static final Map<View, Boolean> HIDDEN = new WeakHashMap<>();

    /** Per taskbar: with two displays, one ticking must not stand for the other. */
    private static final Map<View, Boolean> TICKING = new WeakHashMap<>();

    private TaskbarRunning() {
    }

    /** Called whenever a taskbar is (re)attached, and then on its own every few seconds. */
    static void apply(ViewGroup bar) {
        // Always onto the drag layer itself: a bar's window root can be a wrapper around it.
        ViewGroup dragLayer = TaskbarTray.dragLayerOf(bar);
        if (dragLayer == null) {
            return;
        }
        // Both of these come first, and before any setting is read: the strip is what catches an
        // app dropped on the bar, and nothing can be pinned until it is there - so a taskbar with
        // every one of these settings off still has to be able to receive the first pin.
        watchGeometry(dragLayer, TaskbarTray.rowReference(dragLayer));
        TaskbarDrop.apply(dragLayer);
        TaskbarNav.apply(dragLayer);
        TaskbarRecovery.check(dragLayer);
        fastBar(dragLayer);

        boolean onlyOpen = Cfg.taskbarRunningOnly();
        List<Item> pins = TaskbarPins.pins(dragLayer.getContext());
        if (!onlyOpen && pins.isEmpty() && !Cfg.runningMarks()) {
            restore(dragLayer);
            return;
        }
        // Armed first, and before the row is even looked for: the row may not exist yet and the
        // list can start answering later than the first look at it. A feature that only engages
        // when something else happens to refresh the taskbar is not a feature.
        tick(dragLayer);
        TaskbarFollow.install(dragLayer);
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null) {
            return;
        }
        Set<String> running = RunningTasks.running(dragLayer.getContext(), icons,
                TaskbarTray.displayIdOf(dragLayer));
        if (onlyOpen && running.isEmpty() && pins.isEmpty()) {
            // Nothing readable: better to leave the launcher's bar alone than to empty it. With
            // pins there is something to show - the monitor's desktop with every app closed
            // lost its pinned apps and folders here.
            restore(dragLayer);
            return;
        }
        // Measured while the launcher's icons are still up to be measured: once they are hidden
        // their size and spacing are what our row keeps using.
        RunningIcons.iconSize(icons);
        spacing(icons);
        if (onlyOpen) {
            // A no-op while ZUI builds its row without apps; it only catches icons ZUI built
            // before the setting was switched on.
            hideLauncherApps(icons);
            if (!TaskbarRebind.sAppsAtSource) {
                // A row with apps of ZUI's in it can scroll; one without them cannot.
                keepStill(icons);
            }
        } else {
            showEverythingAgain(icons);
        }
        // The launcher's own icons have no hold menu on this bar; ours is put on them here,
        // where the row is already being walked.
        TaskbarApps.installRowMenu(icons);
        TaskbarStart.apply(dragLayer, icons);
        extras(dragLayer, icons, running, pins, onlyOpen);
        watchTray(dragLayer);
        RunningRow row = rowIn(dragLayer);
        if (row != null) {
            place(dragLayer, row, TaskbarTray.rowReference(dragLayer));
        }
        TaskbarMarks.apply(dragLayer, icons, running);
        RunningRow ours = rowIn(dragLayer);
        if (ours != null) {
            ours.setOpen(running, TaskbarTray.displayIdOf(dragLayer));
        }
        describe(dragLayer, icons, running);
    }

    /**
     * Follows the tray too: its clock and temperatures change length, and the room our row may
     * use ends where the tray begins. Watching only the launcher's row is how the icons ended up
     * over the tray's text.
     */
    private static void watchTray(ViewGroup dragLayer) {
        View tray = TaskbarTray.trayOf(dragLayer);
        if (tray == null || TRAY_WATCHED.containsKey(tray)) {
            return;
        }
        TRAY_WATCHED.put(tray, Boolean.TRUE);
        tray.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) {
                RunningRow current = rowIn(dragLayer);
                if (current != null) {
                    place(dragLayer, current, TaskbarTray.rowReference(dragLayer));
                }
            }
        });
    }

    private static final Map<View, Boolean> TRAY_WATCHED = new WeakHashMap<>();

    /**
     * Holds the launcher's row still while ours scrolls.
     *
     * <p>A swipe along the bar scrolled the launcher's own row as well as ours, and the drawer
     * button is a child of that row - so it slid away under the navigation keys and the robot
     * "disappeared" mid-scroll. With every app of the launcher's hidden there is nothing in its
     * row to scroll to, so it is put straight back whenever it moves.
     */
    private static void keepStill(ViewGroup icons) {
        if (STILL.containsKey(icons)) {
            return;
        }
        STILL.put(icons, Boolean.TRUE);
        icons.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if ((x != 0 || y != 0) && Cfg.taskbarRunningOnly()) {
                v.scrollTo(0, 0);
            }
        });
        if (icons.getScrollX() != 0 || icons.getScrollY() != 0) {
            icons.scrollTo(0, 0);
        }
    }

    private static final Map<View, Boolean> STILL = new WeakHashMap<>();

    /** The last description logged per taskbar, so only a change is logged. */
    private static final Map<View, String> DESCRIBED = new WeakHashMap<>();

    /**
     * One line per taskbar whenever what it shows changes: what is open on its display, which of
     * the launcher's own icons are showing, and what is in our row. Enough to answer "why is that
     * app not on the bar" from a log alone.
     */
    private static void describe(ViewGroup dragLayer, ViewGroup icons, Set<String> running) {
        try {
            List<String> zui = new ArrayList<>();
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                String pkg = IconInfo.packageOfView(child);
                List<String> inside = IconInfo.packagesOfView(child);
                zui.add(pkg != null ? pkg : inside.isEmpty()
                        ? child.getClass().getSimpleName() : "folder" + inside);
            }
            RunningRow row = rowIn(dragLayer);
            String line = "display " + TaskbarTray.displayIdOf(dragLayer) + " open=" + running
                    + " zui=" + zui + " ours=" + (row == null ? "[]"
                    : "pins" + row.mPins + " open" + row.mRunning);
            if (!line.equals(DESCRIBED.get(dragLayer))) {
                DESCRIBED.put(dragLayer, line);
                L.i("taskbar running: " + line);
            }
        } catch (Throwable t) {
            L.d("taskbar running: could not describe the bar (" + t + ")");
        }
    }

    /**
     * Called straight after ZUI has rebuilt its icon row, before that frame is drawn.
     *
     * <p>Opening an app makes ZUI's recent-used model rebind the whole row - its hotseat, its
     * recommendations and the apps it thinks are open - centred on the bar. With "Only open
     * apps" on, every app in our bar is drawn by our own row, so all of ZUI's are hidden again
     * here, in the same frame they were added in: nothing flashes, and nothing pushes our row
     * along.
     *
     * <p>The rebind also hands ZUI's icons fresh listeners, so the hold menu goes back on too,
     * for when the setting is off and ZUI's icons are the ones showing.
     */
    static void rebound(ViewGroup icons) {
        try {
            // Up from the row, not the window's root: on the tablet's desktop-mode bar that root
            // is a wrapper, and a second set of our pieces went onto it, over the whole bar.
            ViewGroup dragLayer = TaskbarTray.dragLayerOf(icons);
            if (dragLayer == null) {
                return;
            }
            TaskbarApps.installRowMenu(icons);
            // ZUI rebuilt its row, and with it its drawer button: ours stays the one shown.
            TaskbarStart.apply(dragLayer, icons);
            if (!Cfg.taskbarRunningOnly() || !Cfg.hideRecommendedFlash()) {
                return;
            }
            if (!TaskbarRebind.sAppsAtSource) {
                // Only where ZUI could not be kept from building its apps in the first place.
                hideLauncherApps(icons);
            }
            // And a real read soon, rather than at the next tick, now that something changed.
            dragLayer.removeCallbacks(REREAD.get(dragLayer));
            // Weakly, because it is also the value of a weak map keyed by this same view.
            java.lang.ref.WeakReference<ViewGroup> layer = new java.lang.ref.WeakReference<>(
                    dragLayer);
            Runnable again = () -> {
                ViewGroup current = layer.get();
                if (current != null && current.isAttachedToWindow()) {
                    apply(current);
                }
            };
            REREAD.put(dragLayer, again);
            dragLayer.postDelayed(again, 600L);
        } catch (Throwable t) {
            L.d("taskbar running: could not tidy the rebuilt row (" + t + ")");
        }
    }

    private static final Map<View, Runnable> REREAD = new WeakHashMap<>();

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Pending re-reads for {@link #soon}, so a burst of task events makes one read, not ten. */
    private static Runnable sSoonFast;
    private static Runnable sSoonSettle;

    /**
     * Something opened, closed or moved: read what is open on every taskbar now, not at the next
     * tick.
     *
     * <p>The bar used to learn about a new app from ZUI rebuilding its own row, which every launch
     * did. With ZUI's recents kept off the bar that rebuild no longer happens, and a new app sat
     * waiting for the three-second tick. Read twice: once almost at once, and once more after the
     * system has finished moving the task, since a launch is still settling when it is announced.
     * Safe from any thread.
     */
    public static void soon() {
        // The glass behind every pane is about to be stale too, and what to keep alive changed.
        ScreenBackdrop.nudge();
        KeepAlive.soon();
        MAIN.removeCallbacks(sSoonFast);
        MAIN.removeCallbacks(sSoonSettle);
        sSoonFast = TaskbarRunning::applyEverywhere;
        sSoonSettle = TaskbarRunning::applyEverywhere;
        MAIN.postDelayed(sSoonFast, 80L);
        MAIN.postDelayed(sSoonSettle, 500L);
    }

    private static void applyEverywhere() {
        for (View root : Windows.roots()) {
            ViewGroup dragLayer = root.isAttachedToWindow() ? TaskbarTray.dragLayerOf(root) : null;
            if (dragLayer != null) {
                apply(dragLayer);
            }
        }
    }

    /**
     * The row of app icons, by name.
     *
     * <p>Not {@code rowReference}, which answers "the row the tray should line up with" and will
     * happily give back the navigation buttons - and putting app icons in among back and home is
     * not a mistake worth risking for one shared helper.
     */
    private static ViewGroup iconRow(ViewGroup dragLayer) {
        for (View view : Reflect.findByIdNames(dragLayer, "taskbar_view")) {
            if (view instanceof ViewGroup) {
                return (ViewGroup) view;
            }
        }
        return null;
    }

    /**
     * Puts the taskbar back exactly as the launcher built it.
     *
     * <p>The whole taskbar, not just its icon row: our own row of extra icons lives in the drag
     * layer, and unhiding the launcher's icons while leaving ours floating over the bar would be
     * a worse state than either.
     */
    static void restore(ViewGroup dragLayer) {
        ScrollRow scroller = scrollerIn(dragLayer);
        if (scroller != null) {
            dragLayer.removeView(scroller);
        }
        TaskbarMarks.remove(dragLayer);
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null) {
            return;
        }
        showEverythingAgain(icons);
        TaskbarApps.installRowMenu(icons);
        TaskbarStart.apply(dragLayer, icons);
    }

    /** Puts back icons an earlier run hid, without touching anything else in the bar. */
    private static void showEverythingAgain(ViewGroup icons) {
        for (int i = icons.getChildCount() - 1; i >= 0; i--) {
            View child = icons.getChildAt(i);
            if (HIDDEN.remove(child) != null) {
                child.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Hides every app and folder in the launcher's own row, leaving its drawer button.
     *
     * <p>The open apps are all drawn by our row instead, which is what lets them line up from the
     * drawer button in the order they opened. ZUI centres its icons on the whole bar and adds
     * them in its own order, so with both showing the bar was two clusters that shifted on every
     * launch and ran under the tray.
     */
    private static void hideLauncherApps(ViewGroup icons) {
        boolean changed = false;
        for (int i = icons.getChildCount() - 1; i >= 0; i--) {
            View child = icons.getChildAt(i);
            if (IconInfo.packagesOfView(child).isEmpty()) {
                // The drawer button, and anything else with no app behind it.
                continue;
            }
            if (child.getVisibility() != View.GONE) {
                HIDDEN.put(child, Boolean.TRUE);
                child.setVisibility(View.GONE);
                changed = true;
            }
        }
        if (changed) {
            icons.requestLayout();
        }
    }

    /**
     * The open apps the launcher has no icon for, in a row of our own.
     *
     * <p>This used to put them in the launcher's row, and that crash-looped the launcher: {@code
     * TaskbarView} animates its children through a property that casts every one of them to
     * {@code Reorderable}, so a plain image view there kills the process the moment the row
     * animates - which adding one is itself what triggers. So nothing of ours is ever its child.
     */
    private static void extras(ViewGroup dragLayer, ViewGroup icons, Set<String> running,
            List<Item> pins, boolean onlyOpen) {
        Set<String> missing = new LinkedHashSet<>(
                onlyOpen ? running : Collections.<String>emptySet());
        // Only an icon of the app itself counts as already showing it. An app inside a folder -
        // the launcher's or one pinned here - still gets its own: the folder only says that
        // something in it is open, and with ZUI as the main launcher every hotseat folder sits on
        // this bar, which hid every open app that lived in one.
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                missing.remove(IconInfo.packageOfView(child));
            }
        }
        missing.removeAll(PinList.directPackagesOf(pins));
        // The launcher itself is the desktop, not an app you switch back to.
        missing.remove(dragLayer.getContext().getPackageName());

        RunningRow row = rowIn(dragLayer);
        if (missing.isEmpty() && pins.isEmpty()) {
            ScrollRow scroller = scrollerIn(dragLayer);
            if (scroller != null) {
                dragLayer.removeView(scroller);
            }
            return;
        }
        if (row == null) {
            row = addRow(dragLayer);
            if (row == null) {
                return;
            }
        }
        int size = RunningIcons.iconSize(icons);
        int gap = spacing(icons);
        // Not trimmed to the room any more - the row scrolls now. The cap is only so that a
        // machine with eighty things open does not build eighty views every three seconds.
        int displayId = TaskbarTray.displayIdOf(dragLayer);
        List<String> plain = new ArrayList<>();
        for (String key : row.mRunning) {
            if (key.indexOf(RunningTasks.WINDOW_MARK) < 0) {
                plain.add(key);
            }
        }
        List<String> wanted = RunningTasks.withWindows(RunningOrder.trimToFit(
                RunningOrder.inOrder(plain, missing), MOST_ICONS * (size + gap), size, gap),
                running, displayId, dragLayer.getContext().getPackageName());
        List<String> pinKeys = new ArrayList<>();
        for (Item pin : pins) {
            pinKeys.add(pin.key());
        }
        Theme theme = Theme.of(displayId);
        boolean sameLook = row.mSize == size && row.mTheme == theme;
        if (wanted.equals(row.mRunning) && pinKeys.equals(row.mPins) && sameLook) {
            return;
        }
        Context ctx = dragLayer.getContext();
        if (pinKeys.equals(row.mPins) && sameLook
                && reconcile(row, wanted, ctx, size, gap, displayId)) {
            return;
        }
        // Pins changed (pinned, unpinned, rearranged) - rebuilt in one go, without the row's
        // transitions: a rearrangement has already been shown by the gap preview, and animating
        // every icon out and back in on top of it would undo that.
        android.animation.LayoutTransition transition = row.getLayoutTransition();
        row.setLayoutTransition(null);
        row.removeAllViews();
        List<String> shownPins = new ArrayList<>();
        for (Item pin : pins) {
            View icon = RunningIcons.pinIcon(ctx, pin, size, displayId);
            if (icon != null) {
                add(row, icon, size, gap);
                shownPins.add(pin.key());
            }
        }
        List<String> shown = new ArrayList<>();
        for (String pkg : wanted) {
            View icon = RunningIcons.iconFor(ctx, pkg, size, displayId);
            if (icon != null) {
                add(row, icon, size, gap);
                shown.add(pkg);
            }
        }
        // What went in, not what was asked for: an app whose icon could not be drawn would
        // otherwise be remembered as shown and never tried again.
        row.mRunning = shown;
        row.mPins = shownPins;
        row.mSize = size;
        row.mTheme = theme;
        row.setLayoutTransition(transition);
    }

    /**
     * Brings the open apps in the row up to date one icon at a time, so each change can move.
     *
     * <p>Closed apps are taken out and new ones put in at their place, with everything else left
     * standing: the row's transition then shrinks the closed ones away, springs the new ones in
     * and slides the rest over. Rebuilding the row instead - as it used to - made every icon
     * vanish and reappear on every change, so nothing could move.
     *
     * @return false when the change is not a matter of adding and removing - the open apps in a
     *         different order - and the caller rebuilds instead
     */
    private static boolean reconcile(RunningRow row, List<String> wanted, Context ctx, int size,
            int gap, int displayId) {
        int base = row.mPins.size();
        List<String> working = new ArrayList<>(row.mRunning);
        if (row.getChildCount() != base + working.size()) {
            return false;
        }
        for (int i = working.size() - 1; i >= 0; i--) {
            if (!wanted.contains(working.get(i))) {
                row.removeViewAt(base + i);
                working.remove(i);
            }
        }
        int pos = 0;
        for (String pkg : wanted) {
            if (pos < working.size() && working.get(pos).equals(pkg)) {
                pos++;
                continue;
            }
            if (working.contains(pkg)) {
                // Still open, but somewhere else in the order.
                return false;
            }
            View icon = RunningIcons.iconFor(ctx, pkg, size, displayId);
            if (icon == null) {
                continue;
            }
            row.addView(icon, base + pos, new LinearLayout.LayoutParams(size, size));
            working.add(pos, pkg);
            pos++;
        }
        if (pos != working.size()) {
            return false;
        }
        // The first icon sits flush; every other one keeps the launcher's gap before it.
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            int margin = i == 0 ? 0 : gap;
            if (lp.leftMargin != margin) {
                lp.leftMargin = margin;
                child.setLayoutParams(lp);
            }
        }
        row.mRunning = working;
        return true;
    }

    /**
     * How the row moves when an icon comes or goes: new apps spring in from half size, closed
     * ones shrink and fade away, and the icons beside them slide over on a firm spring.
     */
    private static android.animation.LayoutTransition rowTransition() {
        android.animation.LayoutTransition t = new android.animation.LayoutTransition();
        // Only the row's own children move; the scroller and the bar around it stay put.
        t.setAnimateParentHierarchy(false);
        android.animation.ObjectAnimator in = android.animation.ObjectAnimator
                .ofPropertyValuesHolder((Object) null,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f),
                        android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f));
        t.setAnimator(android.animation.LayoutTransition.APPEARING, in);
        t.setDuration(android.animation.LayoutTransition.APPEARING, Motion.SPRING_MS);
        t.setInterpolator(android.animation.LayoutTransition.APPEARING, Motion.SPRING);
        t.setStartDelay(android.animation.LayoutTransition.APPEARING, 60);
        android.animation.ObjectAnimator out = android.animation.ObjectAnimator
                .ofPropertyValuesHolder((Object) null,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.5f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.5f),
                        android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f));
        t.setAnimator(android.animation.LayoutTransition.DISAPPEARING, out);
        t.setDuration(android.animation.LayoutTransition.DISAPPEARING, Motion.SHORT);
        t.setInterpolator(android.animation.LayoutTransition.DISAPPEARING, Motion.EXIT);
        t.setStartDelay(android.animation.LayoutTransition.DISAPPEARING, 0);
        for (int type : new int[]{android.animation.LayoutTransition.CHANGE_APPEARING,
                android.animation.LayoutTransition.CHANGE_DISAPPEARING}) {
            t.setDuration(type, Motion.SPRING_MS);
            t.setInterpolator(type, Motion.SPRING_FIRM);
        }
        t.setStartDelay(android.animation.LayoutTransition.CHANGE_APPEARING, 0);
        t.setStartDelay(android.animation.LayoutTransition.CHANGE_DISAPPEARING, 80);
        return t;
    }

    private static void add(RunningRow row, View icon, int size, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = row.getChildCount() == 0 ? 0 : gap;
        row.addView(icon, lp);
    }

    /**
     * While a pin is dragged along the bar: its own slot empties and the pins between where it
     * was and where it would land slide over by one, opening the gap it will drop into. The drop
     * then rebuilds the row with every icon already standing where the preview put it, so
     * nothing jumps.
     *
     * @param slot where it would land, counted with the dragged pin still in the row
     */
    static void previewMove(ViewGroup dragLayer, String key, int slot) {
        RunningRow row = rowIn(dragLayer);
        if (row == null) {
            return;
        }
        int from = row.mPins.indexOf(key);
        if (from < 0 || from >= row.getChildCount()) {
            return;
        }
        int to = Math.min(slot > from ? slot - 1 : slot, row.mPins.size() - 1);
        View dragged = row.getChildAt(from);
        int step = dragged.getWidth() + ((LinearLayout.LayoutParams) dragged.getLayoutParams())
                .leftMargin;
        if (row.getChildCount() > 1) {
            step = Math.abs(row.getChildAt(1).getLeft() - row.getChildAt(0).getLeft());
        }
        for (int i = 0; i < row.mPins.size() && i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            float shift;
            if (i == from) {
                child.setAlpha(0f);
                shift = (to - from) * step;
            } else if (from < to && i > from && i <= to) {
                shift = -step;
            } else if (to < from && i >= to && i < from) {
                shift = step;
            } else {
                shift = 0f;
            }
            if (child.getTranslationX() != shift) {
                child.animate().translationX(shift).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING_FIRM)
                        .start();
            }
        }
    }

    /** Puts every icon back where it stands, for a drag that left the bar or ended. */
    static void clearPreview(ViewGroup dragLayer) {
        RunningRow row = rowIn(dragLayer);
        if (row == null) {
            return;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            child.animate().translationX(0f).alpha(1f).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.EASE).start();
        }
    }

    /** Our row, for whoever needs to measure a drop against what is already in it. */
    static ViewGroup rowOf(ViewGroup dragLayer) {
        return rowIn(dragLayer);
    }

    /**
     * Where the row's first icon starts, in the drag layer's own coordinates.
     *
     * <p>The row sits inside a scroller now, so its own {@code getLeft()} is relative to that and
     * says nothing about where it is on screen - and what has been scrolled out of sight has to
     * come off as well, or the marks under the icons drift away from them.
     */
    static int rowLeft(ViewGroup dragLayer) {
        RunningRow row = rowIn(dragLayer);
        if (row == null) {
            return 0;
        }
        int left = offsetIn(dragLayer, row);
        if (row.getParent() instanceof ScrollRow) {
            left -= ((ScrollRow) row.getParent()).getScrollX();
        }
        return left;
    }

    /**
     * Whether the launcher is already showing this app in its own row.
     *
     * <p>Pinning it again would put a second copy of the same icon on the same bar, a few pixels
     * from the first, which looks like a bug whatever the reason for it.
     */
    static boolean alreadyInTheBar(ViewGroup dragLayer, String pkg) {
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null || pkg == null) {
            return false;
        }
        for (int i = 0; i < icons.getChildCount(); i++) {
            if (IconInfo.packagesOfView(icons.getChildAt(i)).contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static RunningRow rowIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            View child = dragLayer.getChildAt(i);
            if (child instanceof ScrollRow && ((ScrollRow) child).getChildCount() > 0) {
                return (RunningRow) ((ScrollRow) child).getChildAt(0);
            }
        }
        return null;
    }

    /** The scroller our row sits in on this bar, or null - for the bar's touch region. */
    static View scrollerOf(ViewGroup dragLayer) {
        return scrollerIn(dragLayer);
    }

    /** What is actually placed in the drag layer: the scroller the row sits in. */
    private static ScrollRow scrollerIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            if (dragLayer.getChildAt(i) instanceof ScrollRow) {
                return (ScrollRow) dragLayer.getChildAt(i);
            }
        }
        return null;
    }

    /**
     * The row, and the scroller around it.
     *
     * <p>Open enough apps and the row runs out of bar. It used to drop the ones that would not
     * fit; now it scrolls, which is what a taskbar does when it is full.
     */
    static final class ScrollRow extends android.widget.HorizontalScrollView {
        ScrollRow(Context ctx) {
            super(ctx);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            // More icons than room: the ends fade out instead of anything being drawn on top,
            // which is the whole indication there is more to scroll to - and nothing when not.
            setHorizontalFadingEdgeEnabled(true);
            setFadingEdgeLength(Ui.dp(ctx, 28));
            // Nothing here reacts to a touch unless it is a scroll, so a tap goes to the icon.
            setFillViewport(false);
        }

        /**
         * Draws the row inside its own bounds and nowhere else.
         *
         * <p>Scrolled, the row's icons were drawn past both ends - over the drawer button on the
         * left and the tray on the right - because the bar's own layer does not clip what is in
         * it. The clip is set here, on the scroller, so it holds whatever the parents do.
         */
        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            int saved = canvas.save();
            canvas.clipRect(getScrollX(), 0, getScrollX() + getWidth(), getHeight());
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saved);
        }
    }

    /** Puts our row in the drag layer, lined up with the bar the way the tray is. */
    private static RunningRow addRow(ViewGroup dragLayer) {
        try {
            View reference = TaskbarTray.rowReference(dragLayer);
            ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
            if (!(lp instanceof FrameLayout.LayoutParams)) {
                L.w("taskbar running: the drag layer's layout params are not reproducible, so "
                        + "open apps that are not pinned cannot be shown");
                return null;
            }
            RunningRow row = new RunningRow(dragLayer.getContext());
            row.setLayoutTransition(Cfg.animations() ? rowTransition() : null);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ScrollRow scroller = new ScrollRow(dragLayer.getContext());
            scroller.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dragLayer.addView(scroller, lp);
            // Centred on the bar when nothing of the launcher's is showing, so a change in what
            // the row holds moves where it starts.
            row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) {
                    place(dragLayer, row, TaskbarTray.rowReference(dragLayer));
                }
            });
            place(dragLayer, row, reference);
            watchGeometry(dragLayer, reference);
            L.i("taskbar running: a row of our own, beside the launcher's, for open apps that "
                    + "are not pinned");
            return row;
        } catch (Throwable t) {
            L.e("taskbar running: could not add our row", t);
            return null;
        }
    }

    /**
     * Follows the launcher's own row wherever it goes.
     *
     * <p>Its icons are centred in a full-width row, so every icon hidden or shown moves the edge
     * ours has to sit against - and when the row is first added nothing has been measured at all.
     * One listener per taskbar, which finds the current row rather than holding one: ours comes
     * and goes with whether there is anything to put in it, and a listener per row would pile up
     * a dead one every time it went.
     */
    private static void watchGeometry(ViewGroup dragLayer, View reference) {
        if (WATCHING.containsKey(dragLayer)) {
            return;
        }
        // Weakly, as REREAD: the listener is also the value of a weak map keyed by this same view,
        // and held strongly it would keep every bar ZUI ever built, a new one at each rebuild.
        java.lang.ref.WeakReference<ViewGroup> layer = new java.lang.ref.WeakReference<>(
                dragLayer);
        View.OnLayoutChangeListener again = (v, l, t, r, b, ol, ot, or, ob) -> {
            ViewGroup current = layer.get();
            if (current != null) {
                relayout(current);
            }
        };
        if (reference != null) {
            reference.addOnLayoutChangeListener(again);
        }
        View icons = iconRow(dragLayer);
        if (icons != null && icons != reference) {
            icons.addOnLayoutChangeListener(again);
        }
        // And the navigation keys, held at the end where the build lets us, followed otherwise.
        NavKeysHold.watch(dragLayer);
        View keys = TaskbarStart.navKeys(dragLayer);
        if (keys != null && keys != reference && keys != icons) {
            keys.addOnLayoutChangeListener(again);
        }
        WATCHING.put(dragLayer, again);
    }

    /**
     * Everything of ours on this bar put where it now belongs: after ZUI laid its bar out again,
     * or moved its navigation keys.
     */
    static void relayout(ViewGroup dragLayer) {
        // The start button first: our row is measured from where it ends, and placing the row
        // first measured it from where the button used to be.
        ViewGroup row = iconRow(dragLayer);
        if (row != null) {
            TaskbarStart.apply(dragLayer, row);
        }
        RunningRow current = rowIn(dragLayer);
        if (current != null) {
            place(dragLayer, current, TaskbarTray.rowReference(dragLayer));
        }
        // The marks sit under the icons that moved, and the drop strip spans the same bar.
        TaskbarMarks.refresh(dragLayer);
        TaskbarDrop.apply(dragLayer);
    }

    /** One geometry listener per taskbar, whether or not our row is up at the moment. */
    private static final Map<View, View.OnLayoutChangeListener> WATCHING = new WeakHashMap<>();

    /**
     * Lines the row up with the launcher's icons.
     *
     * <p>Hard against the right-hand end of them, at the gap the launcher leaves between two of
     * its own, so that the bar reads as one row rather than as two clusters with a hole between
     * them. Which is what it looked like when this was measured from the tray instead.
     */
    private static void place(ViewGroup dragLayer, RunningRow row, View reference) {
        try {
            View placed = row.getParent() instanceof ScrollRow ? (View) row.getParent() : row;
            ViewGroup.LayoutParams raw = placed.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            // On the bar by gravity, as ZUI's rows are. Without the row's height, wrapped: the
            // bar's edge of the drag layer is still the bar.
            int height = reference != null && reference.getHeight() > 0
                    ? reference.getHeight() : ViewGroup.LayoutParams.WRAP_CONTENT;
            int edge = leftEdge(dragLayer);
            int horizontal;
            int left;
            int right = rightGap(dragLayer, edge, reference);
            int width;
            if (edge >= 0) {
                // Anchored at both ends - after the drawer button on the left, short of the tray
                // on the right - and as wide as whatever lies between, worked out by the layout
                // pass itself. A width computed here went stale whenever the button or the tray
                // moved after it was measured, which is how icons ended up over the tray.
                horizontal = Gravity.START;
                left = edge;
                width = ViewGroup.LayoutParams.MATCH_PARENT;
            } else {
                // No icons to sit beside - a bar of navigation buttons only. Beside the tray is
                // then the only place left that is not on top of something else.
                horizontal = Gravity.END;
                left = 0;
                width = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            boolean moved = TaskbarTray.onRow(lp, reference, height, horizontal);
            if (!moved && lp.leftMargin == left && lp.rightMargin == right && lp.width == width) {
                // Nothing moved. This runs from a layout listener, and setting layout params
                // asks for another layout whether or not they changed - which would be a pass
                // per frame, for ever, over a row that was already in the right place.
                return;
            }
            lp.leftMargin = left;
            lp.rightMargin = right;
            lp.width = width;
            placed.setLayoutParams(lp);
        } catch (Throwable t) {
            L.d("taskbar running: could not place our row (" + t + ")");
        }
    }

    /**
     * Where our row starts.
     *
     * <p>With "Only open apps" on, the whole bar is ours: right after the drawer button, the way
     * Windows lines its taskbar up after Start. Otherwise just past the last icon the launcher
     * is showing.
     */
    private static int leftEdge(ViewGroup dragLayer) {
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null || icons.getWidth() <= 0) {
            return -1;
        }
        if (Cfg.taskbarRunningOnly()) {
            // From the navigation keys and the start button's slot, whether or not the launcher's
            // row is showing: ZUI hides that row while its app drawer is open, and measuring from
            // it then is what sent the whole row off beside the tray.
            int start = TaskbarStart.rightEdge(dragLayer, icons);
            // A clear gap after the start button, so it reads as the button it is and not as the
            // first of the apps - and wide enough to hold or right-click for the taskbar's menu
            // when the row is full and there is no bare bar left anywhere else.
            return start >= 0 ? start + Ui.dp(dragLayer.getContext(), BARE_DP) : -1;
        }
        if (icons.getVisibility() != View.VISIBLE) {
            return -1;
        }
        int edge = -1;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0
                    && !TaskbarStart.isMoved(child)) {
                edge = Math.max(edge, child.getRight());
            }
        }
        if (edge < 0) {
            // Nothing of the launcher's showing - no app open, and the drawer button moved to the
            // corner. The row goes where the launcher's cluster would be, centred on the bar,
            // rather than off beside the clock where it used to land.
            RunningRow row = rowIn(dragLayer);
            int width = row != null ? row.getWidth() : 0;
            return Math.max(0, offsetIn(dragLayer, icons) + (icons.getWidth() - width) / 2);
        }
        return edge + offsetIn(dragLayer, icons) + spacing(icons);
    }

    /**
     * Bare bar kept at each end of our row, after the start button and before the tray: room to
     * hold or right-click for the taskbar's menu however many apps are open.
     */
    private static final int BARE_DP = 40;

    /** What last ended each bar's row, as written to the log. */
    private static final Map<ViewGroup, String> LIMITED_BY = new java.util.WeakHashMap<>();

    /** What ended this bar's row last time it was placed, for the probe. */
    public static String endsAt(ViewGroup dragLayer) {
        String said = LIMITED_BY.get(dragLayer);
        return said != null ? said : "not placed yet";
    }

    /** A view of ZUI's, as the log names it: class, id and width. */
    private static String describe(View view) {
        if (view == null) {
            return "?";
        }
        String id = Reflect.idName(view);
        return view.getClass().getSimpleName() + (id != null ? " #" + id : "") + " "
                + view.getWidth() + "px wide";
    }

    /**
     * How far our row has to stay from the right-hand end of the bar: up to the first thing to
     * its right - our tray, or anything of ZUI's sitting in the bar's row, such as the search
     * pill and the button at the far end of the tablet's desktop-mode bar - and a stretch of bare
     * bar more, so neither the last icon nor its fading edge touches it and the bar can still be
     * held there. Stopping at our tray alone ran the row under ZUI's search pill on the tablet,
     * which has no tray.
     */
    private static int rightGap(ViewGroup dragLayer, int start, View reference) {
        int margin = Ui.dp(dragLayer.getContext(), BARE_DP);
        int width = dragLayer.getWidth();
        if (width <= 0) {
            return TaskbarTray.trayWidth(dragLayer) + margin;
        }
        int limit = width;
        String by = "the bar's end";
        View tray = TaskbarTray.trayOf(dragLayer);
        if (tray != null && tray.getWidth() > 0 && tray.getParent() == dragLayer) {
            limit = tray.getLeft();
            by = "the tray";
        }
        // ZUI's navigation keys, where they rest: always, whether or not they are showing at
        // this moment. ZUI fades and slides them as an app opens or home comes back, and
        // measured then the row ran on to the bar's end, under the keys: every switch flipped the
        // row's end between the two.
        // Their own place or where they are drawn, the nearer: in landscape ZUI rests them part
        // way along the bar by sliding them there (x 1384 on a bar 3040 wide).
        View keys = restingKeys(dragLayer);
        if (keys != null) {
            int at = Math.min(offsetIn(dragLayer, keys),
                    TaskbarStart.drawnLeftIn(dragLayer, keys));
            if (at >= start && at < limit) {
                limit = at;
                by = describe(keys);
            }
        }
        if (start >= 0 && reference != null && reference.getHeight() > 0) {
            View[] which = new View[1];
            int zui = firstOfZuisAfter(dragLayer, start, reference.getTop(),
                    reference.getBottom(), which);
            if (zui >= 0 && zui < limit) {
                limit = zui;
                by = describe(which[0]);
            }
        }
        // What ends the row, each time that changes: the bar in portrait was cut short by something
        // of ZUI's that no probe has shown yet. Which view and how wide the bar is, not where: ZUI
        // slides its keys across the bar, and every frame of that would be a line.
        String said = by + " (bar " + width + " wide)";
        if (!said.equals(LIMITED_BY.put(dragLayer, said))) {
            L.i("taskbar running: display " + TaskbarTray.displayIdOf(dragLayer)
                    + " row ends at " + said + ", x " + limit);
        }
        return Math.max(0, width - limit) + margin;
    }

    /**
     * The left edge of the first visible thing of ZUI's that sits in the bar's row at or after
     * {@code start}, in the drag layer's coordinates; -1 when there is none.
     *
     * <p>Found by what is on screen rather than by name: buttons and pills of the bar's own, not
     * the wide containers they sit in, and nothing of ours.
     */
    private static int firstOfZuisAfter(ViewGroup dragLayer, int start, int top, int bottom,
            View[] which) {
        int found = -1;
        int half = dragLayer.getWidth() / 2;
        java.util.ArrayDeque<View> pending = new java.util.ArrayDeque<>();
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            pending.add(dragLayer.getChildAt(i));
        }
        while (!pending.isEmpty()) {
            View view = pending.poll();
            // Faded out still counts: ZUI fades its search pill in when an app opens, and a row
            // placed while it was at nothing ran on under it once it showed.
            if (view.getVisibility() != View.VISIBLE
                    || view.getClass().getName().startsWith("com.zuxos.desktopplus.")
                    || floating(view)
                    // A bare view is no button of the bar's: it is the arrow under ZUI's popup,
                    // which ZUI puts in the bar's layer beside the popup. Counted, it cut our row
                    // short at the app the popup was opened for.
                    || view.getClass() == View.class) {
                continue;
            }
            if (view.getWidth() >= half && view instanceof ViewGroup) {
                // A container spanning the bar: what matters is what is in it.
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.add(group.getChildAt(i));
                }
                continue;
            }
            if (view.getWidth() <= 0 || view.getWidth() >= half) {
                continue;
            }
            // Where it is drawn: ZUI slides its navigation keys from end to end. But while they
            // slide in from past the bar's end - an app opening, the keys coming back - where
            // they are drawn is not where they stop. The nearer of that and their own place, so
            // the row never runs on under where they land: measured mid-slide (x 2520 on a bar
            // 1904 wide, portrait), it ran to the bar's end, under the keys at 1384.
            int drawn = TaskbarStart.drawnLeftIn(dragLayer, view);
            int laid = offsetIn(dragLayer, view);
            int left = Math.min(drawn, laid);
            if (drawn != laid) {
                settleLater(dragLayer, view, drawn);
            }
            int viewTop = topIn(dragLayer, view);
            if (left < start || viewTop >= bottom || viewTop + view.getHeight() <= top) {
                continue;
            }
            if (found < 0 || left < found) {
                found = left;
                which[0] = view;
            }
            watchBoundary(dragLayer, view);
        }
        return found;
    }

    /**
     * ZUI's navigation keys on this bar, wherever they are in a slide or a fade; null where the
     * bar has none - gone, as in gesture navigation - or they have never been laid out.
     */
    private static View restingKeys(ViewGroup dragLayer) {
        for (View keys : Reflect.findByIdNames(dragLayer, "end_nav_buttons")) {
            if (keys.getWidth() <= 0) {
                continue;
            }
            boolean gone = false;
            for (View v = keys; v != null && v != dragLayer;
                    v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                if (v.getVisibility() == View.GONE) {
                    gone = true;
                    break;
                }
            }
            if (!gone) {
                return keys;
            }
        }
        return null;
    }

    /**
     * ZUI's popups, folders and menus: over the bar for a moment, never a part of it. Counted as
     * the bar's, an app menu opened over the bar's window - full screen while it is up - cut our
     * row short at the menu, and it stayed short after the menu closed.
     */
    private static boolean floating(View view) {
        for (Class<?> c = view.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("com.android.launcher3.AbstractFloatingView")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Something of ZUI's our row stops short of: when it moves or resizes - the search pill
     * sliding in as an app opens - the row is placed again, at once rather than at the next
     * refresh.
     */
    private static void watchBoundary(ViewGroup dragLayer, View view) {
        if (BOUNDARIES.containsKey(view)) {
            return;
        }
        BOUNDARIES.put(view, Boolean.TRUE);
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || r != or) {
                dragLayer.post(() -> relayout(dragLayer));
            }
        });
    }

    private static final Map<View, Boolean> BOUNDARIES = new WeakHashMap<>();

    /**
     * Something of ZUI's caught sliding: the row is placed again once the slide is over. A slide
     * moves a view without laying it out, so no layout listener hears it end, and the row stayed
     * where the moment of measuring left it. Asked again only while it is still moving - where it
     * is drawn differs from last time - so a view resting away from its own place asks once.
     */
    private static void settleLater(ViewGroup dragLayer, View view, int drawn) {
        Integer last = SLIDING.put(view, drawn);
        if ((last != null && last == drawn) || SETTLING.containsKey(dragLayer)) {
            return;
        }
        SETTLING.put(dragLayer, Boolean.TRUE);
        dragLayer.postDelayed(() -> {
            SETTLING.remove(dragLayer);
            try {
                relayout(dragLayer);
            } catch (Throwable t) {
                L.d("taskbar running: not placed again after a slide (" + t + ")");
            }
        }, SLIDE_MS);
    }

    /** Longer than ZUI's slide of its keys. */
    private static final long SLIDE_MS = 450L;
    private static final Map<View, Integer> SLIDING = new WeakHashMap<>();
    private static final Map<ViewGroup, Boolean> SETTLING = new WeakHashMap<>();

    /** How far a view's top edge is from the drag layer's. */
    private static int topIn(ViewGroup dragLayer, View view) {
        int top = 0;
        for (View v = view; v != null && v != dragLayer; ) {
            top += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return top;
    }

    /** How far a view's left edge is from the drag layer's. */
    private static int offsetIn(ViewGroup dragLayer, View view) {
        int left = 0;
        for (View v = view; v != null && v != dragLayer; ) {
            left += v.getLeft();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return left;
    }

    /**
     * The gap the launcher leaves between two of its own icons, so ours matches it.
     *
     * <p>The smallest of them, not the first. An icon we have hidden leaves its slot empty - the
     * launcher lays the rest out where they always were - so the gap measured across that hole is
     * a whole icon wider than the real one. Taking the first gap found is how our row ended up
     * with 66px between icons that should have had four.
     */
    private static int spacing(ViewGroup icons) {
        List<Integer> gaps = new ArrayList<>();
        View previous = null;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) {
                continue;
            }
            if (previous != null) {
                gaps.add(child.getLeft() - previous.getRight());
            }
            previous = child;
        }
        if (gaps.isEmpty()) {
            // Nothing of the launcher's showing to measure: this bar's gap as last measured, or
            // ZUI's own proportion - 3px beside 60px icons in the probe - at this bar's size.
            Integer known = GAPS.get(icons);
            return known != null ? known
                    : Math.max(3, Math.round(RunningIcons.iconSize(icons) / 20f));
        }
        int gap = RunningOrder.spacing(gaps, Ui.dp(icons.getContext(), 64),
                Ui.dp(icons.getContext(), 8));
        GAPS.put(icons, gap);
        return gap;
    }

    /** Each bar's icon gap and size, last measured; kept for when its icons are not there. */
    private static final Map<View, Integer> GAPS = new WeakHashMap<>();
    static final Map<View, Integer> SIZES = new WeakHashMap<>();

    /** Ours, and never a child of the launcher's icon row. */
    private static final class RunningRow extends LinearLayout {
        /** Pinned items, by key, in the order they are on screen. */
        List<String> mPins = new ArrayList<>();
        /** Open apps that are not pinned anywhere, in the order that has to hold still. */
        List<String> mRunning = new ArrayList<>();
        /** The icon size the row was built at: built again when the bar's size is known better. */
        int mSize;
        /** The look the row was built in: built again when the setting changes. */
        Theme mTheme = Theme.GLASS;
        /** What is open right now, for the marks under the icons. */
        private Set<String> mOpen = Collections.emptySet();
        private final android.graphics.Paint mMarkPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        RunningRow(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        /**
         * Night mode switched: drawn again in the new colour at once. The screen's own change
         * reaches every view of the bar's window; ZUI's call on its bar alone came too late or not
         * at all, so the marks only turned when the bar was made again.
         */
        @Override
        protected void onConfigurationChanged(android.content.res.Configuration config) {
            super.onConfigurationChanged(config);
            invalidate();
        }

        /** Between icons: the nearest one still counts as under the pointer. */
        @Override
        public boolean onHoverEvent(android.view.MotionEvent event) {
            TaskbarPreview.rowHover(this, event);
            return true;
        }

        void setOpen(Set<String> open, int display) {
            if (!open.equals(mOpen)) {
                mOpen = new LinkedHashSet<>(open);
                invalidate();
            }
            if (mTheme.retro()) {
                pressIn(display);
            }
        }

        /**
         * Retro's buttons in place of the marks, as Windows 98's taskbar has them: raised while
         * the app is open, sunken for the window in front. Each icon's state only; a button whose
         * state is unchanged is not redrawn.
         */
        private void pressIn(int display) {
            String front = RunningTasks.frontPackage(display);
            Integer frontTask = RunningTasks.FRONT_TASK.get(display);
            for (int i = 0; i < getChildCount(); i++) {
                View icon = getChildAt(i);
                boolean open = RunningOrder.anyRunning(IconInfo.packagesOfView(icon), mOpen);
                int window = TaskbarPreview.windowOf(icon, display);
                boolean inFront = open && front != null && front.equals(icon.getTag())
                        && (window < 0 || frontTask == null || window == frontTask);
                icon.setSelected(open);
                icon.setActivated(inFront);
            }
        }

        /**
         * An icon changed how it is drawn - faded, slid, dipped - so its mark has to be drawn
         * again too.
         *
         * <p>A change to a child's alpha or translation only updates the child; the row's own
         * drawing, where the marks are, is kept as it was. A newly opened app springs in from
         * alpha 0, so its mark was skipped in the one frame the row drew and stayed missing until
         * something else redrew the row - the next app opening.
         */
        @Override
        public void onDescendantInvalidated(View child, View target) {
            super.onDescendantInvalidated(child, target);
            invalidate();
        }

        /**
         * The mark under each open app, drawn with the icons.
         *
         * <p>Part of the row's own drawing, so it is part of what the scroller scrolls and moves
         * in the same frame as the icon above it - at any speed, and through a pin's slide when
         * the row is being rearranged, since it follows each icon's translation too.
         */
        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            super.dispatchDraw(canvas);
            if (mOpen.isEmpty() || !Cfg.runningMarks() || mTheme.retro()) {
                return;
            }
            // Once a frame, not once an icon: this draws on every frame of a scroll.
            int color = TaskbarMarks.markColor(getContext());
            mMarkPaint.setColor(color);
            for (int i = 0; i < getChildCount(); i++) {
                View icon = getChildAt(i);
                if (icon.getVisibility() != VISIBLE || icon.getWidth() <= 0 || icon.getAlpha() <= 0f
                        || !RunningOrder.anyRunning(IconInfo.packagesOfView(icon), mOpen)) {
                    continue;
                }
                mMarkPaint.setAlpha((int) (Color.alpha(color) * Math.min(1f, icon.getAlpha())));
                TaskbarMarks.drawMark(canvas, mMarkPaint, getContext(),
                        icon.getLeft() + icon.getTranslationX(), icon.getWidth(),
                        icon.getBottom() + icon.getTranslationY(), getHeight());
            }
        }
    }

    // --- every bar -----------------------------------------------------------

    /**
     * The monitor's bar asks for the monitor's fastest refresh rate - the rate only, never a
     * mode - so its own motion (hover, the row moving) runs at what the screen can show while
     * any app is in front.
     */
    private static void fastBar(ViewGroup dragLayer) {
        try {
            // The window's own root carries its parameters, wrapped bar or not.
            View window = dragLayer.getRootView();
            if (TaskbarTray.displayIdOf(dragLayer) == 0
                    || !(window.getLayoutParams() instanceof android.view.WindowManager.LayoutParams)
                    || dragLayer.getDisplay() == null) {
                return;
            }
            android.view.WindowManager.LayoutParams lp =
                    (android.view.WindowManager.LayoutParams) window.getLayoutParams();
            float before = lp.preferredRefreshRate;
            FrameRate.rateOnly(lp, dragLayer.getDisplay());
            if (lp.preferredRefreshRate != before) {
                ((android.view.WindowManager) dragLayer.getContext()
                        .getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(window, lp);
                FrameRate.forView(dragLayer);
                L.i("taskbar: asking for " + lp.preferredRefreshRate + " Hz on display "
                        + TaskbarTray.displayIdOf(dragLayer));
            }
        } catch (Throwable t) {
            L.d("taskbar: could not ask for the refresh rate (" + t + ")");
        }
    }

    /** Our row's part in ZUI's own folders, and in ZUI's icon changes. */
    static void install() {
        ZuiFolderCells.onAppsKnown(TaskbarRunning::rebuildPins);
    }

    /**
     * Every bar's row built again, icon by icon: its folders were made before ZUI's app list was
     * read, or the device's icon shape changed and every icon was made in the old one. Rebuilt
     * as a change of pins and open apps would rebuild them.
     */
    static void rebuildPins() {
        for (View root : Windows.roots()) {
            ViewGroup dragLayer = TaskbarTray.dragLayerOf(root);
            RunningRow row = dragLayer == null ? null : rowIn(dragLayer);
            if (row != null) {
                row.mPins = new ArrayList<>();
                row.mRunning = new ArrayList<>();
                apply(dragLayer);
                L.i("taskbar running: display " + TaskbarTray.displayIdOf(dragLayer)
                        + " row built again, its icons and folders made anew");
            }
        }
    }

    /** Our row on this bar drawn again, its marks with it. */
    static void redraw(ViewGroup dragLayer) {
        RunningRow row = rowIn(dragLayer);
        if (row != null) {
            row.invalidate();
        }
    }

    /** Re-reads every taskbar, for when what the row should hold has just changed. */
    public static void refreshAll() {
        for (View root : Windows.roots()) {
            ViewGroup dragLayer = TaskbarTray.dragLayerOf(root);
            if (dragLayer != null && rowIn(dragLayer) != null) {
                apply(dragLayer);
            }
        }
    }
    // --- keeping up -------------------------------------------------------

    private static void tick(ViewGroup dragLayer) {
        if (Boolean.TRUE.equals(TICKING.get(dragLayer))) {
            return;
        }
        TICKING.put(dragLayer, Boolean.TRUE);
        dragLayer.postDelayed(new Runnable() {
            @Override
            public void run() {
                TICKING.remove(dragLayer);
                if (dragLayer.getParent() == null || !Cfg.taskbarRunningOnly()) {
                    return;
                }
                apply(dragLayer);
            }
        }, TaskbarRebind.hearsTasks() ? REFRESH_HEARD_MS : REFRESH_MS);
    }
}
