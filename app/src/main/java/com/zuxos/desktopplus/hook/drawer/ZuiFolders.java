package com.zuxos.desktopplus.hook.drawer;

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
import com.zuxos.desktopplus.hook.IconPress;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.hook.ZuiMenu;
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

    /** One of our folders as ZUI's, in one of ZUI's contexts. */
    private static final class Built {
        final View icon;
        int version;

        Built(View icon, int version) {
            this.icon = icon;
            this.version = version;
        }
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
            ZuiFolderWrites.install(loader);
            ZuiFolderAdd.install(loader, folder);
            ZuiFolderBlur.install(folder);
            Health.hooked("drawer: our folders as ZUI's", hooked);
            L.i("zui folders: our drawer folders as ZUI's own on the tablet x" + hooked);
        } catch (Throwable t) {
            off("not installed (" + t + ")");
        }
    }

    /**
     * ZUI picks a folder's motion by where it is: the bar ({@code getZuiTbFolderAnimator}) or
     * else the home screen ({@code getZuiFolderAnimator}, which needs {@code Launcher}). Our
     * folders in the taskbar's drawer get ZUI's Launcher ({@link #hookHomeContext}) and so the
     * home screen's motion, as in ZUX Home's drawer. Only should there be no Launcher at all do
     * they fall back to ZUI's own {@code getAnimatorZui}, which asks where the folder is at each
     * step - and to no motion rather than a crash (01:32) should that fail too.
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
        return XposedBridge.hookAllMethods(manager, "getAnimator", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object folder = ofFolder.get(param.thisObject);
                    Object where = folder == null ? null : sFolderActivity.get(folder);
                    if (!isOurs(folder) || isLauncher(where) || bar.isInstance(where)
                            || launcher() != null) {
                        // With ZUI's Launcher at hand our folders take its home motion (below).
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
        }).size();
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
        return hooked;
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
     * home screen for a second, then shrinking to nowhere (14:12). So as the sheet starts to
     * close, any folder open in its window closes with it, the way ZUI closes its views there
     * ({@code closeOpenViews(context, animate, TYPE_FOLDER)}).
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
            int folderType = floating.getField("TYPE_FOLDER").getInt(null);
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
                        closeViews.invoke(null, activity, animate, folderType);
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
        icon.post(() -> {
            try {
                ViewGroup list = icon.getParent() != null
                        && icon.getParent().getParent() instanceof ViewGroup
                        ? (ViewGroup) icon.getParent().getParent() : null;
                Look drawer = drawerIconIn(list);
                android.graphics.Rect preview = new android.graphics.Rect();
                icon.getClass().getMethod("getPreviewBounds", android.graphics.Rect.class)
                        .invoke(icon, preview);
                if (drawer == null || preview.width() <= 0) {
                    return;
                }
                float scale = drawer.size / (float) preview.width();
                icon.setPivotX(preview.exactCenterX());
                icon.setPivotY(preview.top);
                icon.setScaleX(scale);
                icon.setScaleY(scale);
                icon.setTranslationY(drawer.top - preview.top - icon.getTop());
                fitName(icon, drawer, scale);
                if (!sSaidAlign) {
                    sSaidAlign = true;
                    L.i("zui folders: preview " + preview.width() + "px at " + preview.top
                            + " to the drawer's " + drawer.size + "px at " + drawer.top
                            + " (x" + scale + ")");
                }
            } catch (Throwable t) {
                L.d("zui folders: not aligned (" + t + ")");
            }
        });
    }

    private static boolean sSaidAlign;

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
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, drawer.textPx / scale);
        name.setMaxLines(drawer.lines);
        name.setEllipsize(drawer.ellipsize);
    }

    /** ZUI's own app icons in a drawer list: icon size and top, and how their names are set. */
    private static final class Look {
        final int size;
        final int top;
        final float textPx;
        final int lines;
        final android.text.TextUtils.TruncateAt ellipsize;

        Look(android.widget.TextView app, int size) {
            this.size = size;
            this.top = app.getPaddingTop();
            this.textPx = app.getTextSize();
            // -1 when ZUI sets no limit; a name never takes more than two lines of a cell.
            this.lines = app.getMaxLines() > 0 ? Math.min(app.getMaxLines(), 2) : 1;
            this.ellipsize = app.getEllipsize() != null ? app.getEllipsize()
                    : android.text.TextUtils.TruncateAt.END;
        }
    }

    /** Each drawer list's look, as measured on one of ZUI's own icons in it. */
    private static final Map<ViewGroup, Look> DRAWER_ICON = new WeakHashMap<>();

    /**
     * The look of ZUI's own app icons in a drawer list, measured on one of them (its top
     * compound drawable, drawn at its top padding, and its name) and kept for when none is on
     * screen.
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
                            art.getBounds().width());
                    DRAWER_ICON.put(list, measured);
                    return measured;
                }
            }
        }
        return DRAWER_ICON.get(list);
    }

    /** ZUI's folder icon for one of our folders in this context, built once per change. */
    private static View iconFor(Context activity, ViewGroup cell, Item folder) throws Exception {
        Context app = AppCtx.get() != null ? AppCtx.get() : activity;
        int version = DrawerStore.shared(app).version();
        Map<String, Built> mine = BUILT.get(activity);
        if (mine == null) {
            mine = new HashMap<>();
            BUILT.put(activity, mine);
        }
        Built built = mine.get(folder.id);
        if (built != null && (built.version == version || isOpen(built))) {
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
        mine.put(folder.id, new Built(icon, version));
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
            Context app = AppCtx.get() != null ? AppCtx.get() : bar;
            int version = DrawerStore.shared(app).version();
            Map<String, Built> mine = BUILT.get(bar);
            if (mine == null) {
                mine = new HashMap<>();
                BUILT.put(bar, mine);
            }
            Built built = mine.get(folder.id);
            View icon;
            if (built != null && (built.version == version || isOpen(built))) {
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
                mine.put(folder.id, new Built(icon, version));
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
     * An app held inside one of our folders: ZUI's app popup as ZUI shows it in that context -
     * on ZUX Home without a drag ({@code LauncherCustom.skipHotseatDrag}), in the taskbar's
     * drawer with ZUI's drawer drag.
     */
    private static boolean holdInside(Object folder, View view) {
        try {
            if (!isOurs(folder)) {
                return false;
            }
            Context ctx = view.getContext();
            Object activity = lookup(ctx);
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

    /** Our folder behind one of ZUI's folder icons of ours, or null. */
    static Item folderOfIcon(View view) {
        Object tag = view == null ? null : view.getTag();
        Object id = tag == null ? null : Reflect.field(tag, "id");
        return id instanceof Integer && (Integer) id <= ID_BASE ? folderOfId((Integer) id) : null;
    }

    /**
     * "Remove from folder" for one of ZUI's items inside our folders - for ZUI's app popup - or
     * null for any other item.
     */
    public static Runnable removeAction(Object item) {
        return childOf(item) == null ? null : () -> removeFromFolder(item);
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

    /** Takes an app out of the folder it is in, as {@code DrawerFolderWindow} does. */
    static void removeFromFolder(Object item) {
        Item child = childOf(item);
        Context ctx = AppCtx.get();
        if (child == null || ctx == null) {
            return;
        }
        DrawerStore store = DrawerStore.shared(ctx);
        Item folder = store.folderContaining(child.key());
        if (folder == null) {
            return;
        }
        folder.children.remove(child);
        if (folder.children.size() <= 1) {
            // One app left is not a folder: both go back to the list.
            folder.children.clear();
            store.folders().remove(folder);
        }
        store.save();
        closeOpenFolders();
        NativeDrawerHooks.refreshList();
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
        java.util.TreeMap<Integer, Item> byRank = new java.util.TreeMap<>();
        for (Object item : items) {
            Item child = childOf(item);
            Object rank = Reflect.field(item, "rank");
            if (child != null && rank instanceof Integer) {
                byRank.put((Integer) rank, child);
            }
        }
        // ZUI's order for the apps it shows; any of ours it does not (not installed) after them.
        List<Item> now = new java.util.ArrayList<>(byRank.values());
        for (Item child : folder.children) {
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

    /** Saves a change made in ZUI's folder itself, which already shows it: no rebuild for it. */
    static void saveKeepingIcons(Context ctx, Item folder) {
        DrawerStore store = DrawerStore.shared(ctx);
        store.save();
        int version = store.version();
        for (Map<String, Built> mine : BUILT.values()) {
            Built built = mine.get(folder.id);
            if (built != null) {
                built.version = version;
            }
        }
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
