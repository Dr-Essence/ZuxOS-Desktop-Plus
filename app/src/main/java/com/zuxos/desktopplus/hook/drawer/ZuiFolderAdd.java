package com.zuxos.desktopplus.hook.drawer;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.logic.DrawerArrange;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUI's Add apps in our drawer folders: ZUI's button, ZUI's picker, our model.
 *
 * <p>ZUI's folder makes its Add apps button when it opens ({@code Folder.createAddAppButton}),
 * and the button opens ZUI's picker ({@code BigFolderIconSelectDialog.showDialog}). ZUI's OK
 * ({@code L}) puts the apps picked into the folder and the ones unpicked onto the home screen;
 * for our folders the same choice goes to our folder instead: the apps picked join it, out of any
 * other folder of ours, the ones unpicked go back to the drawer's list. The open folder shows it
 * through ZUI's own {@code addFolderContent} and {@code removeFolderContent}, whose saves are kept
 * out of ZUI's database ({@link ZuiFolderWrites}), and the picker closes with ZUI's own motion
 * ({@code K}).
 *
 * <p>In the taskbar's drawer the button's tap goes to the bar's app launch, not the picker, so it
 * is given the picker; and the picker, a window of the home screen's, would open under the drawer
 * - it gets the drawer's own window type instead.
 */
final class ZuiFolderAdd {

    private static final String DIALOG = "com.zui.launcher.folder.bigfolder.BigFolderIconSelectDialog";
    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    /** {@code BigFolderWorkspaceItemInfo.bigFolderStatus}: picked, and unpicked. */
    private static final int ADD = 2;
    private static final int DELETE = 1;

    /** The picker being made is for a folder in the taskbar's drawer. Main thread only. */
    private static boolean sOverDrawer;
    private static Method sShow;
    private static Method sClose;

    private ZuiFolderAdd() {
    }

    static void install(ClassLoader loader, Class<?> folderCls) {
        try {
            Class<?> dialog = Reflect.findClass(DIALOG, loader);
            Class<?> launcher = Reflect.findClass(LAUNCHER, loader);
            if (dialog == null || launcher == null) {
                L.w("zui folder add: ZUI's picker not found, no Add apps in our folders");
                return;
            }
            Method ok = null;
            for (Method m : dialog.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (m.getName().equals("showDialog") && p.length == 2) {
                    sShow = m;
                } else if (p.length == 8 && p[0].getSimpleName().equals("BigFolderIconAdapter")) {
                    ok = m;
                } else if (p.length == 3 && p[0] == launcher && p[1] == View.class
                        && p[2] == View.class) {
                    sClose = m;
                }
            }
            if (sShow == null || ok == null || sClose == null) {
                L.w("zui folder add: ZUI's picker is not as expected, no Add apps in our folders");
                return;
            }
            XposedBridge.hookMethod(ok, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (picked(param.args)) {
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.hookMethod(sShow, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sOverDrawer = overDrawer(param.args[1]);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sOverDrawer = false;
                }
            });
            XposedBridge.hookAllConstructors(dialog, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sOverDrawer) {
                        aboveTheDrawer((Dialog) param.thisObject);
                    }
                }
            });
            XposedBridge.hookAllMethods(folderCls, "createAddAppButton", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    wireInDrawer(param.thisObject);
                }
            });
            L.i("zui folder add: ZUI's Add apps in our folders, kept in our model");
        } catch (Throwable t) {
            L.w("zui folder add: not installed (" + t + ")");
        }
    }

    /** Whether a folder of ours is open in the taskbar's drawer rather than on ZUX Home. */
    private static boolean overDrawer(Object folder) {
        try {
            return folder != null && ZuiFolders.isOurs(folder)
                    && !ZuiFolders.isLauncher(ZuiFolders.lookup(((View) folder).getContext()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** The picker in the drawer's own window type, so it opens over the drawer, not under it. */
    private static void aboveTheDrawer(Dialog dialog) {
        try {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
        } catch (Throwable t) {
            L.w("zui folder add: picker left under the drawer (" + t + ")");
        }
    }

    /** In the taskbar's drawer, our folder's Add apps button opens ZUI's picker. */
    private static void wireInDrawer(Object folder) {
        if (!overDrawer(folder)) {
            return;
        }
        try {
            View button = addButtonOf((View) folder);
            Object launcher = ZuiFolders.launcher();
            if (button == null || launcher == null) {
                return;
            }
            button.setOnClickListener(v -> {
                try {
                    sShow.invoke(null, launcher, folder);
                } catch (Throwable t) {
                    L.w("zui folder add: ZUI's picker not opened (" + t + ")");
                }
            });
        } catch (Throwable t) {
            L.d("zui folder add: button not given the picker (" + t + ")");
        }
    }

    /** ZUI's Add apps button among a folder's icons ({@code isBigFolderAddAppButton}). */
    private static View addButtonOf(View view) {
        Object tag = view.getTag();
        if (tag != null && Boolean.TRUE.equals(Reflect.field(tag, "isBigFolderAddAppButton"))) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = addButtonOf(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * ZUI's OK, {@code (adapter, launcher, text, folderInfo, folderIcon, folder, view, button)}:
     * for our folder, the choice goes to our model by the drawer's own rules
     * ({@link DrawerArrange}), as a drop and "Remove from folder" go: an app picked leaves the
     * drawer's order or the other folder it was in, and a folder left with one app gives its
     * place to it; an app unpicked goes to the place after the folder. True when it was ours.
     */
    private static boolean picked(Object[] args) {
        try {
            Object folderInfo = args[3];
            Object id = Reflect.field(folderInfo, "id");
            if (!(id instanceof Integer) || (Integer) id > ZuiFolders.ID_BASE) {
                return false;
            }
            Item folder = ZuiFolderModel.folderOfId((Integer) id);
            Context ctx = AppCtx.get();
            if (folder == null || ctx == null) {
                // Ours, but not one we can save to: ZUI's OK would move apps to the home screen.
                close(args);
                return true;
            }
            List<Item> in = new ArrayList<>();
            List<Object> inZui = new ArrayList<>();
            List<Item> out = new ArrayList<>();
            for (Object choice : (List<?>) Reflect.call(args[0], "getSelectedItemInfos")) {
                Object status = Reflect.field(choice, "bigFolderStatus");
                Object item = Reflect.field(choice, "itemInfo");
                Item app = appOf(ctx, item);
                if (app == null || !(status instanceof Integer)) {
                    continue;
                }
                if ((Integer) status == ADD) {
                    in.add(app);
                    inZui.add(item);
                } else if ((Integer) status == DELETE) {
                    out.add(app);
                }
            }
            String folderId = folder.id;
            boolean[] stays = {true};
            // The store's own folder, as the change finds it.
            Item[] changedIn = {folder};
            NativeDrawerHooks.rearrange("Add apps: " + in.size() + " in, " + out.size()
                    + " out of " + folder.label, (order, folders) -> {
                        Item f = null;
                        for (Item candidate : folders) {
                            if (candidate.id.equals(folderId)) {
                                f = candidate;
                            }
                        }
                        if (f == null) {
                            return false;
                        }
                        changedIn[0] = f;
                        boolean changed = false;
                        for (Item app : in) {
                            changed |= DrawerArrange.fileInto(order, folders, f, app);
                        }
                        int at = order.indexOf(f.key());
                        int next = at < 0 ? order.size() : at + 1;
                        for (Item app : out) {
                            if (DrawerArrange.folderOf(folders, app.key()) == f
                                    && DrawerArrange.takeOut(order, folders, app.key(), next)) {
                                changed = true;
                                next++;
                            }
                        }
                        stays[0] = folders.contains(f);
                        if (stays[0]) {
                            // ZUI's open folder shows the change itself, below.
                            ZuiFolderModel.keepBuilt(f);
                        }
                        return changed;
                    });
            if (!stays[0]) {
                // One app left is not a folder: it took the folder's place in the drawer.
                close(args);
                ZuiFolderModel.closeOpenFolders();
                return true;
            }
            Object zuiFolder = args[5];
            Class<?> itemInfo = Class.forName("com.android.launcher3.model.data.ItemInfo", false,
                    zuiFolder.getClass().getClassLoader());
            int shown = 0;
            Item now = changedIn[0];
            for (int i = 0; i < in.size(); i++) {
                Item child = childIn(now, in.get(i));
                Object item = inZui.get(i);
                if (child == null) {
                    continue;
                }
                Object copy = item.getClass().getConstructor(item.getClass()).newInstance(item);
                setInt(copy, "container", (Integer) id);
                ZuiFolderModel.noteChild(copy, child);
                zuiFolder.getClass().getMethod("addFolderContent", itemInfo)
                        .invoke(zuiFolder, copy);
                shown++;
            }
            List<Object> removed = new ArrayList<>();
            for (Item app : out) {
                Object inFolder = childIn(now, app) == null ? matchIn(folderInfo, app) : null;
                if (inFolder != null) {
                    removed.add(inFolder);
                }
            }
            if (!removed.isEmpty()) {
                Object array = java.lang.reflect.Array.newInstance(itemInfo, removed.size());
                for (int i = 0; i < removed.size(); i++) {
                    java.lang.reflect.Array.set(array, i, removed.get(i));
                }
                zuiFolder.getClass().getMethod("removeFolderContent", boolean.class,
                        array.getClass()).invoke(zuiFolder, false, array);
            }
            close(args);
            L.i("zui folder add: " + shown + " in, " + removed.size() + " out of "
                    + folder.label + " - kept in our model");
            return true;
        } catch (Throwable t) {
            L.w("zui folder add: the choice not kept (" + t + ")");
            return false;
        }
    }

    /** Our folder's own entry for an app, now that the change is made; null when not in it. */
    private static Item childIn(Item folder, Item app) {
        for (Item child : folder.children) {
            if (child.key().equals(app.key())) {
                return child;
            }
        }
        return null;
    }

    /** ZUI's picker closes with its own motion, as its Cancel does. */
    private static void close(Object[] args) throws Exception {
        sClose.invoke(null, args[1], args[6], args[7]);
    }

    /** The app of ours one of ZUI's items stands for. */
    private static Item appOf(Context ctx, Object item) {
        Object component = item == null ? null : Reflect.call(item, "getTargetComponent");
        if (!(component instanceof ComponentName)) {
            return null;
        }
        ComponentName cn = (ComponentName) component;
        Object title = Reflect.field(item, "title");
        return Item.app(cn.getPackageName(), cn.getClassName(),
                IconInfo.serialOf(ctx, IconInfo.userOf(item)),
                title != null ? title.toString() : cn.getPackageName());
    }

    /** ZUI's item in the open folder for one of our apps. */
    private static Object matchIn(Object folderInfo, Item app) {
        Object contents = Reflect.call(folderInfo, "getContents");
        if (!(contents instanceof List)) {
            return null;
        }
        for (Object item : (List<?>) contents) {
            Item child = ZuiFolderModel.childOf(item);
            if (child != null && child.key().equals(app.key())) {
                return item;
            }
        }
        return null;
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException next) {
                // Up a level.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
