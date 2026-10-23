package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconPress;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.logic.DrawerArrange;
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
 * ({@link #TYPE}), which ZUI's adapter has no case for; its cell is made and filled by
 * {@link ZuiFolderCells}. Whatever the folder would write goes to our model
 * ({@link ZuiFolderModel}), never ZUI's database ({@link ZuiFolderWrites}). On the tablet's bar
 * the same folders are ZUI's bar folders ({@link ZuiFolderBar}). Holding an app inside shows
 * ZUI's app popup, as ZUI does for a hold without a drag. The monitor keeps our own folder
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
     * dropped to 72 a second on a 144 Hz screen) and drew each new one a frame at ZUI's home size
     * before it was aligned (the top row's folders flashed).
     */
    static final class Built {
        final View icon;
        String content;

        Built(View icon, String content) {
            this.icon = icon;
            this.content = content;
        }
    }

    /** A folder's name and apps, in order: what its ZUI icon shows. */
    static String contentOf(Item folder) {
        StringBuilder out = new StringBuilder(folder.label != null ? folder.label : "");
        for (Item child : folder.children) {
            out.append('|').append(child.key());
        }
        return out.toString();
    }

    /** Per ZUI context (ZUX Home's, the taskbar drawer's): its folders by our folder id. */
    static final Map<Object, Map<String, Built>> BUILT = new WeakHashMap<>();
    /** The drawer's own entries for the apps filed in folders, by our key, as last seen. */
    static Map<String, Object> sFiled = new HashMap<>();

    static Field sViewType;
    static Field sItemInfo;
    static Field sActivity;
    private static Field sFolderInfo;
    private static Field sFolderActivity;
    static Constructor<?> sHolder;
    static Method sType;
    static Field sItemView;
    static Method sInflate;
    private static boolean sOff;
    static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

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
                                param.setResult(ZuiFolderCells.cell((ViewGroup) param.args[0],
                                        param.thisObject));
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(adapter, "onBindViewHolder",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length >= 2 && param.args[1] instanceof Integer
                                    && ZuiFolderCells.bind(param.thisObject, param.args[0],
                                    (Integer) param.args[1])) {
                                param.setResult(null);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(item, "asApp", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ZuiFolderCells.born(param.getResult());
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
            hooked += hookDissolve(folder);
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
     * ZUI's "Enlarge folder" ({@code SystemShortcut.SWITCH_FOLDER}) is never offered for one of our
     * folders. ZUI's factory makes it for any folder outside desktop mode, and its click resizes
     * the folder on the home screen's grid ({@code SwitchFolder.onClick} casts the icon's layout
     * params to {@code CellLayoutLayoutParams}): for our folders, in a drawer or on the bar, that
     * stopped ZUX Home. The factory, asked about one of ours, answers with no shortcut - ZUI's
     * decision, not its menu undone afterwards. Its other folder shortcut, Rename, only opens the
     * folder and stays.
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
                                        && ZuiFolderModel.folderOfId((Integer) id) != null) {
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
     * ZUI dissolving one of our folders breaks it in our model instead. ZUI dissolves a folder left
     * with one app ({@code Folder.X0}, its delegate's {@code i}: the last app put in the folder's
     * place on the home screen) when it is bound with one ({@code E0}), when one of two is dragged
     * out ({@code W0}) or removed. For ours, the taskbar drawer's delegate does nothing and ZUI
     * will not open a folder of one ({@code Folder.a1}): it stayed, one app, never opening; in ZUX
     * Home's drawer the delegate ran ZUI's home-screen replace on it, its writes kept out of ZUI's
     * database. For our folders ZUI's delegate is never asked: the folder's apps take its place in
     * the drawer ({@link DrawerArrange#breakUp}), after ZUI's call is done.
     */
    private static int hookDissolve(Class<?> folderCls) {
        Method dissolve = null;
        for (Method m : folderCls.getDeclaredMethods()) {
            if (m.getName().equals("X0") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == boolean.class) {
                dissolve = m;
            }
        }
        if (dissolve == null) {
            L.w("zui folders: ZUI's dissolve of a folder (X0) not found - a folder left with one"
                    + " app breaks when the drawer is next loaded");
            return 0;
        }
        XposedBridge.hookMethod(dissolve, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isOurs(param.thisObject)) {
                    param.setResult(null);
                    Object info = folderInfoOf(param.thisObject);
                    Object id = info == null ? null : Reflect.field(info, "id");
                    Object count = Reflect.call(param.thisObject, "getItemCount");
                    if (id instanceof Integer && count instanceof Integer) {
                        int folderId = (Integer) id;
                        int zuiHas = (Integer) count;
                        MAIN.post(() -> breakUp(folderId, zuiHas));
                    }
                }
            }
        });
        return 1;
    }

    /** Our folder behind one of ZUI's folders, or null. */
    static Item folderOfZui(Object zuiFolder) {
        Object info = folderInfoOf(zuiFolder);
        Object id = info == null ? null : Reflect.field(info, "id");
        return id instanceof Integer && (Integer) id <= ID_BASE
                ? ZuiFolderModel.folderOfId((Integer) id) : null;
    }

    /** ZUI's {@code FolderInfo} of a folder of ZUI's, or null. */
    private static Object folderInfoOf(Object folder) {
        try {
            return folder == null ? null : sFolderInfo.get(folder);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Our folder behind a folder ZUI dissolved, broken into its apps where it still is one - and
     * only when ZUI's folder shows all the apps of ours ZUI has ({@code zuiHas}): with fewer, an
     * app of it is out on a drag, which ends by showing the folder whole again
     * ({@link DrawerSession}). An app taken out of a folder of two and let go on it again broke the
     * folder that still held both.
     */
    private static void breakUp(int id, int zuiHas) {
        Item folder = ZuiFolderModel.folderOfId(id);
        if (folder == null) {
            return;
        }
        String key = folder.key();
        int known = 0;
        for (Item child : folder.children) {
            if (sFiled.containsKey(child.key())) {
                known++;
            }
        }
        if (zuiHas < known) {
            if (L.once("ZuiFolders.kept " + key)) {
                L.i("zui folders: " + folder.label + " kept - ZUI's folder shows " + zuiHas
                        + " of its " + known + " apps while one is dragged");
            }
            return;
        }
        boolean broke = NativeDrawerHooks.rearrange("broke " + folder.label
                + " - ZUI's folder of one app", (order, folders) ->
                DrawerArrange.breakUp(order, folders, key));
        if (broke) {
            ZuiFolderModel.closeOpenFolders();
        } else if (L.once("ZuiFolders.kept " + key)) {
            // Already taken apart by the drop that emptied it, or a folder of the bar.
            L.d("zui folders: " + folder.label + " not broken - not a folder of the drawer now");
        }
    }

    /**
     * The picture ZUI drags of one of our drawer folders is drawn at its natural size, with its
     * round corners. ZUI draws the dragged view into a picture the size of its unscaled preview but
     * at the view's own scale ({@code DragPreviewProvider.createDrawable}: made for an icon shrunk
     * by a press); ours are scaled up to the drawer's icon size ({@link ZuiFolderCells#align}), so
     * the folder overflowed the picture and its corners were cut square. For the drawing only, the
     * icon is at scale 1; ZUI then reads the scale from the view ({@code getScaleAndPosition}) and
     * the dragged picture is as large as the folder shown.
     *
     * <p>Shown ×1.53 in portrait, that picture of the natural size was blurred. While the icon is
     * still at 1, ZUI's own drawing of it ({@code drawDragView}) is drawn again at the shown scale,
     * into a hardware bitmap as ZUI's {@code BitmapRenderer} makes it, and handed back at ZUI's
     * size ({@link SharpPicture}): ZUI places and scales it as before, and it is drawn at full
     * resolution.
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
                    if (view instanceof View && ZuiFolderModel.folderOfIcon((View) view) != null
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
                        if (scale[0] > 1.01f && param.getResult() instanceof Drawable) {
                            Drawable sharp = sharp(param.thisObject,
                                    (Drawable) param.getResult(), scale[0]);
                            if (sharp != null) {
                                param.setResult(sharp);
                            }
                        }
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
     * ZUI's drag picture of a folder ({@code small}) drawn again {@code scale} times as large by
     * ZUI's own {@code drawDragView}, at ZUI's size; null, and ZUI's kept, when it cannot be.
     */
    private static Drawable sharp(Object provider, Drawable small, float scale) {
        try {
            int w = small.getIntrinsicWidth();
            int h = small.getIntrinsicHeight();
            Method draw = drawDragView(provider.getClass());
            if (w <= 0 || h <= 0 || draw == null) {
                throw new IllegalStateException(draw == null ? "no drawDragView" : "no size");
            }
            int bigW = Math.round(w * scale);
            int bigH = Math.round(h * scale);
            Picture picture = new Picture();
            Canvas canvas = picture.beginRecording(bigW, bigH);
            canvas.scale((float) bigW / w, (float) bigH / h);
            draw.invoke(provider, canvas, 1f);
            picture.endRecording();
            return new SharpPicture(Bitmap.createBitmap(picture), w, h);
        } catch (Throwable t) {
            if (L.once("ZuiFolders.blurry")) {
                L.w("zui folders: a held folder keeps ZUI's picture of its natural size (" + t
                        + ")");
            }
            return null;
        }
    }

    /** {@code DragPreviewProvider.drawDragView(Canvas, float)}, by name or else by its kind. */
    private static Method drawDragView(Class<?> provider) {
        Method byKind = null;
        for (Class<?> c = provider; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (types.length == 2 && types[0] == Canvas.class && types[1] == float.class
                        && m.getReturnType() == void.class) {
                    if (m.getName().equals("drawDragView")) {
                        m.setAccessible(true);
                        return m;
                    }
                    if (byKind == null) {
                        byKind = m;
                    }
                }
            }
        }
        if (byKind != null) {
            byKind.setAccessible(true);
        }
        return byKind;
    }

    /**
     * A picture drawn large, at the size of ZUI's own: ZUI's drag view sizes and scales it as
     * the picture it made, and the large bitmap is what reaches the screen.
     */
    private static final class SharpPicture extends Drawable {
        private final Bitmap mBitmap;
        private final int mWidth;
        private final int mHeight;
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        SharpPicture(Bitmap bitmap, int width, int height) {
            mBitmap = bitmap;
            mWidth = width;
            mHeight = height;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(mBitmap, null, getBounds(), mPaint);
        }

        @Override
        public int getIntrinsicWidth() {
            return mWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return mHeight;
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            mPaint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * ZUI picks a folder's motion by where it is: the bar ({@code getZuiTbFolderAnimator}) or else
     * the home screen ({@code getZuiFolderAnimator}, which needs {@code Launcher}). Our folders in
     * the taskbar's drawer get ZUI's Launcher ({@link #hookHomeContext}) and so the home screen's
     * motion, as in ZUX Home's drawer. Only should there be no Launcher at all do they fall back to
     * ZUI's own {@code getAnimatorZui}, which asks where the folder is at each step - and to no
     * motion rather than a crash should that fail too.
     *
     * <p>One motion for every folder of ours, in either drawer, whatever the home screen is
     * doing. While ZUI builds it ({@link #sBuilding}):
     * <ul>
     *   <li>the home screen's folder scale ({@code Workspace.getNewScaleFolder()}, about 0.89 while
     *       a drawer is up) reads 1.0. ZUI animates the apps to that scale and then shows the real
     *       ones at full size, so they grew and then snapped - in the taskbar's drawer and, left
     *       out of that fix, in ZUX Home's own;</li>
     *   <li>Recents reads as not showing ({@code Launcher.isInOverViewState()}). ZUI leaves the Add
     *       apps button out of the motion while it shows, and the button then popped in after the
     *       folder had opened, when the drawer was opened from Recents.</li>
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
     * taskbar's drawer unlike ZUX Home's (see-through). For our folders there it answers
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

    /** Our folders and ZUI's popups open in the taskbar's drawer, with their back callbacks. */
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
     * over the folder first, then the folder, then the drawer. The drawer takes back by registering
     * itself with its window ({@code TaskbarAllAppsSlideInView}); ZUI's folder and popups register
     * nothing there, so back closed the drawer and left the folder, and then the folder and left
     * its popup. While our folder is open a callback is registered the same way - the last one
     * registered is the one called - and it does what ZUI's drawer does with a back key
     * ({@code TaskbarOverlayDragLayer.dispatchKeyEvent}): closes ZUI's top open view
     * ({@code AbstractFloatingView.getTopOpenView}).
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
        hooked += hookPopupBack(folderCls.getClassLoader());
        return hooked;
    }

    /**
     * Back closes ZUI's popup in the taskbar's drawer, not the drawer under it. A popup there - an
     * app's, or a folder's menu opened by a hold or the controller's Triangle - registers no back
     * callback, so back went to the drawer's and closed the whole drawer with the menu. While a
     * popup is open there one is registered as for our folders, closing ZUI's top open view; ZUX
     * Home's own back already closes its popups first.
     */
    private static int hookPopupBack(ClassLoader loader) {
        Class<?> popup = Reflect.findClass("com.android.launcher3.popup.ArrowPopup", loader);
        if (popup == null) {
            L.w("zui folders: ZUI's popups not found - back in the taskbar's drawer closes it");
            return 0;
        }
        int hooked = XposedBridge.hookAllMethods(popup, "show", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                registerPopupBack(param.thisObject);
            }
        }).size();
        XC_MethodHook off = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                unregisterBack(param.thisObject);
            }
        };
        hooked += XposedBridge.hookAllMethods(popup, "handleClose", off).size();
        hooked += XposedBridge.hookAllMethods(popup, "closeComplete", off).size();
        return hooked;
    }

    private static void registerPopupBack(Object popup) {
        try {
            if (!(popup instanceof View) || BACK.containsKey(popup)) {
                return;
            }
            Object where = lookup(((View) popup).getContext());
            if (where == null || isLauncher(where) || isBar(where)
                    || !where.getClass().getName().endsWith("TaskbarOverlayContext")) {
                return;
            }
            android.window.OnBackInvokedDispatcher dispatcher =
                    ((View) popup).findOnBackInvokedDispatcher();
            if (dispatcher != null) {
                android.window.OnBackInvokedCallback callback = () -> closeTop(popup, where);
                dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
                BACK.put(popup, new Back(dispatcher, callback));
            }
        } catch (Throwable t) {
            L.d("zui folders: back not given to the popup (" + t + ")");
        }
    }

    /**
     * A back key in the taskbar's drawer closes one thing, as the back gesture does. ZUX Home takes
     * back through callbacks ({@code enableOnBackInvokedCallback}), so Android hands a back key to
     * the top callback - our folder's, closing the folder - and then passes the same key on to the
     * drawer's views marked cancelled ({@code ViewRootImpl.doOnBackKeyEvent}: "forward a cancelled
     * event so that following stages cancel their back logic"). ZUI's drawer layer acts on a back
     * key's release without asking whether it was cancelled
     * ({@code TaskbarOverlayDragLayer.dispatchKeyEvent}), and closed the drawer under the folder
     * just closed: a controller's B (Android's back for it) or a keyboard's back key took both
     * away. A cancelled back is left to the callback that had it.
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

    /**
     * ZUI's top open view in the drawer, closed as a back key closes it; else the folder or popup
     * the callback was registered for.
     */
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
        if (folder instanceof android.window.OnBackInvokedCallback) {
            ((android.window.OnBackInvokedCallback) folder).onBackInvoked();
        } else {
            Reflect.call(folder, "close", true);
        }
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
     * ({@code AbstractFloatingView.closeAllOpenViews}), and the window stays while any is open; the
     * drawer's sheet closed on its own - our start button, a swipe - left our folder over the home
     * screen for a second, then shrinking to nowhere, and an app's popup over the folder stayed as
     * the sheet slid away. So as the sheet starts to close while one of our folders is open,
     * everything open in its window closes with it, as ZUI closes it - every type but the sheet
     * itself and the window's own proxy ({@code closeOpenViews(context, animate,
     * TYPE_ALL & ~(TYPE_TASKBAR_ALL_APPS | TYPE_TASKBAR_OVERLAY_PROXY))}).
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

    static void off(String why) {
        if (!sOff) {
            sOff = true;
            L.w("zui folders: off, our own folder window instead - " + why);
        }
    }

    // --- one of our folders as ZUI's -----------------------------------------

    /**
     * The name under the icon in the colour ZUI gives every name in its drawer
     * ({@code all_app_item_text_color}, as its adapter sets it) - the folder icon's own is the
     * home screen's, made for the wallpaper.
     */
    static void drawerLabel(Context ctx, View icon) {
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
    static void noteDelegate(View icon) {
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
    static Object folderInfo(Context activity, Item folder) throws Exception {
        return folderInfo(activity, folder, ZuiFolderModel.idOf(folder),
                child -> sFiled.get(child.key()), ZuiFolderModel.CHILDREN);
    }

    static Object folderInfo(Context activity, Item folder, int id,
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
        if (rank == 0 && L.once("ZuiFolders.empty " + folder.id)) {
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

    // --- opening and holding ----------------------------------------------

    /** Opens the folder of a folder icon where it is, as ZUI's taskbar does ({@code x0}). */
    static void openHere(View icon) {
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
     * An app held inside one of our folders: its popup, and its drag once the finger moves. In
     * ZUX Home's drawer that is ZUI's own hold on a home-screen folder's app, the folder the
     * drag's source; in the taskbar's drawer, ZUI's drawer hold, the folder made the source
     * ({@link DrawerRearrange}). On ZUX Home without the drag (only the popup,
     * {@code LauncherCustom.skipHotseatDrag}) when the drawer's drag is switched off.
     */
    private static boolean holdInside(Object folder, View view) {
        try {
            if (!isOurs(folder)) {
                return false;
            }
            Context ctx = view.getContext();
            Object activity = lookup(ctx);
            if (isLauncher(activity) && Cfg.drawerDrag()) {
                // ZUI's own hold on a home-screen folder's app (Folder.onLongClick): its popup,
                // then its drag with the folder as the source, which rearranges inside the folder;
                // out of it the drawer takes the drag (DrawerRearrange). Made a drag of the
                // drawer's entry instead, the folder shut as it started and nothing could be moved
                // inside it.
                return false;
            }
            if (isLauncher(activity)) {
                Class.forName(POPUP, false, ctx.getClassLoader())
                        .getMethod("showForIcon", View.class).invoke(null, view);
                IconPress.release(view);
                return true;
            }
            // The taskbar's drawer: what ZUI's drawer does with a hold on its own icons
            // (getAllAppsItemLongClickListener, the drawer's TaskbarDragController): its popup and
            // its drag, in the drawer's own window, with the press let go by ZUI. Through the bar
            // instead (onClickIconWithRightMouse), the bar's window went full screen over the
            // drawer and took every touch from the popup.
            Object hold = activity.getClass().getMethod("getAllAppsItemLongClickListener")
                    .invoke(activity);
            return hold instanceof View.OnLongClickListener
                    && ((View.OnLongClickListener) hold).onLongClick(view);
        } catch (Throwable t) {
            L.d("zui folders: no popup inside our folder (" + t + ")");
            return false;
        }
    }

    // --- small things --------------------------------------------------------

    static boolean isOpen(Built built) {
        try {
            Object folder = built.icon.getClass().getMethod("getFolder").invoke(built.icon);
            return Boolean.TRUE.equals(folder.getClass().getMethod("isOpen").invoke(folder));
        } catch (Throwable t) {
            return false;
        }
    }

    static Object adapterOf(Object appsList) {
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

    static int displayOf(Context ctx) {
        try {
            Display display = ctx.getDisplay();
            return display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return Display.DEFAULT_DISPLAY;
        }
    }

    static void setInt(Object target, String name, int value) throws Exception {
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
    static void describeOnce(View icon) {
        if (!L.once("ZuiFolders.icon")) {
            return;
        }
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
