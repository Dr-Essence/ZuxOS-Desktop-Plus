package com.zuxos.desktopplus.hook.drawer;

import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Reflect;

import java.util.List;

/**
 * The taskbar's drawer opened again under a drag that had left it - ZUI's floating window and
 * split screen under way - so the app can still be put in a new place in it.
 *
 * <p>The drag is the system's by now, not ZUI's controller's: its moves reach the drawer's
 * window as drag events ({@code ZuiBarDrop}'s catcher), and they drive the drawer's
 * rearranging ({@link DrawerReorder}) as ZUI's own drag does while the drawer keeps it.
 */
public final class DrawerReopen {

    private static DrawerReorder sReorder;
    private static View sGrid;
    /** The drag was over the drawer's panel at its last move. */
    private static boolean sOver;

    private DrawerReopen() {
    }

    /**
     * The drag at ({@code sx}, {@code sy}) on the screen, in the window of {@code root}: true when
     * it is over the reopened drawer's panel and rearranges it.
     */
    public static boolean over(View root, String key, float sx, float sy) {
        View apps = appsIn(root);
        Object grid = apps == null ? null : Reflect.call(apps, "getActiveRecyclerView");
        Object sheet = apps == null ? null : Reflect.call(apps, "getVisibleContainerView");
        View panel = sheet instanceof View ? (View) sheet : apps;
        if (!(grid instanceof ViewGroup) || panel == null || !panel.isShown()) {
            sOver = false;
            return false;
        }
        int[] at = new int[2];
        panel.getLocationOnScreen(at);
        sOver = sx >= at[0] && sx <= at[0] + panel.getWidth() && sy >= at[1]
                && sy <= at[1] + panel.getHeight();
        if (sReorder == null || sGrid != grid) {
            sGrid = (View) grid;
            sReorder = DrawerReorder.over((ViewGroup) grid, key);
        }
        if (sReorder == null) {
            sOver = false;
            return false;
        }
        if (sOver) {
            sReorder.moveToScreen(sx, sy);
        } else {
            sReorder.cancel();
        }
        return sOver;
    }

    /** Let go: over the reopened drawer, the app goes where the gap is; true when it did. */
    public static boolean drop() {
        DrawerReorder reorder = sReorder;
        boolean over = sOver;
        sReorder = null;
        sGrid = null;
        sOver = false;
        if (reorder == null) {
            return false;
        }
        if (!over) {
            reorder.cancel();
            return false;
        }
        reorder.drop();
        return true;
    }

    /** The drag is over, or the drawer gone: nothing left moved. */
    public static void reset() {
        sApps = null;
        DrawerReorder reorder = sReorder;
        sReorder = null;
        sGrid = null;
        sOver = false;
        if (reorder != null) {
            reorder.cancel();
        }
    }

    /** The drawer found last, kept so a move does not walk the window's views again. */
    private static java.lang.ref.WeakReference<View> sApps;

    private static View appsIn(View root) {
        View known = sApps != null ? sApps.get() : null;
        if (known != null && known.isShown() && known.getRootView() == root) {
            return known;
        }
        List<View> found = Reflect.findByClassFragments(root, "AllAppsContainerView");
        for (View view : found) {
            if (view.isShown()) {
                sApps = new java.lang.ref.WeakReference<>(view);
                return view;
            }
        }
        return null;
    }
}
