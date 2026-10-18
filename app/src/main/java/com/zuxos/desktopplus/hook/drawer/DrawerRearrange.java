package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Rearranging both of the tablet's drawers by dragging in them, as ZUI rearranges its home
 * screen.
 *
 * <p><b>ZUX Home's drawer.</b> A drag out of it leaves the drawer the moment it starts: the home
 * screen opens for it ({@code Workspace.onDragStart} going to {@code SPRING_LOADED}). Here the
 * drawer keeps the drag while it is over the drawer - that one step of ZUI's is held back for it
 * - and a drop target of ours ({@link DrawerSession}, on ZUI's own drag controller, asked before
 * the home screen) rearranges the drawer under the finger ({@link DrawerReorder}). Carried up past
 * the drawer's grid the drag goes on to the home screen through ZUI's own step; carried over the
 * taskbar it is pinned there. An app held in one of our open folders is ZUI's own folder drag,
 * kept the same way, the folder asked first so that it rearranges itself.
 *
 * <p><b>The taskbar's drawer.</b> ZUI turns a drag there into a system drag - the floating window
 * and split screen - as soon as it starts ({@code TaskbarDragController.callOnDragStart}), unless
 * global drags are off for the drawer, when it keeps the drag in the drawer on its own controller
 * (its desktop mode's way). For a drag of a drawer entry that is what it is told, so the same
 * drop target rearranges this drawer too; the moment the finger leaves the drawer's sheet, ZUI's
 * own system drag starts from the same icon, as it would have at once, and the drawer closes.
 */
final class DrawerRearrange {

    private static final String WORKSPACE = "com.android.launcher3.Workspace";
    private static final String STATE_MANAGER = "com.android.launcher3.statemanager.StateManager";
    private static final String DROP_TARGET = "com.android.launcher3.DropTarget";
    private static final String STATES = "com.android.launcher3.LauncherState";
    private static final String TASKBAR_DRAGS =
            "com.android.launcher3.taskbar.TaskbarDragController";

    static Object sSpringLoaded;
    private static Object sNormal;
    /** ZUI's start of its system drag for the taskbar drawer's icon ({@code o0(View)}). */
    static Method sSystemDrag;
    /** The drag a drawer is keeping, or null. Main thread. */
    static DrawerSession sSession;
    /** The taskbar drawer's controller told to keep the drag being started, or null. */
    private static Object sKeeping;
    /** Our open folder the drag being kept starts in, or null. */
    private static Object sKeepingFolder;
    /**
     * The taskbar drawer's context whose open folder of ours stays open as the drag being kept
     * starts in it; null at any other time.
     */
    private static Object sKeepOpenIn;
    /** ZUI's {@code AbstractFloatingView.TYPE_FOLDER}. */
    private static int sTypeFolder = 1;
    /**
     * The taskbar drawer's controller told to keep a drag of our folder with no session for it:
     * ZUI's system drag takes apps only ({@code o0}), so the folder stays in the drawer and is
     * animated back; told again at the drag's end that the next drag is decided afresh.
     */
    private static Object sResetDisallow;

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
            sNormal = launcherStates.getField("NORMAL").get(null);
            int hooked = XposedBridge.hookAllMethods(workspace, "onDragStart",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0) {
                                beginHome(param.args[0]);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(workspace, "onDragEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    DrawerSession session = sSession;
                    if (session != null && !session.taskbar) {
                        end();
                    }
                }
            }).size();
            hooked += XposedBridge.hookAllMethods(states, "goToState", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    DrawerSession session = sSession;
                    if (session == null || session.taskbar || !session.keeping
                            || param.args.length == 0) {
                        return;
                    }
                    if (param.args[0] == sSpringLoaded || (param.args[0] == sNormal
                            && (session.folderOpened || session.inFolder()))) {
                        // The drawer keeps this drag: the home screen does not open for it, and
                        // a drop in the drawer's folder does not close the drawer.
                        param.setResult(null);
                    }
                }
            }).size();
            hooked += installTaskbar(loader);
            L.i("drawer rearrange: drags in the drawers rearrange them x" + hooked);
            return hooked;
        } catch (Throwable t) {
            L.w("drawer rearrange: not installed (" + t + ")");
            return 0;
        }
    }

    /** The taskbar drawer's drags; nothing where ZUI's start of its system drag is not found. */
    private static int installTaskbar(ClassLoader loader) {
        try {
            Class<?> drags = Reflect.findClass(TASKBAR_DRAGS, loader);
            if (drags == null) {
                L.w("drawer rearrange: the taskbar's drag controller not found - its drawer's "
                        + "drags stay ZUI's");
                return 0;
            }
            for (Method m : drags.getDeclaredMethods()) {
                if (m.getName().equals("o0") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == View.class) {
                    m.setAccessible(true);
                    sSystemDrag = m;
                }
            }
            if (sSystemDrag == null) {
                L.w("drawer rearrange: ZUI's system drag start not found - the taskbar drawer's "
                        + "drags stay ZUI's");
                return 0;
            }
            int hooked = XposedBridge.hookAllMethods(drags, "callOnDragStart",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            keepInTaskbarDrawer(param.thisObject);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sKeepOpenIn = null;
                            if (sKeeping == param.thisObject) {
                                sKeeping = null;
                                beginTaskbar(param.thisObject);
                            }
                        }
                    }).size();
            hooked += hookKeepFolderOpen(loader);
            Class<?> folder = Reflect.findClass("com.android.launcher3.folder.Folder", loader);
            if (folder != null) {
                hooked += XposedBridge.hookAllMethods(folder, "onDrop", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        quietDrop(param.thisObject, param.args);
                    }
                }).size();
                hooked += XposedBridge.hookAllMethods(folder, "onDropCompleted",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                taken(param.thisObject, param.args);
                            }
                        }).size();
            }
            hooked += hookLanding(loader);
            hooked += XposedBridge.hookAllMethods(drags, "callOnDragEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    DrawerSession session = sSession;
                    if (session != null && session.taskbar
                            && session.controller == param.thisObject) {
                        end();
                    }
                    if (sResetDisallow == param.thisObject) {
                        sResetDisallow = null;
                        Reflect.call(param.thisObject, "setDisallowGlobalDrag", false);
                    }
                }
            }).size();
            return hooked;
        } catch (Throwable t) {
            L.w("drawer rearrange: the taskbar drawer's drags stay ZUI's (" + t + ")");
            return 0;
        }
    }

    /**
     * A drag starting inside our open folder in the taskbar's drawer leaves the folder open, as
     * one inside ZUI's home-screen folders does. Kept in the drawer ({@code setDisallowGlobalDrag},
     * ours), ZUI's {@code TaskbarDragController.callOnDragStart} closes every open view of the
     * drawer's window but the drawer itself ({@code closeAllOpenViewsExcept(context,
     * TYPE_TASKBAR_ALL_APPS)}): our folder closed under the finger as the app was picked up, and
     * nothing could be moved inside it (owner on 0.0.276; "left its folder" in the log the moment
     * the drag began, 10:59:59). For that one start ZUI is told to keep folders too; its popup
     * still gives way to the drag.
     */
    private static int hookKeepFolderOpen(ClassLoader loader) {
        Class<?> floating = Reflect.findClass("com.android.launcher3.AbstractFloatingView",
                loader);
        if (floating == null) {
            L.w("drawer rearrange: ZUI's open views not found - a drag in a taskbar drawer"
                    + " folder closes it");
            return 0;
        }
        try {
            sTypeFolder = floating.getField("TYPE_FOLDER").getInt(null);
        } catch (Throwable t) {
            L.d("drawer rearrange: ZUI's folder type read as 1 (" + t + ")");
        }
        int hooked = 0;
        for (Method m : floating.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            // The two-argument form calls this one.
            if (m.getName().equals("closeAllOpenViewsExcept") && p.length == 3
                    && p[1] == boolean.class && p[2] == int.class) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object keep = sKeepOpenIn;
                        if (keep != null && param.args[0] == keep
                                && param.args[2] instanceof Integer) {
                            param.args[2] = (Integer) param.args[2] | sTypeFolder;
                        }
                    }
                });
                hooked++;
            }
        }
        if (hooked == 0) {
            L.w("drawer rearrange: ZUI's closeAllOpenViewsExcept not found - a drag in a taskbar"
                    + " drawer folder closes it");
        }
        return hooked;
    }

    private static boolean sSaidQuiet;
    private static boolean sSaidLanding;

    /**
     * A drop into one of our folders in the taskbar's drawer lands at once, as that drawer's
     * drops do, not on ZUX Home's own layer. ZUI's folder lands a dropped icon through its
     * delegate's {@code Launcher} ({@code Folder.onDrop}:
     * {@code getDragLayer().animateViewIntoPosition(dragView, view, null)}); our folders there
     * answer ZUX Home's ({@link ZuiFolders}, so that they are drawn as ZUI's), so the taskbar's
     * picture was animated on ZUX Home's layer, and at its end ZUX Home's own controller, with no
     * drag of its own, read its missing drag and ZUX Home stopped (owner, 10:20:06:
     * {@code DragController.g} from {@code DragLayer.clearAnimatedView}). A picture that is not
     * on the layer gets ZUI's own way for one it cannot animate ({@code onDrop}, not drawn yet):
     * the icon shown, and the drag ended by its own controller. ZUX Home's own drops always have
     * their picture on its layer and are left as they are.
     */
    private static int hookLanding(ClassLoader loader) {
        Class<?> layer = Reflect.findClass("com.android.launcher3.dragndrop.DragLayer", loader);
        if (layer == null) {
            return 0;
        }
        int hooked = 0;
        for (Method m : layer.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("animateViewIntoPosition") && p.length == 3
                    && p[1] == View.class && p[2] == View.class) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        landElsewhere(param);
                    }
                });
                hooked++;
            }
        }
        if (hooked == 0) {
            L.w("drawer rearrange: ZUX Home's drop landing not found - drops into the taskbar"
                    + " drawer's folders may stop ZUX Home");
        }
        return hooked;
    }

    private static void landElsewhere(XC_MethodHook.MethodHookParam param) {
        try {
            Object picture = param.args[0];
            if (!(picture instanceof View) || ((View) picture).getParent() == param.thisObject) {
                return;
            }
            param.setResult(null);
            if (param.args[1] instanceof View) {
                ((View) param.args[1]).setVisibility(View.VISIBLE);
            }
            if (!sSaidLanding) {
                sSaidLanding = true;
                L.i("drawer rearrange: a drop from another window lands at once, not on ZUX"
                        + " Home's layer");
            }
        } catch (Throwable t) {
            L.d("drawer rearrange: a landing left to ZUI (" + t + ")");
        }
    }

    /**
     * A drop into one of our folders outside ZUX Home's own window - the taskbar's drawer, where
     * this module makes the folder a drop target - without ZUI's spoken "moved" for it. ZUI's drop
     * ends by announcing it through the drag's {@code DragViewStateAnnouncer}, which only the
     * taskbar's controller makes, and only while an accessibility service is on
     * ({@code TaskbarDragController.startDrag}); the announcer asks for ZUX Home's activity
     * ({@code Launcher.getLauncher}), which the taskbar's window has none of, and ZUX Home
     * stopped on every such drop ("Cannot find BaseActivity in parent tree", owner, 15:15-15:18
     * and 23:23).
     */
    private static void quietDrop(Object folder, Object[] args) {
        try {
            if (args.length == 0 || args[0] == null || !(folder instanceof View)
                    || ZuiFolders.isLauncher(ZuiFolders.lookup(((View) folder).getContext()))
                    || Reflect.field(args[0], "stateAnnouncer") == null) {
                return;
            }
            DrawerSession.setField(args[0], "stateAnnouncer", null);
            if (!sSaidQuiet) {
                sSaidQuiet = true;
                L.i("drawer rearrange: a drop into a folder outside ZUX Home's window goes"
                        + " without ZUI's spoken announcement, which needs ZUX Home's activity");
            }
        } catch (Throwable t) {
            L.d("drawer rearrange: a folder's drop left as ZUI has it (" + t + ")");
        }
    }

    /**
     * A drag out of our folder in the taskbar's drawer let go: what ZUI's own source tells its
     * controller ({@code n0}'s {@code b0}), so a drop the folder took is not animated back.
     */
    private static void taken(Object folder, Object[] args) {
        DrawerSession session = sSession;
        if (session != null && session.taskbar && session.inFolder() && args.length == 3
                && Boolean.TRUE.equals(args[2])) {
            TaskbarFolderDrag.setTaken(session.controller, true);
        }
    }

    /** A drag starting on ZUX Home's controller: kept when it is one out of its drawer. */
    private static void beginHome(Object drag) {
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
            // An app held in our open folder of the drawer: ZUI's own folder drag, the folder
            // its source, kept in the drawer as the drawer's own are (ZuiFolders.holdInside).
            Object inFolder = apps != source && ZuiFolders.isOurs(source)
                    && Boolean.TRUE.equals(Reflect.call(source, "isOpen")) ? source : null;
            if (apps != source && inFolder == null) {
                // A drag from anywhere but ZUX Home's drawer: not ours to keep, nothing to say.
                return;
            }
            android.view.Display display = ((View) source).getDisplay();
            Object info = Reflect.field(drag, "dragInfo");
            Item folder = inFolder == null ? ZuiFolderHome.folderDragged(info) : null;
            String key = folder != null ? folder.key() : inFolder != null ? keyFor(null, info)
                    : NativeDrawerHooks.keyOfEntry(info);
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
                if (inFolder != null && controller != null) {
                    // ZUI's stand-in for our folder's app is no item of its home screen: the
                    // drag is called off and the folder puts the app back, once ZUI has told
                    // every listener of the start.
                    ((View) source).post(() -> Reflect.call(controller, "cancelDrag"));
                }
                return;
            }
            Object layer = Reflect.call(launcher, "getDragLayer");
            DrawerSession session = new DrawerSession(false, launcher, (View) apps, (View) apps,
                    layer instanceof View ? (View) layer : (View) apps, (ViewGroup) grid,
                    controller, reorder, key, drag, display.getDisplayId());
            if (Cfg.drawerFolderSpring() && inFolder == null) {
                reorder.springFolders(session::springOpen);
            }
            start(session, source.getClass().getClassLoader());
            if (inFolder != null) {
                // Asked before the drawer: its apps move aside under the finger, ZUI's way.
                session.inFolder(inFolder);
            }
        } catch (Throwable t) {
            sSession = null;
            L.w("drawer rearrange: a drag left to ZUI (" + t + ")");
        }
    }

    /**
     * Before ZUI's taskbar drag controller decides where a drag goes: one of an entry of its
     * drawer on the tablet stays in the drawer, as ZUI keeps them in its desktop mode
     * ({@code setDisallowGlobalDrag}).
     */
    private static void keepInTaskbarDrawer(Object controller) {
        try {
            sKeeping = null;
            sKeepOpenIn = null;
            if (!Cfg.drawerRearrange() || !Cfg.taskbarDrawerRearrange()) {
                return;
            }
            Object activity = Reflect.field(controller, "mActivity");
            if (!(activity instanceof Context)
                    || !activity.getClass().getName().endsWith("TaskbarOverlayContext")) {
                return;
            }
            Context ctx = (Context) activity;
            Object disallowed = Reflect.field(controller, "o");
            if (!Boolean.FALSE.equals(disallowed)
                    || ctx.getDisplay() == null || ctx.getDisplay().getDisplayId() != 0
                    || Settings.System.getInt(ctx.getContentResolver(), "zui_ov_desktop_mode",
                    0) == 1) {
                // Already kept in the drawer by ZUI, another screen, or ZUI's desktop mode.
                return;
            }
            Object drag = Reflect.field(controller, "mDragObject");
            Object original = drag == null ? null : Reflect.field(drag, "originalView");
            Object info = drag == null ? null : Reflect.field(drag, "dragInfo");
            View view = original instanceof View ? (View) original : null;
            View apps = view == null ? null : appsViewOf(view);
            Object folder = apps == null && view != null ? openFolderOf(view) : null;
            if ((apps == null && folder == null) || keyFor(view, info) == null
                    || (apps != null && Boolean.TRUE.equals(Reflect.call(apps, "isSearching")))) {
                keepFolderAnyway(controller, view);
                return;
            }
            if (folder != null && !fromFolder(controller, drag, folder, view, info)) {
                return;
            }
            Reflect.call(controller, "setDisallowGlobalDrag", true);
            sKeeping = controller;
            sKeepingFolder = folder;
            sKeepOpenIn = folder != null ? activity : null;
        } catch (Throwable t) {
            sKeeping = null;
            sKeepingFolder = null;
            sKeepOpenIn = null;
            L.d("drawer rearrange: a taskbar drawer drag left to ZUI (" + t + ")");
        }
    }

    /** ZUI kept a drag in the taskbar's drawer for us: the drawer rearranges under it. */
    private static void beginTaskbar(Object controller) {
        Object folder = sKeepingFolder;
        sKeepingFolder = null;
        try {
            end();
            Object drag = Reflect.field(controller, "mDragObject");
            View original = (View) Reflect.field(drag, "originalView");
            Object info = Reflect.field(drag, "dragInfo");
            String key = keyFor(original, info);
            // From a folder, the drawer is found through the folder: ZUI's folder has already
            // taken the app's icon off its pages (Folder.onDragStart, the folder being the
            // drag's source), so the icon is in no window - and the drag went on as ZUI's
            // floating window, closing the drawer (owner on 0.0.274, 10:20:42).
            View apps = folder == null ? appsViewOf(original)
                    : appsIn(((View) folder).getRootView());
            Object activity = Reflect.field(controller, "mActivity");
            Object layer = Reflect.call(activity, "getDragLayer");
            Object list = apps == null ? null : Reflect.call(apps, "getActiveRecyclerView");
            ViewGroup grid = list instanceof ViewGroup ? (ViewGroup) list : null;
            DrawerReorder reorder = grid == null ? null : DrawerReorder.over(grid, key);
            if (apps == null || !(layer instanceof View) || reorder == null) {
                if (ZuiFolders.folderOfIcon(original) != null || folder != null) {
                    // A folder has no system drag of ZUI's, and an app from one goes back into
                    // it: it stays in the drawer, and goes back.
                    sResetDisallow = controller;
                    L.i("drawer rearrange: the taskbar drawer's drag of " + key + " goes back -"
                            + " its place in the drawer not found");
                    return;
                }
                L.i("drawer rearrange: the taskbar drawer's drag of " + key + " goes on as ZUI's"
                        + " - its place in the drawer not found");
                DrawerSession.systemDrag(controller, original);
                return;
            }
            Object sheet = Reflect.call(apps, "getVisibleContainerView");
            DrawerSession session = new DrawerSession(true, activity, apps,
                    sheet instanceof View ? (View) sheet : apps, (View) layer, grid, controller,
                    reorder, key, drag, 0);
            session.folderDragged = ZuiFolders.folderOfIcon(original);
            if (Cfg.drawerFolderSpring() && folder == null) {
                reorder.springFolders(session::springOpen);
            }
            start(session, controller.getClass().getClassLoader());
            if (folder != null) {
                // Asked before the drawer: its apps move aside under the finger, ZUI's way.
                session.inFolder(folder);
            }
        } catch (Throwable t) {
            sSession = null;
            L.w("drawer rearrange: a taskbar drawer drag left to ZUI (" + t + ")");
            // ZUI was told to keep it: it goes on as ZUI's after all, and the next drag is
            // decided afresh.
            Object drag = Reflect.field(controller, "mDragObject");
            Object original = drag == null ? null : Reflect.field(drag, "originalView");
            if (folder != null || (original instanceof View
                    && ZuiFolders.folderOfIcon((View) original) != null)) {
                // A folder has no system drag of ZUI's, and an app from one goes back into it:
                // it stays in the drawer, and goes back.
                sResetDisallow = controller;
            } else if (!(original instanceof View)
                    || !DrawerSession.systemDrag(controller, (View) original)) {
                Reflect.call(controller, "setDisallowGlobalDrag", false);
            }
        }
    }

    /**
     * A drag of our folder not kept for rearranging (a search showing, say) stays in the drawer
     * all the same: ZUI's system drag is for apps only ({@code o0} reads an app from the icon).
     */
    private static void keepFolderAnyway(Object controller, View view) {
        if (view != null && ZuiFolders.folderOfIcon(view) != null) {
            Reflect.call(controller, "setDisallowGlobalDrag", true);
            sResetDisallow = controller;
        }
    }

    /** What the drag is of: our folder, an app of the drawer, or an app in one of our folders. */
    private static String keyFor(View view, Object info) {
        Item folder = view == null ? null : ZuiFolders.folderOfIcon(view);
        if (folder != null) {
            return folder.key();
        }
        Item child = ZuiFolders.childOf(info);
        return child != null ? child.key() : NativeDrawerHooks.keyOfEntry(info);
    }

    /** Our open folder an app's icon is in, or null. */
    private static Object openFolderOf(View icon) {
        for (android.view.ViewParent p = icon.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().equals("com.android.launcher3.folder.Folder")) {
                return ZuiFolders.isOurs(p) && Boolean.TRUE.equals(Reflect.call(p, "isOpen"))
                        ? p : null;
            }
        }
        return null;
    }

    /**
     * A drag of an app inside our open folder in the taskbar's drawer, as ZUI's home screen
     * starts one ({@code Folder.startDrag}, through {@code Workspace.beginDragShared}): the folder
     * is the drag's source, holds the app's place ({@code Folder.p}, its rank; {@code t}, its
     * view) and hears the drag start, when it takes the app out of its pages
     * ({@code Folder.onDragStart}). Its delegate in the taskbar's drawer starts no drag
     * ({@code LauncherDelegate$b.b}), so it is told here. Let go in the folder, ZUI puts the app at
     * its new place and saves the order ({@code Folder.onDrop}); dragged out of it, ZUI's folder
     * closes ({@code onDragExit}) and the app goes on in the drawer, as in ZUX Home's drawer
     * ({@link DrawerSession}); let go out of the drawer, the folder puts it back
     * ({@code onDropCompleted}) - ZUI's floating window has no icon to start from, the folder
     * having taken it off its pages.
     */
    private static boolean fromFolder(Object controller, Object drag, Object folder, View view,
            Object info) {
        try {
            Object rank = Reflect.field(info, "rank");
            if (!(rank instanceof Integer)) {
                return false;
            }
            DrawerSession.setField(drag, "dragSource", folder);
            DrawerSession.setField(folder, "p", rank);
            DrawerSession.setField(folder, "t", view);
            Reflect.call(controller, "addDragListener", folder);
            // ZUI's folder speaks each new place through the drag's announcer
            // (Folder.onDragOver), which needs ZUX Home's activity: gone for this drag, as at the
            // drop (quietDrop).
            if (Reflect.field(drag, "stateAnnouncer") != null) {
                DrawerSession.setField(drag, "stateAnnouncer", null);
            }
            return true;
        } catch (Throwable t) {
            L.d("drawer rearrange: the folder's drag left to ZUI (" + t + ")");
            return false;
        }
    }

    /** ZUI's drawer shown in a window: its all-apps container. */
    private static View appsIn(View root) {
        for (View view : Reflect.findByClassFragments(root, "AllAppsContainerView")) {
            if (view.isShown()) {
                return view;
            }
        }
        return null;
    }

    /** The drawer's own apps view the icon is in: ZUI's all-apps container. */
    private static View appsViewOf(View icon) {
        for (android.view.ViewParent p = icon.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().endsWith("AllAppsContainerView")) {
                return (View) p;
            }
        }
        return null;
    }

    private static void start(DrawerSession session, ClassLoader loader) throws Exception {
        session.target = Proxy.newProxyInstance(loader,
                new Class<?>[]{Class.forName(DROP_TARGET, false, loader)},
                (proxy, method, args) -> session.call(proxy, method.getName(), args,
                        method.getReturnType()));
        Reflect.call(session.controller, "addDropTarget", session.target);
        sSession = session;
        session.startTrace();
        L.i("drawer rearrange: " + (session.taskbar ? "the taskbar's" : "ZUX Home's")
                + " drawer keeps the drag of " + session.key);
    }

    /** The drag is over: our drop target goes with it. */
    static void end() {
        DrawerSession session = sSession;
        sSession = null;
        if (session != null) {
            session.end();
        }
    }
}
