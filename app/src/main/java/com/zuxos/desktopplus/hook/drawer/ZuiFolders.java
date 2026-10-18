package com.zuxos.desktopplus.hook.drawer;

import android.content.ComponentName;
import android.content.Context;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.IconPress;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.hook.ZuiApps;
import com.zuxos.desktopplus.hook.ZuiMenu;
import com.zuxos.desktopplus.logic.DrawerArrange;
import com.zuxos.desktopplus.model.DrawerStore;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Our drawer folders as ZUI's own folders, in ZUI's drawers on the tablet.
 *
 * <p>ZUI keeps folders to its home screen and taskbar, but its folder code runs for any of its
 * contexts: {@code FolderIcon.inflateFolderAndIcon} builds the icon and its {@code Folder} from a
 * {@code FolderInfo}, and outside {@code Launcher} the folder gets ZUI's taskbar delegate. So each
 * of our folders becomes a {@code FolderInfo} - its apps made by ZUI from the drawer's own entries
 * ({@code AppInfo.makeWorkspaceItem}) - and takes its place in ZUI's list as a {@code FolderIcon}:
 * its look, its opening and closing, its title editing and its pages are ZUI's.
 *
 * <p>In ZUI's list the entry keeps the icon span but is born with a cell type of its own
 * ({@link #TYPE}), which ZUI's adapter has no case for; its cell is made and filled here. Whatever the folder would
 * write goes to our model, never ZUI's database ({@link ZuiFolderWrites}). Holding an app inside
 * shows ZUI's app popup, as ZUI does for a hold without a drag. The monitor keeps our own folder
 * window until its own step.
 */
public final class ZuiFolders {

    /** Our cell type: the icon bit, so it spans one cell, and a bit ZUI never uses. */
    static final int TYPE = 2 | (1 << 16);
    /** Our folders' ids: far below every id and container ZUI uses. */
    public static final int ID_BASE = -1_000_000;

    private static final String ADAPTER = "com.android.launcher3.allapps.BaseAllAppsAdapter";
    private static final String FOLDER = "com.android.launcher3.folder.Folder";
    private static final String FOLDER_ICON = "com.android.launcher3.folder.FolderIcon";
    private static final String FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";
    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";

    /**
     * One of our folders as ZUI's, in one of ZUI's contexts, and what it was built with: its name
     * and apps ({@link #contentOf}). Made again only when those change - not on every save of the
     * drawer, which re-inflated every folder of the drawer on each drop of a rearrange (frames
     * dropped to 72 a second on a 144 Hz screen, 22:53) and drew each new one a frame at ZUI's
     * home size before it was aligned (the top row's folders flashing, 22:53, 4.26 s).
     */
    private static final class Built {
        final View icon;
        String content;

        Built(View icon, String content) {
            this.icon = icon;
            this.content = content;
        }
    }

    /** A folder's name and apps, in order: what its ZUI icon shows. */
    private static String contentOf(Item folder) {
        StringBuilder out = new StringBuilder(folder.label != null ? folder.label : "");
        for (Item child : folder.children) {
            out.append('|').append(child.key());
        }
        return out.toString();
    }

    /** Per ZUI context (ZUX Home's, the taskbar drawer's): its folders by our folder id. */
    private static final Map<Object, Map<String, Built>> BUILT = new WeakHashMap<>();
    /** Our folder behind each id handed to ZUI. */
    private static final Map<Integer, Item> BY_ID = new HashMap<>();
    /** The app of ours each of ZUI's items inside our folders stands for. */
    private static final Map<Object, Item> CHILDREN = new WeakHashMap<>();
    /** The drawer's own entries for the apps filed in folders, by our key, as last seen. */
    private static Map<String, Object> sFiled = new HashMap<>();

    private static Field sViewType;
    private static Field sItemInfo;
    private static Field sActivity;
    private static Field sFolderInfo;
    private static Field sFolderActivity;
    private static Constructor<?> sHolder;
    private static Method sType;
    private static Field sItemView;
    private static Method sInflate;
    private static boolean sOff;
    private static boolean sSaidIcon;

    private ZuiFolders() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> adapter = Reflect.findClass(ADAPTER, loader);
            Class<?> item = Reflect.findClass(ADAPTER + "$AdapterItem", loader);
            Class<?> holder = Reflect.findClass(ADAPTER + "$ViewHolder", loader);
            Class<?> icon = Reflect.findClass(FOLDER_ICON, loader);
            Class<?> folder = Reflect.findClass(FOLDER, loader);
            if (adapter == null || item == null || holder == null || icon == null
                    || folder == null) {
                off("ZUI's drawer or folder classes not found");
                return;
            }
            sViewType = item.getDeclaredField("viewType");
            sViewType.setAccessible(true);
            sItemInfo = item.getDeclaredField("itemInfo");
            sItemInfo.setAccessible(true);
            sActivity = adapter.getDeclaredField("mActivityContext");
            sActivity.setAccessible(true);
            sFolderInfo = folder.getDeclaredField("mInfo");
            sFolderInfo.setAccessible(true);
            sHolder = holder.getConstructor(View.class);
            sType = holder.getMethod("getItemViewType");
            sItemView = holder.getField("itemView");
            for (Method m : icon.getDeclaredMethods()) {
                if (m.getName().equals("inflateFolderAndIcon") && m.getParameterCount() == 4) {
                    sInflate = m;
                }
            }
            if (sInflate == null) {
                off("ZUI's FolderIcon.inflateFolderAndIcon not found");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(adapter, "onCreateViewHolder",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 2 && param.args[1] instanceof Integer
                                    && (Integer) param.args[1] == TYPE) {
                                param.setResult(cell((ViewGroup) param.args[0], param.thisObject));
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(adapter, "onBindViewHolder",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length >= 2 && param.args[1] instanceof Integer
                                    && bind(param.thisObject, param.args[0],
                                    (Integer) param.args[1])) {
                                param.setResult(null);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(item, "asApp", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    born(param.getResult());
                }
            }).size();
            hooked += XposedBridge.hookAllMethods(folder, "onLongClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && param.args[0] instanceof View
                            && holdInside(param.thisObject, (View) param.args[0])) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            }).size();
            hooked += hookAnimator(loader, folder);
            hooked += hookHomeContext(loader);
            hooked += hookBack(folder);
            hooked += hookDrawerClose(loader);
            hooked += hookHeldOut(loader);
            hooked += hookNoEnlarge(loader);
            hooked += hookDragPicture(loader);
            ZuiFolderWrites.install(loader);
            ZuiFolderAdd.install(loader, folder);
            hooked += ZuiFolderHome.install(loader);
            hooked += DrawerRearrange.install(loader);
            DrawerSelect.install(loader);
            ZuiFolderBlur.install(folder);
            Health.hooked("drawer: our folders as ZUI's", hooked);
            L.i("zui folders: our drawer folders as ZUI's own on the tablet x" + hooked);
        } catch (Throwable t) {
            off("not installed (" + t + ")");
        }
    }

    /**
     * ZUI's "Enlarge folder" ({@code SystemShortcut.SWITCH_FOLDER}) is never offered for one of
     * our folders. ZUI's factory makes it for any folder outside desktop mode, and its click
     * resizes the folder on the home screen's grid ({@code SwitchFolder.onClick} casts the icon's
     * layout params to {@code CellLayoutLayoutParams}): for our folders, in a drawer or on the
     * bar, that stopped ZUX Home (21:55:31, 21:58:23). The factory, asked about one of ours,
     * answers with no shortcut - ZUI's decision, not its menu undone afterwards. Its other folder
     * shortcut, Rename, only opens the folder and stays.
     */
    private static int hookNoEnlarge(ClassLoader loader) {
        try {
            Class<?> shortcuts = Reflect.findClass("com.android.launcher3.popup.SystemShortcut",
                    loader);
            Object factory = shortcuts == null ? null
                    : shortcuts.getField("SWITCH_FOLDER").get(null);
            if (factory == null) {
                L.w("zui folders: ZUI's Enlarge folder not found - left as ZUI has it");
                return 0;
            }
            return XposedBridge.hookAllMethods(factory.getClass(), "getShortcut",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object id = param.args.length == 3
                                        ? Reflect.field(param.args[1], "id") : null;
                                if (id instanceof Integer && (Integer) id <= ID_BASE
                                        && folderOfId((Integer) id) != null) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                L.d("zui folders: Enlarge left to ZUI (" + t + ")");
                            }
                        }
                    }).size();
        } catch (Throwable t) {
            L.w("zui folders: ZUI's Enlarge folder not reached (" + t + ")");
            return 0;
        }
    }

    /** The scale of the folder icon being drawn for a drag, while it is drawn at 1. Main thread. */
    private static float[] sDragScale;

    /**
     * The picture ZUI drags of one of our drawer folders is drawn at its natural size, with its
     * round corners. ZUI draws the dragged view into a picture the size of its unscaled preview
     * but at the view's own scale ({@code DragPreviewProvider.createDrawable}: made for an icon
     * shrunk by a press); ours are scaled up to the drawer's icon size ({@link #align}), so the
     * folder overflowed the picture and its corners were cut square (owner, video 22:53). For
     * the drawing only, the icon is at scale 1; ZUI then reads the scale from the view
     * ({@code getScaleAndPosition}) and the dragged picture is as large as the folder shown.
     */
    private static int hookDragPicture(ClassLoader loader) {
        Class<?> provider = Reflect.findClass("com.android.launcher3.graphics.DragPreviewProvider",
                loader);
        if (provider == null) {
            L.w("zui folders: ZUI's drag picture not found - our folders drag as ZUI draws them");
            return 0;
        }
        return XposedBridge.hookAllMethods(provider, "createDrawable", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object view = Reflect.field(param.thisObject, "mView");
                    if (view instanceof View && folderOfIcon((View) view) != null
                            && ((View) view).getScaleX() != 1f) {
                        View icon = (View) view;
                        sDragScale = new float[]{icon.getScaleX(), icon.getScaleY()};
                        icon.setScaleX(1f);
                        icon.setScaleY(1f);
                    }
                } catch (Throwable t) {
                    L.d("zui folders: drag picture left to ZUI (" + t + ")");
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    float[] scale = sDragScale;
                    sDragScale = null;
                    Object view = Reflect.field(param.thisObject, "mView");
                    if (scale != null && view instanceof View) {
                        ((View) view).setScaleX(scale[0]);
                        ((View) view).setScaleY(scale[1]);
                    }
                } catch (Throwable t) {
                    L.d("zui folders: folder scale not put back (" + t + ")");
                }
            }
        }).size();
    }

    /**
     * ZUI picks a folder's motion by where it is: the bar ({@code getZuiTbFolderAnimator}) or
     * else the home screen ({@code getZuiFolderAnimator}, which needs {@code Launcher}). Our
     * folders in the taskbar's drawer get ZUI's Launcher ({@link #hookHomeContext}) and so the
     * home screen's motion, as in ZUX Home's drawer. Only should there be no Launcher at all do
     * they fall back to ZUI's own {@code getAnimatorZui}, which asks where the folder is at each
     * step - and to no motion rather than a crash (01:32) should that fail too.
     *
     * <p>One motion for every folder of ours, in either drawer, whatever the home screen is
     * doing. While ZUI builds it ({@link #sBuilding}):
     * <ul>
     *   <li>the home screen's folder scale ({@code Workspace.getNewScaleFolder()}, about 0.89
     *       while a drawer is up) reads 1.0. ZUI animates the apps to that scale and then shows the
     *       real ones at full size, so they grew and then snapped - in the taskbar's drawer
     *       (23:05) and, left out of that fix, in ZUX Home's own (15:52, the Add apps button and
     *       every icon's backing appearing in the last frame);</li>
     *   <li>Recents reads as not showing ({@code Launcher.isInOverViewState()}). ZUI leaves the
     *       Add apps button out of the motion while it shows, and the button then popped in after
     *       the folder had opened, when the drawer was opened from Recents (15:52).</li>
     * </ul>
     */
    private static int hookAnimator(ClassLoader loader, Class<?> folderCls) {
        Class<?> manager = Reflect.findClass(FOLDER + "AnimationManager", loader);
        Class<?> bar = Reflect.findClass("com.android.launcher3.taskbar.TaskbarActivityContext",
                loader);
        if (manager == null || bar == null) {
            L.w("zui folders: ZUI's folder motion not found - taskbar drawer folders stay ours");
            return 0;
        }
        Field ofFolder = Mirror.fieldOfType(manager, folderCls);
        Method generic;
        try {
            generic = manager.getMethod("getAnimatorZui");
            sFolderActivity = folderCls.getDeclaredField("mActivityContext");
            sFolderActivity.setAccessible(true);
        } catch (Throwable t) {
            L.w("zui folders: ZUI's own folder motion not found (" + t + ")");
            return 0;
        }
        if (ofFolder == null) {
            return 0;
        }
        int hooked = hookHomeScale(loader);
        return hooked + XposedBridge.hookAllMethods(manager, "getAnimator", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object folder = ofFolder.get(param.thisObject);
                    Object where = folder == null ? null : sFolderActivity.get(folder);
                    if (!isOurs(folder) || bar.isInstance(where)) {
                        return;
                    }
                    if (isLauncher(where) || launcher() != null) {
                        // With ZUI's Launcher at hand our folders take its home motion, at the
                        // scale they end at, the same in both drawers.
                        sBuilding = true;
                        return;
                    }
                    try {
                        param.setResult(generic.invoke(param.thisObject));
                    } catch (Throwable t) {
                        L.w("zui folders: ZUI's folder motion failed here (" + t.getCause()
                                + ") - opened without it");
                        param.setResult(new android.animation.AnimatorSet());
                    }
                } catch (Throwable t) {
                    L.d("zui folders: motion not chosen (" + t + ")");
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sBuilding = false;
            }
        }).size();
    }

    /** Set on the main thread while ZUI builds the home motion for one of our folders. */
    private static boolean sBuilding;

    /**
     * While {@link #sBuilding}: the home screen's folder scale reads 1.0 and Recents as not
     * showing; see hookAnimator.
     */
    private static int hookHomeScale(ClassLoader loader) {
        try {
            Class<?> workspace = Reflect.findClass("com.android.launcher3.Workspace", loader);
            int hooked = workspace == null ? 0 : XposedBridge.hookAllMethods(workspace,
                    "getNewScaleFolder", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sBuilding && param.getResult() instanceof Float) {
                                param.setResult(1f);
                            }
                        }
                    }).size();
            if (hooked == 0) {
                L.w("zui folders: the home screen's folder scale not found - drawer folder apps "
                        + "snap to size as they open");
            }
            Class<?> launcher = Reflect.findClass(LAUNCHER, loader);
            int overview = launcher == null ? 0 : XposedBridge.hookAllMethods(launcher,
                    "isInOverViewState", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sBuilding) {
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    }).size();
            if (overview == 0) {
                L.w("zui folders: Recents' state not found - over Recents, Add apps pops in "
                        + "after our folders open");
            }
            return hooked;
        } catch (Throwable t) {
            L.w("zui folders: the home screen's folder scale not hooked (" + t + ")");
            return 0;
        }
    }

    /** The folder delegates of our folders outside ZUX Home (the taskbar's drawer). */
    private static final Map<Object, Boolean> OUR_DELEGATES = new WeakHashMap<>();
    private static Method sTracked;
    private static Object sTracker;

    /**
     * ZUI's folder code is written for the home screen: outside it, its folder delegate
     * ({@code LauncherDelegate$b}) answers no {@code Launcher}, and ZUI then draws a folder of the
     * taskbar's drawer unlike ZUX Home's (see-through, 1.0.192). For our folders there it answers
     * ZUI's tablet Launcher - read as ZUI's own {@code expandFolder} reads it - so the folder is
     * drawn and moves exactly as in ZUX Home's drawer. ZUI's Launcher-only effects stay behind
     * {@code isFromLauncher()}, which asks the folder's own context; drag stays off.
     */
    private static int hookHomeContext(ClassLoader loader) {
        try {
            Class<?> delegate = Reflect.findClass(FOLDER.replace("Folder", "LauncherDelegate"),
                    loader);
            Class<?> outside = Reflect.findClass(
                    FOLDER.replace("Folder", "LauncherDelegate") + "$b", loader);
            Class<?> launcher = Reflect.findClass(LAUNCHER, loader);
            if (delegate == null || outside == null || launcher == null) {
                return 0;
            }
            Field tracker = launcher.getField("ACTIVITY_TRACKER");
            sTracker = tracker.get(null);
            sTracked = sTracker.getClass().getMethod("getCreatedContext");
            String name = null;
            for (Method m : delegate.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == launcher) {
                    name = m.getName();
                }
            }
            if (name == null) {
                return 0;
            }
            return XposedBridge.hookAllMethods(outside, name, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 && OUR_DELEGATES.containsKey(param.thisObject)) {
                        Object home = launcher();
                        if (home != null) {
                            param.setResult(home);
                        }
                    }
                }
            }).size();
        } catch (Throwable t) {
            L.w("zui folders: ZUI's home context not given to the drawer's folders (" + t + ")");
            return 0;
        }
    }

    /** ZUI's tablet Launcher, as ZUI's own taskbar reads it; null when there is none. */
    static Object launcher() {
        try {
            return sTracked == null ? null : sTracked.invoke(sTracker);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Our folders open in the taskbar's drawer, with the back callback each registered. */
    private static final Map<Object, Back> BACK = new WeakHashMap<>();

    /** Where a folder's back callback was registered, to take it off again. */
    private static final class Back {
        final android.window.OnBackInvokedDispatcher dispatcher;
        final android.window.OnBackInvokedCallback callback;

        Back(android.window.OnBackInvokedDispatcher dispatcher,
                android.window.OnBackInvokedCallback callback) {
            this.dispatcher = dispatcher;
            this.callback = callback;
        }
    }

    /**
     * Back closes what is on top in the taskbar's drawer while our folder is open: an app's popup
     * over the folder first, then the folder, then the drawer. The drawer takes back by
     * registering itself with its window ({@code TaskbarAllAppsSlideInView}); ZUI's folder and
     * popups register nothing there, so back closed the drawer and left the folder (02:14), and
     * then the folder and left its popup (12:21). While our folder is open a callback is
     * registered the same way - the last one registered is the one called - and it does what
     * ZUI's drawer does with a back key ({@code TaskbarOverlayDragLayer.dispatchKeyEvent}):
     * closes ZUI's top open view ({@code AbstractFloatingView.getTopOpenView}).
     */
    private static int hookBack(Class<?> folderCls) {
        int hooked = XposedBridge.hookAllMethods(folderCls, "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        registerBack(param.thisObject);
                    }
                }).size();
        hooked += XposedBridge.hookAllMethods(folderCls, "handleClose", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                unregisterBack(param.thisObject);
            }
        }).size();
        hooked += hookCancelledBack(folderCls.getClassLoader());
        return hooked;
    }

    /**
     * A back key in the taskbar's drawer closes one thing, as the back gesture does. ZUX Home
     * takes back through callbacks ({@code enableOnBackInvokedCallback}), so Android hands a back
     * key to the top callback - our folder's, closing the folder - and then passes the same key
     * on to the drawer's views marked cancelled ({@code ViewRootImpl.doOnBackKeyEvent}: "forward
     * a cancelled event so that following stages cancel their back logic"). ZUI's drawer layer
     * acts on a back key's release without asking whether it was cancelled
     * ({@code TaskbarOverlayDragLayer.dispatchKeyEvent}), and closed the drawer under the folder
     * just closed: a controller's B (Android's back for it) or a keyboard's back key took both
     * away (owner, 10:37:53 and 10:37:58). A cancelled back is left to the callback that had it.
     */
    private static int hookCancelledBack(ClassLoader loader) {
        Class<?> layer = Reflect.findClass(
                "com.android.launcher3.taskbar.overlay.TaskbarOverlayDragLayer", loader);
        if (layer == null) {
            return 0;
        }
        return XposedBridge.hookAllMethods(layer, "dispatchKeyEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 1 && param.args[0] instanceof android.view.KeyEvent) {
                    android.view.KeyEvent key = (android.view.KeyEvent) param.args[0];
                    if (key.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
                            && key.getAction() == android.view.KeyEvent.ACTION_UP
                            && key.isCanceled()) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        }).size();
    }

    private static void registerBack(Object folder) {
        try {
            Object where = sFolderActivity == null ? null : sFolderActivity.get(folder);
            if (!isOurs(folder) || isLauncher(where) || isBar(where)
                    || !(folder instanceof android.window.OnBackInvokedCallback)) {
                return;
            }
            android.window.OnBackInvokedDispatcher dispatcher =
                    ((View) folder).findOnBackInvokedDispatcher();
            if (dispatcher != null && !BACK.containsKey(folder)) {
                android.window.OnBackInvokedCallback callback = () -> closeTop(folder, where);
                dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
                BACK.put(folder, new Back(dispatcher, callback));
            }
        } catch (Throwable t) {
            L.d("zui folders: back not given to the folder (" + t + ")");
        }
    }

    /** ZUI's top open view in the drawer, closed as a back key closes it; else the folder. */
    private static void closeTop(Object folder, Object activity) {
        try {
            Class<?> floating = Class.forName("com.android.launcher3.AbstractFloatingView", false,
                    folder.getClass().getClassLoader());
            Class<?> context = Class.forName("com.android.launcher3.views.ActivityContext", false,
                    folder.getClass().getClassLoader());
            Object top = floating.getMethod("getTopOpenView", context).invoke(null, activity);
            if (top != null && Boolean.TRUE.equals(
                    floating.getMethod("canHandleBack").invoke(top))) {
                floating.getMethod("onBackInvoked").invoke(top);
                return;
            }
        } catch (Throwable t) {
            L.d("zui folders: top view not found for back (" + t + ")");
        }
        ((android.window.OnBackInvokedCallback) folder).onBackInvoked();
    }

    private static void unregisterBack(Object folder) {
        Back back = BACK.remove(folder);
        if (back != null) {
            try {
                back.dispatcher.unregisterOnBackInvokedCallback(back.callback);
            } catch (Throwable t) {
                L.d("zui folders: back callback not removed (" + t + ")");
            }
        }
    }

    /**
     * Our folder closes with the taskbar's drawer. ZUI's own close of the drawer's window
     * ({@code TaskbarOverlayController.hideWindow}) closes every view open in it
     * ({@code AbstractFloatingView.closeAllOpenViews}), and the window stays while any is open;
     * the drawer's sheet closed on its own - our start button, a swipe - left our folder over the
     * home screen for a second, then shrinking to nowhere (14:12), and an app's popup over the
     * folder stayed as the sheet slid away (15:14). So as the sheet starts to close while one of
     * our folders is open, everything open in its window closes with it, as ZUI closes it -
     * every type but the sheet itself and the window's own proxy
     * ({@code closeOpenViews(context, animate, TYPE_ALL & ~(TYPE_TASKBAR_ALL_APPS |
     * TYPE_TASKBAR_OVERLAY_PROXY))}).
     */
    private static int hookDrawerClose(ClassLoader loader) {
        try {
            Class<?> sheet = Reflect.findClass(
                    "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView", loader);
            Class<?> floating = Reflect.findClass("com.android.launcher3.AbstractFloatingView",
                    loader);
            if (sheet == null || floating == null) {
                L.w("zui folders: the drawer's sheet not found - folders close on their own");
                return 0;
            }
            Method close = null;
            for (Method m : floating.getMethods()) {
                if (m.getName().equals("closeOpenViews") && m.getParameterCount() == 3) {
                    close = m;
                }
            }
            if (close == null) {
                L.w("zui folders: ZUI's closeOpenViews not found - folders close on their own");
                return 0;
            }
            int openTypes = floating.getField("TYPE_ALL").getInt(null)
                    & ~(floating.getField("TYPE_TASKBAR_ALL_APPS").getInt(null)
                    | floating.getField("TYPE_TASKBAR_OVERLAY_PROXY").getInt(null));
            Method closeViews = close;
            return XposedBridge.hookAllMethods(sheet, "handleClose", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object activity = Reflect.field(param.thisObject, "mActivityContext");
                        if (activity == null || isLauncher(activity) || isBar(activity)
                                || !anyOursOpen(activity)) {
                            return;
                        }
                        boolean animate = param.args.length == 0
                                || !(param.args[0] instanceof Boolean)
                                || (Boolean) param.args[0];
                        closeViews.invoke(null, activity, animate, openTypes);
                    } catch (Throwable t) {
                        L.d("zui folders: not closed with the drawer (" + t + ")");
                    }
                }
            }).size();
        } catch (Throwable t) {
            L.w("zui folders: drawer close not watched (" + t + ")");
            return 0;
        }
    }

    /** Whether one of our folders is open in this context. */
    private static boolean anyOursOpen(Object activity) {
        Map<String, Built> mine = BUILT.get(activity);
        if (mine == null) {
            return false;
        }
        for (Built built : mine.values()) {
            if (isOpen(built)) {
                return true;
            }
        }
        return false;
    }

    /** The context a folder of ZUI's was made in, or null. */
    static Object contextOf(Object folder) {
        try {
            return sFolderActivity == null || folder == null ? null : sFolderActivity.get(folder);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether a folder of ZUI's is one of ours. */
    static boolean isOurs(Object folder) {
        try {
            Object info = folder == null ? null : sFolderInfo.get(folder);
            Object id = info == null ? null : Reflect.field(info, "id");
            return id instanceof Integer && (Integer) id <= ID_BASE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether our folders are ZUI's in the tablet's drawers. */
    static boolean on() {
        return !sOff && sInflate != null && Cfg.enabled() && Cfg.nativeDrawer();
    }

    private static void off(String why) {
        if (!sOff) {
            sOff = true;
            L.w("zui folders: off, our own folder window instead - " + why);
        }
    }

    // --- ZUI's list ------------------------------------------------------

    /**
     * ZUI's entries for the apps in our folders, from its app store; null when the store could
     * not be read, which keeps the ones last seen.
     */
    static void remember(Map<String, Object> filed) {
        if (filed != null) {
            sFiled = filed;
            Runnable known = sAppsKnown;
            if (!filed.isEmpty() && sBarWaiting && known != null) {
                sBarWaiting = false;
                MAIN.post(known);
            }
        }
    }

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** A bar asked for its folders before ZUI's app list was read. */
    private static volatile boolean sBarWaiting;
    /** Who rebuilds the bar's folders once ZUI's app list is read. */
    private static volatile Runnable sAppsKnown;

    /**
     * Who to tell once ZUI's app list is known, when a bar built its folders before it was: the
     * bar is put up at boot a moment before ZUI builds its drawer's list, which is where our
     * folders' apps come from (1.0.209 left the bar's folders ours for the whole session).
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
        BUILT.clear();
        Runnable known = sAppsKnown;
        if (on()) {
            sBarWaiting = true;
        } else if (known != null) {
            MAIN.post(known);
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
        TABLET_LIST.set(on() && onTablet(appsList));
    }

    static void built() {
        TABLET_LIST.remove();
    }

    private static boolean onTablet(Object appsList) {
        try {
            Object adapter = adapterOf(appsList);
            Object activity = adapter == null ? null : sActivity.get(adapter);
            return activity instanceof Context
                    && displayOf((Context) activity) == Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    /** One of ZUI's list items as it is made: ours, in a tablet drawer, get our type. */
    private static void born(Object item) {
        try {
            if (!Boolean.TRUE.equals(TABLET_LIST.get()) || item == null) {
                return;
            }
            Object info = sItemInfo.get(item);
            if (info != null && NativeDrawerHooks.folderOfEntry(info) != null) {
                sViewType.setInt(item, TYPE);
            }
        } catch (Throwable t) {
            off("could not mark our folders in ZUI's list (" + t + ")");
        }
    }

    /** A cell for one of our folders: the size of ZUI's own drawer cells, filled when bound. */
    private static Object cell(ViewGroup parent, Object adapter) {
        FrameLayout cell = new FrameLayout(parent.getContext());
        int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        try {
            Object activity = sActivity.get(adapter);
            Object profile = Reflect.call(activity, "getDeviceProfile");
            Object cellHeight = Reflect.field(profile, "allAppsCellHeightPx");
            if (cellHeight instanceof Integer) {
                height = (Integer) cellHeight;
            }
            cell.setLayoutParams(new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
            return sHolder.newInstance(cell);
        } catch (Throwable t) {
            off("could not make a cell (" + t + ")");
            try {
                return sHolder.newInstance(cell);
            } catch (Throwable never) {
                throw new IllegalStateException(never);
            }
        }
    }

    /** Fills one of our cells with ZUI's folder icon; false for every cell that is not ours. */
    private static boolean bind(Object adapter, Object holder, int position) {
        try {
            // Every cell of the drawer comes through here: the type first, nothing else for
            // ZUI's own.
            if (sType == null || (Integer) sType.invoke(holder) != TYPE) {
                return false;
            }
            View cell = (View) sItemView.get(holder);
            if (!(cell instanceof FrameLayout)) {
                return false;
            }
            Object list = Reflect.field(adapter, "mApps");
            Object items = Reflect.call(list, "getAdapterItems");
            if (!(items instanceof List) || position < 0 || position >= ((List<?>) items).size()) {
                return false;
            }
            Object item = ((List<?>) items).get(position);
            if (sViewType.getInt(item) != TYPE) {
                return false;
            }
            Item folder = NativeDrawerHooks.folderOfEntry(sItemInfo.get(item));
            Object activity = sActivity.get(adapter);
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
            describeOnce(icon);
            return true;
        } catch (Throwable t) {
            L.d("zui folders: cell not filled (" + t + ")");
            return false;
        }
    }

    /**
     * ZUI lays its folder icon out with the home screen's sizes; in the drawer its box was a
     * tenth bigger than the apps beside it and sat higher (01:43). Measured once laid out: the
     * folder's preview ({@code getPreviewBounds}) is scaled to a drawer icon's size and moved to
     * where that icon is drawn (its top compound drawable, at its top padding). Its name follows
     * ({@link #fitName}).
     */
    private static void align(View icon) {
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
     * - in landscape smaller than the apps beside it, and than the folders aligned after it
     * (owner, 0.0.270).
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
                if (drawer.focus != null && icon.getOnFocusChangeListener() == null) {
                    // Reached by a keyboard or a controller, marked as ZUI marks its apps -
                    // guarded, as ZUI made its mark for app icons. ZUI draws the mark where the
                    // view is in its list (FocusIndicatorHelper.viewToRect: its left and top in
                    // its parent), so it is told of the folder's cell, the list's own child: told
                    // of the folder inside the cell, the mark stayed on the list's first cell
                    // wherever the focus went (owner's video, 10:37:46).
                    View.OnFocusChangeListener zui = drawer.focus;
                    icon.setOnFocusChangeListener((v, focused) -> {
                        try {
                            zui.onFocusChange(v.getParent() instanceof View
                                    ? (View) v.getParent() : v, focused);
                        } catch (Throwable t) {
                            L.d("zui folders: ZUI's focus mark not drawn on a folder (" + t + ")");
                        }
                    });
                }
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

    /** The drawer's icon size in ZUI's profile for the icon's window, or 0. */
    private static int profileIconSize(View icon) {
        try {
            Object profile = Reflect.call(lookup(icon.getContext()), "getDeviceProfile");
            Object size = Reflect.field(profile, "allAppsIconSizePx");
            return size instanceof Integer ? (Integer) size : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The folder's name as ZUI writes the app names beside it. ZUI's folder name is made for the
     * home screen and does not shorten to its width, so in a portrait drawer, narrower than a
     * home cell and scaled with the icon, long names ran out of the cell on both sides (14:12).
     * It takes the cell's width and the drawer names' size, lines and shortening, each divided by
     * the icon's scale so that, scaled, it is exactly as wide and as large as ZUI's own.
     */
    private static void fitName(View icon, Look drawer, float scale) {
        int id = icon.getResources().getIdentifier("folder_icon_name", "id",
                icon.getContext().getPackageName());
        View view = id != 0 ? icon.findViewById(id) : null;
        if (!(view instanceof android.widget.TextView) || icon.getWidth() <= 0 || scale <= 0f) {
            return;
        }
        android.widget.TextView name = (android.widget.TextView) view;
        int width = Math.round(icon.getWidth() / scale);
        ViewGroup.LayoutParams lp = name.getLayoutParams();
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
        String content = contentOf(folder);
        Map<String, Built> mine = BUILT.get(activity);
        if (mine == null) {
            mine = new HashMap<>();
            BUILT.put(activity, mine);
        }
        Built built = mine.get(folder.id);
        if (built != null && (built.content.equals(content) || isOpen(built))) {
            return built.icon;
        }
        Object info = folderInfo(activity, folder);
        if (info == null) {
            return null;
        }
        int layout = activity.getResources().getIdentifier("folder_icon", "layout",
                activity.getPackageName());
        View icon = (View) sInflate.invoke(null, layout, activity, cell, info);
        drawerLabel(activity, icon);
        if (!isLauncher(activity)) {
            // ZUI's taskbar would open it in the bar's window and close the drawer
            // (expandFolder); in the drawer it opens where it is, as ZUI's taskbar opens its own.
            icon.setOnClickListener(v -> openHere(v));
            noteDelegate(icon);
        }
        // Held, our hold hook gives it ZUI's folder menu; ZUI's own hold needs it long-clickable.
        icon.setOnLongClickListener(v -> true);
        // A keyboard's or a controller's arrows stop on it, and select opens it, as an app.
        icon.setFocusable(true);
        // Aligned as it is laid out, before it is ever drawn at ZUI's home size.
        icon.addOnLayoutChangeListener(ALIGN);
        mine.put(folder.id, new Built(icon, content));
        return icon;
    }

    // --- the tablet's bar ----------------------------------------------------

    private static boolean sSaidBar;

    /**
     * One of our folders pinned to the tablet's bar, as ZUI's own folder icon there: built as
     * ZUI's {@code TaskbarView} builds its folders ({@code inflateFolderAndIcon} in the bar's
     * context, name hidden), so its preview, its folder, the motion and blur of its opening and
     * ZUI's own click and popup on each app inside are ZUI's bar's. Our row keeps the tap, the
     * hold menu and the drag; a tap opens it through {@link #openOnBar}. Null when it cannot be
     * ZUI's - the bar then keeps our own folder icon and window.
     */
    public static View barIcon(Context ctx, Item folder) {
        try {
            Object activity = on() ? lookup(ctx) : null;
            if (!isBar(activity) || !(activity instanceof Context)) {
                return null;
            }
            Context bar = (Context) activity;
            if (sFiled.isEmpty()) {
                // ZUI has not read its app list yet: this bar is rebuilt once it has.
                if (!sBarWaiting) {
                    sBarWaiting = true;
                    L.i("zui folders: the bar's folders wait for ZUI's app list");
                }
                return null;
            }
            String content = contentOf(folder);
            Map<String, Built> mine = BUILT.get(bar);
            if (mine == null) {
                mine = new HashMap<>();
                BUILT.put(bar, mine);
            }
            Built built = mine.get(folder.id);
            View icon;
            if (built != null && (built.content.equals(content) || isOpen(built))) {
                icon = built.icon;
            } else {
                Object info = folderInfo(bar, folder);
                if (info == null) {
                    return null;
                }
                int layout = bar.getResources().getIdentifier("folder_icon", "layout",
                        bar.getPackageName());
                icon = (View) sInflate.invoke(null, layout, bar, null, info);
                icon.getClass().getMethod("setTextVisible", boolean.class).invoke(icon, false);
                mine.put(folder.id, new Built(icon, content));
            }
            if (icon.getParent() instanceof ViewGroup) {
                ((ViewGroup) icon.getParent()).removeView(icon);
            }
            icon.setOnClickListener(ZuiFolders::openOnBar);
            if (!sSaidBar) {
                sSaidBar = true;
                L.i("zui folders: the bar's pinned folders are ZUI's own folder icons");
            }
            return icon;
        } catch (Throwable t) {
            L.w("zui folders: the bar keeps our folder for " + folder.label + " (" + t + ")");
            return null;
        }
    }

    /** Whether a view is one of our folders as ZUI's folder icon. */
    public static boolean isZuiFolder(View view) {
        return view != null && sInflate != null
                && sInflate.getDeclaringClass().isInstance(view);
    }

    /**
     * Opens one of our bar folders as ZUI's bar opens its own ({@code expandFolder}: the bar's
     * window full screen, then the folder, with ZUI's click and hold on every app inside). The
     * window is made full screen first and the folder asked for only once it has grown: our row
     * finds its place in the bigger window a layout later than ZUI's own icons, and a folder
     * opened before that would open from where the bar used to be.
     */
    public static void openOnBar(View icon) {
        IconPress.release(icon);
        boolean asked = ZuiMenu.withRoom(icon, (activity, dragLayer) -> {
            try {
                Method expand = null;
                for (Method m : activity.getClass().getMethods()) {
                    if (m.getName().equals("expandFolder") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isInstance(icon)) {
                        expand = m;
                    }
                }
                if (expand == null) {
                    L.w("zui folders: ZUI's bar cannot open folders here (no expandFolder)");
                    return false;
                }
                expand.invoke(activity, icon);
                // ZUI opens it on its next frame; should it not (the notification shade down),
                // the bar gets its size back.
                Object folder = icon.getClass().getMethod("getFolder").invoke(icon);
                dragLayer.post(() -> {
                    try {
                        if (!Boolean.TRUE.equals(folder.getClass().getMethod("isOpen")
                                .invoke(folder))) {
                            activity.getClass().getMethod("setTaskbarWindowFullscreen",
                                    boolean.class).invoke(activity, false);
                        }
                    } catch (Throwable ignored) {
                        // The bar is gone with its window.
                    }
                });
                L.i("zui folders: opened on the bar, as ZUI's bar opens its folders");
                return true;
            } catch (Throwable t) {
                L.w("zui folders: could not open on the bar (" + t + ")");
                return false;
            }
        });
        if (!asked) {
            L.d("zui folders: the bar's folder not opened - not in ZUI's bar");
        }
    }

    /**
     * The name under the icon in the colour ZUI gives every name in its drawer
     * ({@code all_app_item_text_color}, as its adapter sets it) - the folder icon's own is the
     * home screen's, made for the wallpaper.
     */
    private static void drawerLabel(Context ctx, View icon) {
        int label = ctx.getResources().getIdentifier("folder_icon_name", "id",
                ctx.getPackageName());
        int colour = ctx.getResources().getIdentifier("all_app_item_text_color", "color",
                ctx.getPackageName());
        View name = label != 0 ? icon.findViewById(label) : null;
        if (name instanceof android.widget.TextView && colour != 0) {
            ((android.widget.TextView) name).setTextColor(ctx.getColor(colour));
        }
    }

    /** Our folder's delegate, so it answers ZUI's Launcher ({@link #hookHomeContext}). */
    private static void noteDelegate(View icon) {
        try {
            Object folder = icon.getClass().getMethod("getFolder").invoke(icon);
            Object delegate = Reflect.field(folder, "mLauncherDelegate");
            if (delegate != null) {
                OUR_DELEGATES.put(delegate, Boolean.TRUE);
            }
        } catch (Throwable t) {
            L.d("zui folders: delegate not noted (" + t + ")");
        }
    }

    /** Our folder as ZUI's {@code FolderInfo}: its title and its apps, in our order. */
    private static Object folderInfo(Context activity, Item folder) throws Exception {
        return folderInfo(activity, folder, idOf(folder),
                child -> sFiled.get(child.key()), CHILDREN);
    }

    private static final java.util.Set<String> SAID_EMPTY = new java.util.HashSet<>();

    private static Object folderInfo(Context activity, Item folder, int id,
            java.util.function.Function<Item, Object> appOf, Map<Object, Item> children)
            throws Exception {
        Class<?> cls = Class.forName(FOLDER_INFO, false, activity.getClassLoader());
        Object info = cls.getConstructor().newInstance();
        setInt(info, "id", id);
        setField(info, "title", folder.label != null ? folder.label : "Folder");
        Method add = cls.getMethod("add", Class.forName(
                "com.android.launcher3.model.data.ItemInfo", false, activity.getClassLoader()));
        int rank = 0;
        for (Item child : folder.children) {
            Object entry = appOf.apply(child);
            if (entry == null) {
                continue;
            }
            Object made = entry.getClass().getMethod("makeWorkspaceItem", Context.class)
                    .invoke(entry, activity);
            if (made == null) {
                continue;
            }
            setInt(made, "container", id);
            setInt(made, "rank", rank++);
            add.invoke(info, made);
            children.put(made, child);
        }
        if (rank == 0 && SAID_EMPTY.add(folder.id)) {
            L.w("zui folders: folder " + folder.label + " - none of its "
                    + folder.children.size() + " apps in ZUI's app store");
        }
        return rank == 0 ? null : info;
    }

    /**
     * Fresh copies of our folder's apps as ZUI's home screen makes its own
     * ({@code AppInfo.makeWorkspaceItem}), for a folder of ZUI's ({@link ZuiFolderHome}).
     */
    static List<Object> homeApps(Context activity, Item folder) {
        List<Object> apps = new java.util.ArrayList<>();
        for (Item child : folder.children) {
            Object entry = sFiled.get(child.key());
            if (entry == null) {
                continue;
            }
            try {
                Object made = entry.getClass().getMethod("makeWorkspaceItem", Context.class)
                        .invoke(entry, activity);
                if (made != null) {
                    apps.add(made);
                }
            } catch (Throwable t) {
                L.d("zui folders: " + child.pkg + " not copied for the home screen (" + t + ")");
            }
        }
        return apps;
    }

    private static synchronized int idOf(Item folder) {
        for (Map.Entry<Integer, Item> e : BY_ID.entrySet()) {
            if (e.getValue().id.equals(folder.id)) {
                e.setValue(folder);
                return e.getKey();
            }
        }
        int id = ID_BASE - BY_ID.size();
        BY_ID.put(id, folder);
        return id;
    }

    // --- opening and holding ----------------------------------------------

    /** Opens the folder of a folder icon where it is, as ZUI's taskbar does ({@code x0}). */
    private static void openHere(View icon) {
        try {
            IconPress.release(icon);
            Object folder = icon.getClass().getMethod("getFolder").invoke(icon);
            folder.getClass().getMethod("animateOpen").invoke(folder);
            L.i("zui folders: opened in the drawer, as ZUI's taskbar opens its folders");
        } catch (Throwable t) {
            L.w("zui folders: could not open (" + t + ")");
        }
    }

    /**
     * An app held inside one of our folders: what ZUI's drawer does with a hold on its own apps
     * - its popup, and its drag once the finger moves - in either drawer. On ZUX Home without
     * the drag (only the popup, {@code LauncherCustom.skipHotseatDrag}) when the drawer's drag
     * is switched off.
     */
    private static boolean holdInside(Object folder, View view) {
        try {
            if (!isOurs(folder)) {
                return false;
            }
            Context ctx = view.getContext();
            Object activity = lookup(ctx);
            if (isLauncher(activity) && Cfg.drawerDrag() && holdAsDrawerApp(activity, view)) {
                return true;
            }
            if (isLauncher(activity)) {
                Class.forName(POPUP, false, ctx.getClassLoader())
                        .getMethod("showForIcon", View.class).invoke(null, view);
                IconPress.release(view);
                return true;
            }
            // The taskbar's drawer: what ZUI's drawer does with a hold on its own icons
            // (getAllAppsItemLongClickListener, the drawer's TaskbarDragController): its popup
            // and its drag, in the drawer's own window, with the press let go by ZUI. Through
            // the bar instead (onClickIconWithRightMouse), the bar's window went full screen over
            // the drawer and took every touch from the popup (12:21).
            Object hold = activity.getClass().getMethod("getAllAppsItemLongClickListener")
                    .invoke(activity);
            return hold instanceof View.OnLongClickListener
                    && ((View.OnLongClickListener) hold).onLongClick(view);
        } catch (Throwable t) {
            L.d("zui folders: no popup inside our folder (" + t + ")");
            return false;
        }
    }

    /**
     * ZUX Home's own drawer hold ({@code getAllAppsItemLongClickListener}: popup, then
     * {@code Workspace.beginDragShared} as the finger moves) on an app in one of our folders,
     * with the drawer's own entry for that app as what it holds and drags: ZUI's stand-in item
     * for our folder's app is no item of its home screen, and dropped there it would be written
     * as a move of a row that does not exist. With the drawer's entry ZUI copies the app onto
     * the home screen, as from its drawer, and our folder keeps it. Our folder's rows stay in the
     * popup: the entry is ours for as long as the hold is being handled.
     *
     * <p>ZUI's hold answers false even when it took the hold ({@code ItemLongClickListener}
     * returns false after {@code beginDragShared}); whether its drag is waiting on the finger
     * says whether it did. Our folder is shut once the drag is under way, as ZUI's drawer is.
     */
    private static boolean holdAsDrawerApp(Object launcher, View view) {
        Object stand = view.getTag();
        Item child = childOf(stand);
        if (child == null || child.pkg == null || child.cls == null) {
            return false;
        }
        Object app = ZuiApps.appInfo(launcher, new ComponentName(child.pkg, child.cls),
                IconInfo.userOf(stand));
        Object hold = Reflect.call(launcher, "getAllAppsItemLongClickListener");
        Object drags = Reflect.call(launcher, "getDragController");
        if (app == null || drags == null || !(hold instanceof View.OnLongClickListener)) {
            return false;
        }
        noteChild(app, child);
        view.setTag(app);
        try {
            ((View.OnLongClickListener) hold).onLongClick(view);
        } finally {
            view.setTag(stand);
            CHILDREN.remove(app);
        }
        boolean took = Boolean.TRUE.equals(Reflect.call(drags, "isDragging"));
        sHeldOut = took;
        if (!took) {
            L.i("zui folders: ZUI's drawer did not take the hold on " + child.pkg);
        }
        return took;
    }

    /** ZUI's drag of an app out of one of our folders is waiting on the finger or under way. */
    private static boolean sHeldOut;

    /**
     * Our folder shuts as the drag out of it starts, as ZUI's drawer closes for its own: the home
     * screen opens to take it ({@code Workspace.onDragStart}), and the folder would stay over it.
     */
    private static int hookHeldOut(ClassLoader loader) {
        Class<?> workspace = Reflect.findClass("com.android.launcher3.Workspace", loader);
        if (workspace == null) {
            L.w("zui folders: ZUI's home screen not found - a folder stays open over a drag");
            return 0;
        }
        int hooked = XposedBridge.hookAllMethods(workspace, "onDragStart", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sHeldOut) {
                    sHeldOut = false;
                    closeOpenFolders();
                }
            }
        }).size();
        hooked += XposedBridge.hookAllMethods(workspace, "onDragEnd", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // Let go before it moved: only the popup was shown, and the folder stays.
                sHeldOut = false;
            }
        }).size();
        return hooked;
    }

    // --- our model ---------------------------------------------------------

    /** Our folder behind an id handed to ZUI, or null. */
    static synchronized Item folderOfId(int id) {
        return BY_ID.get(id);
    }

    /** One of ZUI's items made for an app of ours inside one of our folders. */
    static void noteChild(Object item, Item child) {
        CHILDREN.put(item, child);
    }

    /** The app of ours one of ZUI's items inside our folders stands for, or null. */
    static Item childOf(Object item) {
        return item == null ? null : CHILDREN.get(item);
    }

    /**
     * ZUI's new folder for one of ours on its way to the home screen from the bar: placed by ZUI,
     * filled with its apps as one from the drawer is. Null where it cannot go.
     */
    public static Object forHome(Context home, Item folder) {
        return ZuiFolderHome.forHome(home, folder);
    }

    /** Our folder behind one of ZUI's folder icons of ours, or null. */
    public static Item folderOfIcon(View view) {
        Object tag = view == null ? null : view.getTag();
        Object id = tag == null ? null : Reflect.field(tag, "id");
        return id instanceof Integer && (Integer) id <= ID_BASE ? folderOfId((Integer) id) : null;
    }

    /**
     * "Remove from folder" for one of ZUI's items inside our folders - for ZUI's app popup - or
     * null for any other item.
     */
    public static Runnable removeAction(Object item) {
        // The app as the menu is built: ZUX Home's drawer hold notes it under the drawer's own
        // entry only while the hold is handled, so a lookup at the tap found nothing (21:58).
        Item child = childOf(item);
        return child == null ? null : () -> removeFromFolder(child);
    }

    /**
     * "Uninstall" for one of ZUI's items inside our folders: ZUI's own
     * ({@code Utilities.startUninstallActivity}, as its Uninstall shortcut calls it, with ZUI's
     * Launcher), or null for any other item. ZUI's own Uninstall leaves items outside the home
     * screen out, so it is never offered for ours.
     */
    public static Runnable uninstallAction(Object item) {
        if (childOf(item) == null) {
            return null;
        }
        return () -> {
            try {
                Object home = launcher();
                for (Method m : Class.forName("com.android.launcher3.Utilities", false,
                        item.getClass().getClassLoader()).getMethods()) {
                    if (m.getName().equals("startUninstallActivity")
                            && m.getParameterCount() == 2 && home != null) {
                        m.invoke(null, home, item);
                        return;
                    }
                }
                L.w("zui folders: ZUI's uninstall not reachable");
            } catch (Throwable t) {
                L.w("zui folders: uninstall failed (" + t + ")");
            }
        };
    }

    /**
     * Takes an app out of the folder it is in, to the place after the folder, by the drawer's
     * own rule ({@link DrawerArrange#takeOut}: a folder left with one app gives its place to it),
     * and the drawer shows it at once.
     */
    static void removeFromFolder(Item child) {
        String key = child.key();
        boolean done = NativeDrawerHooks.rearrange("took " + key + " out of its folder",
                (order, folders) -> {
                    Item folder = DrawerArrange.folderOf(folders, key);
                    int at = folder == null ? -1 : order.indexOf(folder.key());
                    return folder != null
                            && DrawerArrange.takeOut(order, folders, key, at + 1);
                });
        if (!done) {
            // No drawer list seen yet since ZUX Home started (the bar's folder, held first): the
            // folder alone loses the app, and the drawer sorts it in when it next lays out.
            Context ctx = AppCtx.get();
            DrawerStore store = ctx == null ? null : DrawerStore.shared(ctx);
            Item folder = store == null ? null : store.folderContaining(key);
            if (folder != null) {
                folder.children.remove(child);
                if (folder.children.size() <= 1) {
                    // One app left is not a folder: both go back to the list.
                    folder.children.clear();
                    store.folders().remove(folder);
                }
                store.save();
                NativeDrawerHooks.refreshList();
                L.i("zui folders: took " + key + " out of its folder, before any drawer was seen");
            } else {
                L.i("zui folders: " + key + " not taken out - no folder holds it");
            }
        }
        closeOpenFolders();
    }

    /** Renamed in ZUI's folder: our folder takes the name. */
    static void renamed(int id, CharSequence title) {
        Item folder = folderOfId(id);
        Context ctx = AppCtx.get();
        String name = title == null ? "" : title.toString();
        if (folder == null || ctx == null || name.equals(folder.label)) {
            return;
        }
        folder.label = name;
        saveKeepingIcons(ctx, folder);
        L.i("zui folders: renamed in ZUI's folder - saved to ours");
    }

    /** Rearranged in ZUI's folder: our folder takes the order of the ranks ZUI gave its apps. */
    static void reordered(int id, List<?> items) {
        Item folder = folderOfId(id);
        Context ctx = AppCtx.get();
        if (folder == null || ctx == null) {
            return;
        }
        // The folder's whole contents where they can be had: ZUI saves only the apps whose
        // place changed (Folder.updateItemLocationsInDatabaseBatch), and read alone those left
        // every other app to go after them - a move inside a folder sent the rest to its end.
        List<?> all = contentsOf(id);
        if (all != null) {
            items = all;
        }
        // By key: ZUI's apps map to our apps as they were when its folder was built - the bar's
        // and the drawer's builds hold other copies of the same apps. Matched as objects, every
        // app of a folder pinned to the bar was added again each time ZUI bound it (0.0.269).
        Map<String, Item> ours = new java.util.LinkedHashMap<>();
        for (Item child : folder.children) {
            ours.putIfAbsent(child.key(), child);
        }
        java.util.TreeMap<Integer, Item> byRank = new java.util.TreeMap<>();
        for (Object item : items) {
            Item child = childOf(item);
            Object rank = Reflect.field(item, "rank");
            Item mine = child == null ? null : ours.get(child.key());
            if (mine != null && rank instanceof Integer && !byRank.containsValue(mine)) {
                byRank.put((Integer) rank, mine);
            }
        }
        // ZUI's order for the apps it shows; any of ours it does not (not installed) after them.
        List<Item> now = new java.util.ArrayList<>(byRank.values());
        for (Item child : ours.values()) {
            if (!now.contains(child)) {
                now.add(child);
            }
        }
        if (!now.equals(folder.children)) {
            folder.children.clear();
            folder.children.addAll(now);
            saveKeepingIcons(ctx, folder);
        }
    }

    /** What ZUI's open folder of ours holds, every app, or null where it cannot be read. */
    private static List<?> contentsOf(int id) {
        for (Map<String, Built> mine : BUILT.values()) {
            for (Built built : mine.values()) {
                Object info = built.icon.getTag();
                Object builtId = info == null ? null : Reflect.field(info, "id");
                if (builtId instanceof Integer && (Integer) builtId == id) {
                    Object contents = Reflect.call(info, "getContents");
                    if (contents instanceof List) {
                        return new java.util.ArrayList<>((List<?>) contents);
                    }
                }
            }
        }
        return null;
    }

    /** The app filed into one of our folders by the last drop into ZUI's folder, for the log. */
    static String sLastFiled;
    /** Where in our folder {@link #sLastFiled} went: the apps chosen with it go after it. */
    static int sLastFiledAt;

    /**
     * ZUI's folder of ours took a dragged app ({@code Folder.onDrop}, sprung open under it in the
     * drawer): the app goes into our folder at the place it was let go, out of the drawer's
     * order, and ZUI's folder, which already shows it, is kept as it is. True when it went in.
     */
    static boolean filedByDrop(int id, Object item) {
        try {
            Item folder = folderOfId(id);
            Context ctx = AppCtx.get();
            if (folder == null || ctx == null || item == null || childOf(item) != null) {
                return false;
            }
            Object target = Reflect.call(item, "getTargetComponent");
            Object user = Reflect.field(item, "user");
            if (!(target instanceof ComponentName)) {
                return false;
            }
            ComponentName component = (ComponentName) target;
            Object title = Reflect.field(item, "title");
            Item app = Item.app(component.getPackageName(), component.getClassName(),
                    IconInfo.serialOf(ctx, user instanceof android.os.UserHandle
                            ? (android.os.UserHandle) user : null),
                    title != null ? title.toString() : component.getPackageName());
            Object rankValue = Reflect.field(item, "rank");
            int rank = rankValue instanceof Integer ? (Integer) rankValue
                    : folder.children.size();
            String folderId = folder.id;
            boolean[] filed = new boolean[1];
            NativeDrawerHooks.rearrange("put " + app.key() + " into a folder at " + rank,
                    (order, folders) -> {
                        for (Item f : folders) {
                            if (f.id.equals(folderId)) {
                                filed[0] = DrawerArrange.fileIntoAt(order, folders, f, app, rank);
                                if (filed[0]) {
                                    keepBuilt(f);
                                    for (int i = 0; i < f.children.size(); i++) {
                                        Item child = f.children.get(i);
                                        if (child.key().equals(app.key())) {
                                            noteChild(item, child);
                                            sLastFiledAt = i;
                                        }
                                    }
                                }
                                return filed[0];
                            }
                        }
                        return false;
                    });
            if (filed[0]) {
                sLastFiled = app.key();
            }
            return filed[0];
        } catch (Throwable t) {
            L.w("zui folders: a dropped app not filed (" + t + ")");
            return false;
        }
    }

    /**
     * The apps chosen with a dragged one ({@link DrawerSelect}) into the folder of ours that took
     * it ({@code Folder.onDrop}), after it and in their order. ZUI's own multi-drag files its
     * chosen items after a drop into a folder ({@code MultiDragSupport.onDropFolderSuccess});
     * ours are not ZUI's, so only the dragged app went in and the others went back to their
     * cells (owner on 0.0.274). Into our model as the dropped app went ({@link #filedByDrop}),
     * and shown by ZUI's open folder as ZUI's Add apps shows its picks ({@link ZuiFolderAdd}):
     * the drawer's own entry made into ZUI's item for a folder ({@code makeWorkspaceItem}, as
     * ZUI's drop makes it) and added through ZUI's {@code addFolderContent}, its writes kept out
     * of ZUI's database ({@link ZuiFolderWrites}). How many went in.
     */
    static int fileCarried(Object zuiFolder, List<String> keys) {
        try {
            Object info = Reflect.field(zuiFolder, "mInfo");
            Object id = info == null ? null : Reflect.field(info, "id");
            Item folder = id instanceof Integer ? folderOfId((Integer) id) : null;
            if (folder == null || keys.isEmpty() || !(zuiFolder instanceof View)) {
                return 0;
            }
            String folderId = folder.id;
            int after = sLastFiledAt;
            List<Item> filed = new java.util.ArrayList<>();
            NativeDrawerHooks.rearrange("put " + keys.size() + " chosen into a folder after "
                    + after, (order, folders) -> {
                        for (Item f : folders) {
                            if (!f.id.equals(folderId)) {
                                continue;
                            }
                            for (String key : keys) {
                                Item app = DrawerArrange.appOf(key, NativeDrawerHooks.labelOf(key));
                                if (app != null && DrawerArrange.fileIntoAt(order, folders, f, app,
                                        after + 1 + filed.size())) {
                                    filed.add(app);
                                }
                            }
                            if (!filed.isEmpty()) {
                                keepBuilt(f);
                            }
                            return !filed.isEmpty();
                        }
                        return false;
                    });
            Context ctx = ((View) zuiFolder).getContext();
            Class<?> itemInfo = Class.forName("com.android.launcher3.model.data.ItemInfo", false,
                    zuiFolder.getClass().getClassLoader());
            Method add = zuiFolder.getClass().getMethod("addFolderContent", itemInfo, int.class,
                    boolean.class);
            int shown = 0;
            for (Item app : filed) {
                Object entry = NativeDrawerHooks.entryOf(app.key());
                Object made = entry == null ? null : entry.getClass()
                        .getMethod("makeWorkspaceItem", Context.class).invoke(entry, ctx);
                Item child = null;
                for (Item c : folder.children) {
                    if (c.key().equals(app.key())) {
                        child = c;
                    }
                }
                if (made == null || child == null) {
                    continue;
                }
                setInt(made, "container", (Integer) id);
                noteChild(made, child);
                add.invoke(zuiFolder, made, folder.children.indexOf(child), true);
                shown++;
            }
            L.i("zui folders: " + filed.size() + " chosen with the dropped app into "
                    + folder.label + ", " + shown + " shown by ZUI's folder");
            return filed.size();
        } catch (Throwable t) {
            L.w("zui folders: the chosen apps not filed with the dropped one (" + t + ")");
            return 0;
        }
    }

    /** Our folder's ZUI icons are kept as they are for its new contents: ZUI shows them already. */
    private static void keepBuilt(Item folder) {
        String content = contentOf(folder);
        for (Map<String, Built> mine : BUILT.values()) {
            Built built = mine.get(folder.id);
            if (built != null) {
                built.content = content;
            }
        }
    }

    /** Saves a change made in ZUI's folder itself, which already shows it: no rebuild for it. */
    static void saveKeepingIcons(Context ctx, Item folder) {
        DrawerStore.shared(ctx).save();
        keepBuilt(folder);
    }

    static void closeOpenFolders() {
        for (Map<String, Built> mine : BUILT.values()) {
            for (Built built : mine.values()) {
                if (isOpen(built)) {
                    try {
                        Object folder = built.icon.getClass().getMethod("getFolder")
                                .invoke(built.icon);
                        folder.getClass().getMethod("close", boolean.class).invoke(folder, true);
                    } catch (Throwable t) {
                        L.d("zui folders: could not close (" + t + ")");
                    }
                }
            }
        }
    }

    // --- small things --------------------------------------------------------

    private static boolean isOpen(Built built) {
        try {
            Object folder = built.icon.getClass().getMethod("getFolder").invoke(built.icon);
            return Boolean.TRUE.equals(folder.getClass().getMethod("isOpen").invoke(folder));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object adapterOf(Object appsList) {
        for (Field f : Mirror.fields(appsList.getClass())) {
            if (f.getType().getName().equals(ADAPTER)) {
                return Mirror.get(f, appsList);
            }
        }
        return null;
    }

    /** Whether a context is ZUI's bar itself, which handles its own folders' back. */
    static boolean isBar(Object activity) {
        try {
            return activity != null && Class.forName(
                    "com.android.launcher3.taskbar.TaskbarActivityContext", false,
                    activity.getClass().getClassLoader()).isInstance(activity);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isLauncher(Object activity) {
        try {
            return activity != null && Class.forName(LAUNCHER, false,
                    activity.getClass().getClassLoader()).isInstance(activity);
        } catch (Throwable t) {
            return false;
        }
    }

    static Object lookup(Context ctx) throws Exception {
        return Class.forName("com.android.launcher3.views.ActivityContext", false,
                ctx.getClassLoader()).getMethod("lookupContext", Context.class).invoke(null, ctx);
    }

    private static int displayOf(Context ctx) {
        try {
            Display display = ctx.getDisplay();
            return display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return Display.DEFAULT_DISPLAY;
        }
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        Field f = fieldOf(target.getClass(), name);
        f.setInt(target, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        fieldOf(target.getClass(), name).set(target, value);
    }

    private static Field fieldOf(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException next) {
                // Up a level.
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Once: ZUI's folder icon beside the drawer's own icons, to compare their sizes. */
    private static void describeOnce(View icon) {
        if (sSaidIcon) {
            return;
        }
        sSaidIcon = true;
        icon.post(() -> {
            View neighbour = null;
            ViewGroup list = icon.getParent() != null && icon.getParent().getParent()
                    instanceof ViewGroup ? (ViewGroup) icon.getParent().getParent() : null;
            if (list != null) {
                for (int i = 0; i < list.getChildCount() && neighbour == null; i++) {
                    View c = list.getChildAt(i);
                    if (!(c instanceof FrameLayout) || c.getClass() != FrameLayout.class) {
                        neighbour = c;
                    }
                }
            }
            L.i("zui folders: ZUI's folder icon " + icon.getWidth() + "x" + icon.getHeight()
                    + (neighbour != null ? ", a drawer icon beside it " + neighbour.getWidth()
                    + "x" + neighbour.getHeight() : ""));
        });
    }
}
