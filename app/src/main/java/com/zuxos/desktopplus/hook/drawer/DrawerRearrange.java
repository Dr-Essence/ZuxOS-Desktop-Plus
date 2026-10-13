package com.zuxos.desktopplus.hook.drawer;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Rearranging ZUX Home's own drawer by dragging in it, as ZUI rearranges its home screen.
 *
 * <p>A drag out of ZUI's drawer leaves the drawer the moment it starts: the home screen opens
 * for it ({@code Workspace.onDragStart} going to {@code SPRING_LOADED}). Here the drawer keeps
 * the drag while it is over the drawer - that one step of ZUI's is held back for it - and a drop
 * target of ours over the drawer ({@code DropTarget}, on ZUI's own drag controller, asked before
 * the home screen) rearranges the drawer under the finger ({@link DrawerReorder}): gaps opening,
 * apps folding into apps and folders, apps from our folders taken out. Let go, ZUI's dragged icon
 * flies into its new place and the drag ends as ZUI ends its own after a drop that animates
 * ({@code deferDragViewCleanupPostAnimation}). Carried up past the drawer's grid, to the top edge,
 * the drag goes on to the home screen through ZUI's own step, as before.
 */
final class DrawerRearrange {

    private static final String WORKSPACE = "com.android.launcher3.Workspace";
    private static final String STATE_MANAGER = "com.android.launcher3.statemanager.StateManager";
    private static final String DROP_TARGET = "com.android.launcher3.DropTarget";
    private static final String STATES = "com.android.launcher3.LauncherState";

    private static Object sSpringLoaded;
    /** The drag the drawer is keeping, or null. Main thread. */
    private static Session sSession;

    private DrawerRearrange() {
    }

    static int install(ClassLoader loader) {
        try {
            Class<?> workspace = Reflect.findClass(WORKSPACE, loader);
            Class<?> states = Reflect.findClass(STATE_MANAGER, loader);
            Class<?> launcherStates = Reflect.findClass(STATES, loader);
            if (workspace == null || states == null || launcherStates == null
                    || Reflect.findClass(DROP_TARGET, loader) == null) {
                L.w("drawer rearrange: ZUI's drag parts not found - drags leave the drawer");
                return 0;
            }
            sSpringLoaded = launcherStates.getField("SPRING_LOADED").get(null);
            int hooked = XposedBridge.hookAllMethods(workspace, "onDragStart",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0) {
                                begin(param.args[0]);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(workspace, "onDragEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    end();
                }
            }).size();
            hooked += XposedBridge.hookAllMethods(states, "goToState", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Session session = sSession;
                    if (session != null && session.keeping && param.args.length > 0
                            && param.args[0] == sSpringLoaded) {
                        // The drawer keeps this drag: the home screen does not open for it.
                        param.setResult(null);
                    }
                }
            }).size();
            L.i("drawer rearrange: drags in ZUX Home's drawer rearrange it x" + hooked);
            return hooked;
        } catch (Throwable t) {
            L.w("drawer rearrange: not installed (" + t + ")");
            return 0;
        }
    }

    /** A drag starting: kept by the drawer when it is one out of ZUX Home's drawer. */
    private static void begin(Object drag) {
        try {
            end();
            if (!Cfg.drawerRearrange()) {
                return;
            }
            Object source = Reflect.field(drag, "dragSource");
            if (!(source instanceof View)) {
                return;
            }
            // The launcher whose drawer the drag came out of: the source's own, not the last one
            // made, which may be the monitor's.
            Object launcher = ZuiFolders.lookup(((View) source).getContext());
            Object apps = Reflect.call(launcher, "getAppsView");
            if (apps != source) {
                // A drag from anywhere but ZUX Home's drawer: not ours to keep, nothing to say.
                return;
            }
            android.view.Display display = ((View) source).getDisplay();
            Object info = Reflect.field(drag, "dragInfo");
            Item folder = ZuiFolderHome.folderDragged(info);
            String key = folder != null ? folder.key() : NativeDrawerHooks.keyOfEntry(info);
            String why = null;
            if (display == null
                    || display.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
                why = "not on the tablet's screen";
            } else if (!Boolean.TRUE.equals(Reflect.call(launcher, "isInAllAppsState"))) {
                // ZUI's own question (Launcher.isInAllAppsState). Its isInState is a default
                // method of an interface, which a search of the classes never finds.
                why = "the drawer is not open";
            } else if (Boolean.TRUE.equals(Reflect.call(apps, "isSearching"))) {
                why = "a search is showing";
            } else if (key == null) {
                why = "not an app or folder of the drawer";
            }
            Object grid = why == null ? Reflect.call(apps, "getActiveRecyclerView") : null;
            DrawerReorder reorder = grid instanceof ViewGroup
                    ? DrawerReorder.over((ViewGroup) grid, key) : null;
            Object controller = Reflect.call(launcher, "getDragController");
            if (why == null && (reorder == null || controller == null)) {
                why = reorder == null ? "its place in the drawer not found"
                        : "ZUI's drag controller not found";
            }
            if (why != null) {
                L.i("drawer rearrange: a drag of " + key + " left to ZUI - " + why);
                return;
            }
            ClassLoader loader = source.getClass().getClassLoader();
            Session session = new Session(launcher, (View) apps, (ViewGroup) grid, controller,
                    reorder, key);
            session.target = Proxy.newProxyInstance(loader,
                    new Class<?>[]{Class.forName(DROP_TARGET, false, loader)},
                    (proxy, method, args) -> session.call(proxy, method.getName(), args,
                            method.getReturnType()));
            Reflect.call(controller, "addDropTarget", session.target);
            sSession = session;
            L.i("drawer rearrange: the drawer keeps the drag of " + key);
        } catch (Throwable t) {
            sSession = null;
            L.w("drawer rearrange: a drag left to ZUI (" + t + ")");
        }
    }

    /** The drag is over: our drop target goes with it. */
    private static void end() {
        Session session = sSession;
        sSession = null;
        if (session != null) {
            boolean kept = session.keeping;
            session.keeping = false;
            Reflect.call(session.controller, "removeDropTarget", session.target);
            if (!session.dropped) {
                session.reorder.cancel();
            }
            L.i("drawer rearrange: the drag of " + session.key + " ended - "
                    + (session.dropped ? session.reorder.outcome()
                    : kept ? "let go of nowhere in the drawer, or cancelled - no change"
                    : "on the home screen's side, as ZUI has it"));
        }
    }

    /** One drag kept by the drawer. */
    private static final class Session {
        final Object launcher;
        final View apps;
        final ViewGroup grid;
        final Object controller;
        final DrawerReorder reorder;
        final String key;
        Object target;
        /** The drawer keeps it; false once it has gone on to the home screen. */
        boolean keeping = true;
        boolean dropped;

        Session(Object launcher, View apps, ViewGroup grid, Object controller,
                DrawerReorder reorder, String key) {
            this.launcher = launcher;
            this.apps = apps;
            this.grid = grid;
            this.controller = controller;
            this.reorder = reorder;
            this.key = key;
        }

        /** Our {@code DropTarget}. */
        Object call(Object proxy, String name, Object[] args, Class<?> returns) {
            try {
                switch (name) {
                    case "isDropEnabled":
                        return keeping;
                    case "acceptDrop":
                        return keeping;
                    case "getHitRectRelativeToDragLayer":
                        hitRect((Rect) args[0]);
                        return null;
                    case "onDragOver":
                        over(args[0]);
                        return null;
                    case "onDragExit":
                        // ZUI's drop leaves the target first (DragController.drop: onDragExit,
                        // then acceptDrop and onDrop), with the drag already complete: the
                        // gap stays for the drop. Any other exit puts the drawer back.
                        if (!Boolean.TRUE.equals(Reflect.field(args[0], "dragComplete"))) {
                            reorder.cancel();
                        }
                        return null;
                    case "getDropView":
                        // The interface's own answer is "this", a view; ours is ZUI's drag
                        // layer, so the drag's place reaches us as ZUI's layer has it.
                        return dragLayer();
                    case "onDrop":
                        drop(args[0]);
                        return null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return args != null && proxy == args[0];
                    case "toString":
                        return "DrawerRearrange.target";
                    default:
                        break;
                }
            } catch (Throwable t) {
                L.d("drawer rearrange: " + name + " failed (" + t + ")");
            }
            return returns == boolean.class ? Boolean.FALSE : null;
        }

        /** The whole drawer, where ZUI's drag layer is. */
        private void hitRect(Rect out) {
            int[] at = new int[2];
            int[] layer = new int[2];
            apps.getLocationOnScreen(at);
            dragLayer().getLocationOnScreen(layer);
            out.set(at[0] - layer[0], at[1] - layer[1],
                    at[0] - layer[0] + apps.getWidth(), at[1] - layer[1] + apps.getHeight());
        }

        private View dragLayer() {
            Object layer = Reflect.call(launcher, "getDragLayer");
            return layer instanceof View ? (View) layer : apps;
        }

        /** The drag moved over the drawer: rearranged under it, or gone on above the grid. */
        private void over(Object drag) {
            if (!keeping) {
                return;
            }
            int[] layer = new int[2];
            dragLayer().getLocationOnScreen(layer);
            float sx = layer[0] + intField(drag, "x");
            float sy = layer[1] + intField(drag, "y");
            int[] grid = new int[2];
            this.grid.getLocationOnScreen(grid);
            if (sy < grid[1] - Ui.dp(apps.getContext(), 8)) {
                toHome();
                return;
            }
            reorder.moveToScreen(sx, sy);
        }

        /** Up past the grid: on to the home screen, through ZUI's own step. */
        private void toHome() {
            keeping = false;
            reorder.cancel();
            Reflect.call(controller, "removeDropTarget", target);
            Object states = Reflect.call(launcher, "getStateManager");
            Reflect.call(states, "goToState", sSpringLoaded);
            L.i("drawer rearrange: the drag of " + key + " goes on to the home screen");
        }

        /** Let go over the drawer: rearranged, and ZUI's icon flies into its place. */
        private void drop(Object drag) {
            dropped = true;
            View dragged = (View) Reflect.field(drag, "dragView");
            boolean[] landing = new boolean[1];
            // ZUI ends the drag itself unless the drop animates: ours does, so it is ours to end.
            setBoolean(drag, "deferDragViewCleanupPostAnimation", dragged != null);
            try {
                reorder.drop((cell, into) -> {
                    if (!landing[0]) {
                        landing[0] = true;
                        land(dragged, cell, into);
                    }
                });
            } catch (Throwable t) {
                // ZUI ends it after all, at once.
                landing[0] = true;
                setBoolean(drag, "deferDragViewCleanupPostAnimation", false);
                L.w("drawer rearrange: the drop not kept (" + t + ")");
                return;
            }
            if (dragged != null) {
                // Ended however the drawer's next frame goes.
                dragged.postDelayed(() -> {
                    if (!landing[0]) {
                        landing[0] = true;
                        land(dragged, null, false);
                    }
                }, 1000L);
            }
        }

        /**
         * ZUI's dragged icon onto the cell it now has, or into the folder it went in; then the
         * drag ends, as ZUI ends one whose drop animated ({@code DragView.remove},
         * {@code callOnDragEnd}).
         */
        private void land(View dragged, View cell, boolean into) {
            if (dragged == null) {
                return;
            }
            boolean[] done = new boolean[1];
            long start = android.os.SystemClock.uptimeMillis();
            String expected = cell == null ? null : DrawerReorder.keyOfCell(cell);
            Runnable finish = () -> {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                // Read-only trace for the flashing on a drop (owner, 22:53): whether the cell
                // the icon lands on still shows what it showed when the flight began.
                String now = cell == null ? null : DrawerReorder.keyOfCell(cell);
                L.i("drawer rearrange: trace: the icon landed after "
                        + (android.os.SystemClock.uptimeMillis() - start) + " ms"
                        + (cell == null ? ", on no cell" : expected != null && expected.equals(now)
                        ? ", its cell unchanged" : ", its cell re-bound from " + expected + " to "
                        + now) + (into ? " (into a folder)" : ""));
                Reflect.call(dragged, "remove");
                if (cell != null && !into) {
                    cell.setAlpha(1f);
                }
                Reflect.call(controller, "callOnDragEnd");
            };
            if (cell == null) {
                finish.run();
                return;
            }
            int[] from = new int[2];
            int[] to = new int[2];
            dragged.getLocationOnScreen(from);
            cell.getLocationOnScreen(to);
            float fromX = from[0] + dragged.getWidth() * dragged.getScaleX() / 2f;
            float fromY = from[1] + dragged.getHeight() * dragged.getScaleY() / 2f;
            float icon = Math.min(cell.getWidth(), cell.getHeight());
            float toX = to[0] + cell.getWidth() / 2f;
            float toY = to[1] + icon / 2f;
            float fit = icon * 0.72f / Math.max(1, dragged.getWidth());
            float scale = into ? 0.35f : Math.max(0.5f, Math.min(1.5f, fit));
            dragged.animate()
                    .translationX(dragged.getTranslationX() + toX - fromX)
                    .translationY(dragged.getTranslationY() + toY - fromY)
                    .scaleX(scale).scaleY(scale).alpha(into ? 0f : 1f)
                    .setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING_FIRM)
                    .withEndAction(finish).start();
            // Never left hanging, whatever happens to the animation.
            dragged.postDelayed(finish, Motion.SPRING_MS + 400L);
        }

        private static int intField(Object o, String name) {
            Object value = Reflect.field(o, name);
            return value instanceof Integer ? (Integer) value : 0;
        }

        private static void setBoolean(Object o, String name, boolean value) {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.setBoolean(o, value);
                    return;
                } catch (NoSuchFieldException e) {
                    // Up the hierarchy.
                } catch (Throwable t) {
                    return;
                }
            }
        }
    }
}
