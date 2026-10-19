package com.zuxos.desktopplus.hook.drawer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.view.View;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.desktop.DesktopHost;
import com.zuxos.desktopplus.desktop.DragPayload;
import com.zuxos.desktopplus.desktop.FolderIconDrawable;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.IconPress;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.hook.ZuiMenu;
import com.zuxos.desktopplus.hook.taskbar.TaskbarApps;
import com.zuxos.desktopplus.hook.taskbar.TaskbarBridge;
import com.zuxos.desktopplus.hook.taskbar.TaskbarMenu;
import com.zuxos.desktopplus.logic.DrawerArrange;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.DrawerStore;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Brings the module's folders and custom order to the launcher's own app drawer - the one the
 * taskbar opens.
 *
 * <p>ZuxOS ships a minified Launcher3: the class names survive but every field and many methods
 * are renamed to single letters, so nothing here is looked up by name. The app list is found by
 * looking for the {@code List} whose elements are app entries, an app entry's fields are learned
 * from a real instance (see {@link Mirror}), and clicks are intercepted at {@code View} level,
 * which the obfuscator cannot touch.
 */
public final class NativeDrawerHooks {

    /** Package used on synthetic entries so we can recognise our own items again. */
    private static final String FOLDER_PKG = Const.MODULE_PKG;
    private static final String FOLDER_PREFIX = "folder.";

    private static boolean sInstalled;

    private static AppsRepo sRepo;

    private static Field sAppsField;
    private static Class<?> sAppsFieldOwner;
    private static Class<?> sAppInfoCls;
    private static Mirror.AppInfoShape sShape;
    private static List<Field> sLabelFields = Collections.emptyList();
    private static final Map<Class<?>, Field> sComponentFields = new HashMap<>();
    private static final Set<Class<?>> sWithoutComponent = new HashSet<>();
    private static final Map<String, Object> sFolderEntries = new HashMap<>();
    /** What each folder's entry was made with ({@link #contentOf}). */
    private static final Map<String, String> sFolderContent = new HashMap<>();
    private static final ThreadLocal<Boolean> sRefreshing = new ThreadLocal<>();
    private static String sLastNote;

    private NativeDrawerHooks() {
    }

    public static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        DrawerLetters.install(loader);
        DrawerAboveBar.install(loader);
        ZuiFolders.install(loader);

        Class<?> listCls = Reflect.findClass(
                "com.android.launcher3.allapps.AlphabeticalAppsList", loader);
        if (listCls == null) {
            L.w("native drawer: AlphabeticalAppsList not found - the stock drawer is left alone");
            return;
        }

        int hooked = hookUpTheHierarchy(listCls, "updateAdapterItems", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                customise(param.thisObject);
                ZuiFolders.building(param.thisObject);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ZuiFolders.built();
            }
        });
        int hookedUpdates = hookUpTheHierarchy(listCls, "onAppsUpdated", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(sRefreshing.get()) || !customise(param.thisObject)) {
                    return;
                }
                sRefreshing.set(Boolean.TRUE);
                try {
                    Reflect.call(param.thisObject, "updateAdapterItems");
                } finally {
                    sRefreshing.set(Boolean.FALSE);
                }
            }
        });
        Health.hooked("drawer: our order and folders (updateAdapterItems)", hooked,
                Cfg::nativeDrawer);
        Health.hooked("drawer: app list updates (onAppsUpdated)", hookedUpdates);
        L.i("native drawer: " + listCls.getName() + " - updateAdapterItems x" + hooked
                + ", onAppsUpdated x" + hookedUpdates);
        if (hooked == 0 && hookedUpdates == 0) {
            L.w("native drawer: no entry point on this build");
            dumpCandidates(listCls);
            return;
        }

        // Clicks are intercepted on View itself: ItemClickHandler's methods are minified away,
        // but every click still goes through performClick, and the check below is just a tag test.
        try {
            int clicks = XposedBridge.hookAllMethods(View.class, "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View
                                    && DrawerSelect.tapped((View) param.thisObject)) {
                                // Chosen or let go while choosing: no launch.
                                param.setResult(Boolean.TRUE);
                                IconPress.release((View) param.thisObject);
                            } else if (param.thisObject instanceof View
                                    && openFolderFor((View) param.thisObject)) {
                                param.setResult(Boolean.TRUE);
                                // ZUI's click handler is skipped: its press let go here.
                                IconPress.release((View) param.thisObject);
                            } else if (param.thisObject instanceof View
                                    && bringIfOpen((View) param.thisObject)) {
                                // ZUI's launch skipped: the app's window came forward instead.
                                // The drawer still closes, after this, as for any launch.
                                param.setResult(Boolean.TRUE);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View
                                    && !DrawerSelect.consumed((View) param.thisObject)) {
                                closeAfterLaunch((View) param.thisObject);
                            }
                        }
                    }).size();
            Health.hooked("drawer: taps on folders", clicks);
            L.i("native drawer: click interception installed x" + clicks);
        } catch (Throwable t) {
            L.e("native drawer: could not intercept clicks", t);
        }

        // And the long press, for the same reason and in the same place. Without this the
        // launcher opens its own popup for a folder, reads the package off the icon - ours, since
        // that is how a synthetic entry is recognised - and offers app info for this module.
        try {
            int holds = XposedBridge.hookAllMethods(View.class, "performLongClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) {
                                return;
                            }
                            View view = (View) param.thisObject;
                            if (folderMenu(view) || dragOut(view)) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    }).size();
            Health.hooked("drawer: holds on folders", holds, Cfg::drawerDrag);
            L.i("native drawer: folder long-press interception installed x" + holds);
        } catch (Throwable t) {
            L.e("native drawer: could not intercept long presses", t);
        }
        hookAppInfo(loader);
    }

    /**
     * App info chosen in a menu of the taskbar's drawer: the drawer goes as Settings opens.
     *
     * <p>ZUI's {@code SystemShortcut.AppInfo.onClick} sends ZUX Home's own drawer back to the
     * home screen ({@code returnToHomescreen}), and leaves the taskbar's drawer, a window above
     * every app, open in front of Settings (owner on 0.0.282, 23:19:15). Its menu there has App
     * info because we hand ZUI's own to it ({@code ZuiAppRows}). It is closed as after a launch
     * from it ({@link #closeTaskbarDrawer}), once Settings is on its way.
     */
    private static void hookAppInfo(ClassLoader loader) {
        try {
            Class<?> info = Reflect.findClass("com.android.launcher3.popup.SystemShortcut$AppInfo",
                    loader);
            if (info == null) {
                L.w("native drawer: ZUI's App info not found - the taskbar's drawer stays open"
                        + " over Settings");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(info, "onClick", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && param.args[0] instanceof View) {
                        closeAfterAppInfo(param.thisObject, (View) param.args[0]);
                    }
                }
            }).size();
            Health.hooked("drawer: App info closes the taskbar's drawer", hooked);
        } catch (Throwable t) {
            L.w("native drawer: App info not watched (" + t + ")");
        }
    }

    private static void closeAfterAppInfo(Object shortcut, View row) {
        try {
            Object target = Reflect.field(shortcut, "mTarget");
            if (target == null || row.getDisplay() == null
                    || !target.getClass().getName().endsWith(".TaskbarOverlayContext")) {
                // ZUX Home's own drawer, which ZUI sends home itself, or the bar's menus.
                return;
            }
            int display = row.getDisplay().getDisplayId();
            row.getRootView().postDelayed(() -> closeTaskbarDrawer(target, display),
                    CLOSE_AFTER_LAUNCH_MS);
            if (SAID_CLOSE.add("app info")) {
                L.i("native drawer: App info chosen in the taskbar's drawer - the drawer goes");
            }
        } catch (Throwable t) {
            L.d("native drawer: the taskbar's drawer left open after App info (" + t + ")");
        }
    }

    private static int hookUpTheHierarchy(Class<?> clazz, String name, XC_MethodHook callback) {
        int count = 0;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                count += XposedBridge.hookAllMethods(c, name, callback).size();
            } catch (Throwable t) {
                L.d("native drawer: hooking " + c.getName() + "." + name + " failed: " + t);
            }
        }
        return count;
    }

    private static void dumpCandidates(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            StringBuilder sb = new StringBuilder("  " + c.getName() + ":");
            try {
                for (Method m : c.getDeclaredMethods()) {
                    sb.append(' ').append(m.getName()).append('/').append(m.getParameterCount());
                }
            } catch (Throwable t) {
                sb.append(" <unreadable>");
            }
            L.i(sb.toString());
        }
    }

    // --- list rewriting --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static boolean customise(Object appsList) {
        try {
            if (!Cfg.enabled() || !Cfg.nativeDrawer()) {
                return false;
            }
            Context ctx = AppCtx.get();
            if (ctx == null) {
                note("no context yet - open the desktop once so the module can find one");
                return false;
            }
            List<Object> apps = appsListOf(appsList);
            if (apps == null || apps.isEmpty()) {
                return false;
            }
            sLastList = new java.lang.ref.WeakReference<>(appsList);
            synchronized (LISTS) {
                LISTS.put(appsList, Boolean.TRUE);
            }
            DrawerStore store = store(ctx);
            Set<String> filed = store.keysInFolders();
            Map<String, Object> inStore = storeAppsByKey(ctx, appsList, filed);
            if (inStore != null && forgetUninstalled(ctx, store, filed, inStore.keySet())) {
                filed = store.keysInFolders();
            }
            Set<String> hidden = store.hidden();
            if (filed.isEmpty() && hidden.isEmpty() && store.order().isEmpty()
                    && store.folders().isEmpty()) {
                note("nothing to apply yet: make a folder in the module's drawer first");
                return false;
            }
            if (!learnShape(ctx, apps)) {
                return false;
            }

            List<Object> kept = new ArrayList<>(apps.size());
            int iconPx = 0;
            for (Object app : apps) {
                if (folderIdOf(app) != null) {
                    // Left over from an earlier pass - rebuilt below.
                    continue;
                }
                if (iconPx == 0) {
                    iconPx = iconSizeOf(app);
                }
                String key = keyOf(ctx, app);
                if (key != null && (hidden.contains(key) || filed.contains(key))) {
                    continue;
                }
                kept.add(app);
            }
            ZuiFolders.remember(inStore);
            if (kept.isEmpty()) {
                note("refusing to empty the drawer - no entries survived filtering");
                return false;
            }

            Object sample = kept.get(0);
            for (Item folder : store.folders()) {
                Object entry = folderEntry(ctx, folder, sample, iconPx);
                if (entry != null) {
                    kept.add(entry);
                }
            }

            sortEntries(ctx, kept, store);
            apps.clear();
            apps.addAll(kept);
            note("applied: " + kept.size() + " entries, " + store.folders().size() + " folder(s), "
                    + hidden.size() + " hidden, order " + store.order().size());
            return true;
        } catch (Throwable t) {
            L.e("native drawer: rewriting the app list failed", t);
            return false;
        }
    }

    /**
     * Apps uninstalled while in our folders leave them: a folder left with one app gives its place
     * to it, one left with none goes ({@link DrawerArrange#forget}). Kept, the folder showed fewer
     * apps than it held - one, which ZUI will not open, or none, an empty cell. Only apps of the
     * tablet's own user that ZUI's app store does not have are asked about, and only the package
     * manager's word that the package is not installed counts: one missing for a moment stays.
     */
    private static boolean forgetUninstalled(Context ctx, DrawerStore store, Set<String> filed,
            Set<String> inStore) {
        Set<String> gone = new java.util.HashSet<>();
        android.content.pm.PackageManager pm = null;
        for (String key : filed) {
            Item app = inStore.contains(key) || !key.endsWith("#0") ? null
                    : DrawerArrange.appOf(key, null);
            if (app == null) {
                continue;
            }
            if (pm == null) {
                pm = ctx.getPackageManager();
            }
            try {
                pm.getApplicationInfo(app.pkg, 0);
            } catch (android.content.pm.PackageManager.NameNotFoundException uninstalled) {
                gone.add(key);
            }
        }
        if (gone.isEmpty()) {
            return false;
        }
        int removed = DrawerArrange.forget(store.order(), store.folders(), gone);
        store.save();
        L.i("native drawer: " + removed + " uninstalled app(s) taken out of the folders");
        return removed > 0;
    }

    /**
     * The installed apps filed in our folders, by key, from ZUI's own app store
     * ({@code AllAppsStore.getApps()}): the list rewritten here has them taken out after its first
     * pass, so it cannot be where ZUI's folders of them are made from (1.0.190: every folder
     * empty). Null where the store is not found.
     */
    private static Map<String, Object> storeAppsByKey(Context ctx, Object appsList,
            Set<String> filed) {
        try {
            Object store = null;
            for (Field f : Mirror.fields(appsList.getClass())) {
                if (f.getType().getName().endsWith(".AllAppsStore")) {
                    store = Mirror.get(f, appsList);
                    break;
                }
            }
            Object apps = store == null ? null : Reflect.call(store, "getApps");
            if (!(apps instanceof Object[])) {
                note("ZUI's app store not found - folders keep the apps last seen");
                return null;
            }
            Map<String, Object> byKey = new HashMap<>();
            for (Object app : (Object[]) apps) {
                String key = app == null ? null : keyOf(ctx, app);
                if (key != null && filed.contains(key)) {
                    byKey.put(key, app);
                }
            }
            return byKey;
        } catch (Throwable t) {
            L.d("native drawer: ZUI's app store not read (" + t + ")");
            return null;
        }
    }

    /** The launcher's app list, found by element type because its field name is minified. */
    @SuppressWarnings("unchecked")
    private static List<Object> appsListOf(Object appsList) {
        if (sAppsField != null && sAppsFieldOwner == appsList.getClass()) {
            Object value = Mirror.get(sAppsField, appsList);
            return value instanceof List ? (List<Object>) value : null;
        }
        // The element type is whatever the launcher puts in there; identify it by finding the
        // list whose entries carry a ComponentName, which app entries always do.
        for (Field f : Mirror.fields(appsList.getClass())) {
            if (!List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            Object value = Mirror.get(f, appsList);
            if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
                continue;
            }
            Object first = ((List<?>) value).get(0);
            if (first == null || componentOf(first) == null) {
                continue;
            }
            sAppsField = f;
            sAppsFieldOwner = appsList.getClass();
            sAppInfoCls = first.getClass();
            L.i("native drawer: app list is field '" + f.getName() + "' holding "
                    + sAppInfoCls.getName());
            return (List<Object>) value;
        }
        note("no app list found on " + appsList.getClass().getName());
        return null;
    }

    /** Learns which fields of an app entry hold the component, label, icon and user. */
    private static boolean learnShape(Context ctx, List<Object> apps) {
        if (sShape != null && sShape.usable()) {
            return true;
        }
        sShape = Mirror.learnAppInfo(apps, entry -> {
            ComponentName cn = componentOf(entry);
            return cn != null ? labelFor(ctx, cn) : null;
        });
        if (sShape.usable()) {
            L.i("native drawer: app entry shape - " + sShape);
            sLabelFields = Mirror.labelFields(apps.get(0).getClass());
            return true;
        }
        note("could not work out the shape of an app entry");
        return false;
    }

    private static CharSequence labelFor(Context ctx, ComponentName cn) {
        for (AppsRepo.AppEntry e : repo(ctx).apps()) {
            if (e.cn.equals(cn)) {
                return e.label;
            }
        }
        return null;
    }

    private static void sortEntries(Context ctx, List<Object> entries, DrawerStore store) {
        final Collator collator = Collator.getInstance();
        final List<String> order = store.order();

        // Both keys are reflective and positions are a linear scan, so resolve each entry once
        // rather than on every comparison - this list is ~150 long and sorted on every refresh.
        final Map<Object, String> titles = new IdentityHashMap<>(entries.size());
        for (Object entry : entries) {
            titles.put(entry, titleOf(entry));
        }
        if (order.isEmpty()) {
            // No custom order: keep it alphabetical so the A-Z fast scroller stays honest.
            Collections.sort(entries, (a, b) -> collator.compare(titles.get(a), titles.get(b)));
            return;
        }
        final Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            positions.putIfAbsent(order.get(i), i);
        }
        final Map<Object, Integer> ranks = new IdentityHashMap<>(entries.size());
        for (Object entry : entries) {
            ranks.put(entry, positions.getOrDefault(entryKey(ctx, entry), -1));
        }
        Collections.sort(entries, (a, b) -> {
            int ia = ranks.get(a);
            int ib = ranks.get(b);
            if (ia < 0 && ib < 0) {
                return collator.compare(titles.get(a), titles.get(b));
            }
            if (ia < 0) {
                return 1;
            }
            if (ib < 0) {
                return -1;
            }
            return Integer.compare(ia, ib);
        });
    }

    /** The drawer's app list as last seen, so a rearrangement can have it re-sorted. */
    private static java.lang.ref.WeakReference<Object> sLastList;
    /** Every drawer list ours is applied to - ZUX Home's own and the taskbar's - to refresh. */
    private static final Map<Object, Boolean> LISTS = new java.util.WeakHashMap<>();

    /** What an icon in the stock drawer stands for, as a key of the drawer's order; or null. */
    static String keyOfIcon(View icon) {
        Context ctx = AppCtx.get();
        Object tag = icon == null ? null : icon.getTag();
        if (ctx == null || tag == null || sAppInfoCls == null || !sAppInfoCls.isInstance(tag)) {
            return null;
        }
        String key = entryKey(ctx, tag);
        return key.isEmpty() ? null : key;
    }

    /** Every entry of the stock drawer, by key, in the order it is showing them. */
    static List<String> shownOrder() {
        Context ctx = AppCtx.get();
        Object list = sLastList != null ? sLastList.get() : null;
        List<Object> apps = list == null ? null : appsListOf(list);
        List<String> keys = new ArrayList<>();
        if (ctx == null || apps == null) {
            return keys;
        }
        for (Object app : apps) {
            String key = entryKey(ctx, app);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Moves one entry of the stock drawer to {@code index} of what it shows, keeps that order -
     * the same order the module's own drawer uses - and has the drawer show it.
     *
     * @return false when the drawer's list is not at hand and nothing was changed
     */
    static boolean moveInDrawer(String key, int index) {
        return rearrange("moved " + key + " to " + index,
                (order, folders) -> DrawerArrange.move(order, key, index));
    }

    /**
     * Rearranges the stock drawer: {@code change} gets what the drawer shows, by key and in
     * order, and our folders, and changes them in place ({@link DrawerArrange}); the result is
     * kept - the same order the module's own drawer uses - and shown in every drawer.
     *
     * @return false when the drawer's list is not at hand or nothing changed
     */
    static boolean rearrange(String what,
            java.util.function.BiPredicate<List<String>, List<Item>> change) {
        Context ctx = AppCtx.get();
        List<String> keys = shownOrder();
        if (ctx == null || keys.isEmpty()) {
            return false;
        }
        DrawerStore store = store(ctx);
        if (!change.test(keys, store.folders())) {
            return false;
        }
        store.order().clear();
        store.order().addAll(keys);
        store.save();
        refreshList();
        L.i("native drawer: " + what);
        return true;
    }

    /** The key of one of ZUI's drawer entries, an app or a folder of ours; null for neither. */
    static String keyOfEntry(Object entry) {
        Context ctx = AppCtx.get();
        String key = ctx == null || entry == null ? "" : entryKey(ctx, entry);
        return key.isEmpty() ? null : key;
    }

    /** The name the drawer shows for an app key, or the name it has in one of our folders. */
    static String labelOf(String key) {
        Context ctx = AppCtx.get();
        Object list = sLastList != null ? sLastList.get() : null;
        List<Object> apps = list == null ? null : appsListOf(list);
        if (ctx == null) {
            return null;
        }
        for (Object app : apps != null ? apps : Collections.emptyList()) {
            if (key.equals(entryKey(ctx, app))) {
                return titleOf(app);
            }
        }
        Item folder = DrawerArrange.folderOf(store(ctx).folders(), key);
        if (folder != null) {
            for (Item child : folder.children) {
                if (child.key().equals(key)) {
                    return child.label;
                }
            }
        }
        return null;
    }

    private static String entryKey(Context ctx, Object entry) {
        String folderId = folderIdOf(entry);
        if (folderId != null) {
            return "folder:" + folderId;
        }
        String key = keyOf(ctx, entry);
        return key != null ? key : "";
    }

    // --- synthetic folder entries ----------------------------------------

    private static Object folderEntry(Context ctx, Item folder, Object sample, int iconPx) {
        String label = folder.label != null ? folder.label : "Folder";
        Object cached = sFolderEntries.get(folder.id);
        String content = contentOf(folder);
        if (cached != null && label.equals(titleOf(cached))
                && content.equals(sFolderContent.get(folder.id))) {
            return cached;
        }
        try {
            Object info = Mirror.instantiateLike(sample);
            if (info == null) {
                return null;
            }
            ComponentName cn = new ComponentName(FOLDER_PKG, FOLDER_PREFIX + folder.id);
            Mirror.set(sShape.component, info, cn);
            Mirror.set(sShape.title, info, label);
            // The entry is a copy of a real app, so any label field left holding that app's name
            // would show through. Overwrite every one of them.
            for (Field f : sLabelFields) {
                Mirror.set(f, info, label);
            }
            String shown = titleOf(info);
            if (!label.equals(shown)) {
                L.w("native drawer: folder label did not take (shows '" + shown + "', wanted '"
                        + label + "') - title field was " + sShape);
            }
            if (sShape.user != null) {
                Mirror.set(sShape.user, info, android.os.Process.myUserHandle());
            }
            // Pointed at the folder, not at the app this entry was copied from, and not left
            // null: the launcher reads this field, and a null there would be its crash.
            if (sShape.intent != null) {
                Mirror.set(sShape.intent, info, new Intent(Intent.ACTION_MAIN).setComponent(cn));
            }
            applyFolderIcon(ctx, info, folder, iconPx > 0 ? iconPx : Ui.dp(ctx, 48));
            sFolderEntries.put(folder.id, info);
            sFolderContent.put(folder.id, content);
            return info;
        } catch (Throwable t) {
            L.e("native drawer: could not build a folder entry", t);
            return null;
        }
    }

    private static void applyFolderIcon(Context ctx, Object info, Item folder, int sizePx) {
        if (sShape.bitmap == null) {
            return;
        }
        try {
            List<Drawable> previews = new ArrayList<>();
            AppsRepo repo = repo(ctx);
            for (Item child : folder.children) {
                Drawable d = repo.iconFor(child);
                if (d != null) {
                    previews.add(d);
                }
                if (previews.size() == 4) {
                    break;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            // One picture in ZUI's model, shown on the tablet and the monitor alike: Glass.
            FolderIconDrawable icon = new FolderIconDrawable(ctx, previews, sizePx,
                    Theme.GLASS);
            icon.setBounds(0, 0, sizePx, sizePx);
            icon.draw(canvas);

            Object bitmapInfo = makeBitmapInfo(sShape.bitmap.getType(), bitmap);
            if (bitmapInfo != null) {
                Mirror.set(sShape.bitmap, info, bitmapInfo);
            }
        } catch (Throwable t) {
            L.d("native drawer: folder icon fell back to the default: " + t);
        }
    }

    /** BitmapInfo's factory is minified too, so it is matched by signature. */
    private static Object makeBitmapInfo(Class<?> bitmapInfoCls, Bitmap bitmap) {
        for (Method m : bitmapInfoCls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Bitmap.class
                    && bitmapInfoCls.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    return m.invoke(null, bitmap);
                } catch (Throwable ignored) {
                    // Try the next candidate.
                }
            }
        }
        for (Constructor<?> c : bitmapInfoCls.getDeclaredConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            if (types.length >= 1 && types[0] == Bitmap.class) {
                try {
                    c.setAccessible(true);
                    Object[] args = new Object[types.length];
                    args[0] = bitmap;
                    for (int i = 1; i < types.length; i++) {
                        args[i] = types[i] == int.class ? Integer.valueOf(0) : null;
                    }
                    return c.newInstance(args);
                } catch (Throwable ignored) {
                    // Try the next candidate.
                }
            }
        }
        L.d("native drawer: no way to build a BitmapInfo - folder keeps a default icon");
        return null;
    }

    static boolean openFolderFor(View view) {
        if (sFolderEntries.isEmpty()) {
            // No synthetic entries exist, so no view can be carrying one.
            return false;
        }
        try {
            Object tag = view.getTag();
            if (tag == null) {
                return false;
            }
            String folderId = folderIdOf(tag);
            if (folderId == null) {
                return false;
            }
            Context ctx = view.getContext();
            Context storeCtx = AppCtx.get() != null ? AppCtx.get() : ctx;
            final DrawerStore drawerStore = store(storeCtx);
            for (Item folder : drawerStore.folders()) {
                if (folder.id.equals(folderId)) {
                    int displayId = view.getDisplay() != null ? view.getDisplay().getDisplayId() : 0;
                    DrawerFolderWindow.show(ctx, folder, repo(ctx), displayId,
                            Ui.dp(ctx, Cfg.iconSizeDp()), drawerStore, view);
                    return true;
                }
            }
        } catch (Throwable t) {
            L.e("native drawer: opening a folder failed", t);
        }
        return false;
    }

    /**
     * The folder behind one of our synthetic entries, with everything in it.
     *
     * <p>Holding a folder used to open a menu, which is why folders were the one thing in the
     * drawer that could not be dragged out of it. It drags now, like everything else there: one
     * gesture, one meaning. Breaking a folder up stays in the module's own drawer, where the
     * folder was made - what the stock drawer was missing was a way to get the folder out.
     */
    private static Item folderBehind(View view) {
        if (sFolderEntries.isEmpty()) {
            return null;
        }
        Item folder = folderOfEntry(view.getTag());
        return folder != null ? folder : ZuiFolders.folderOfIcon(view);
    }

    /** Our folder behind one of our entries in ZUI's list, or null for anything else. */
    static Item folderOfEntry(Object entry) {
        String folderId = entry == null || sFolderEntries.isEmpty() ? null : folderIdOf(entry);
        Context ctx = AppCtx.get();
        if (folderId == null || ctx == null) {
            return null;
        }
        for (Item folder : store(ctx).folders()) {
            if (folder.id.equals(folderId)) {
                return folder;
            }
        }
        return null;
    }

    /** Has ZUI's drawer list built again from our model, as after any change to it. */
    static void refreshList() {
        List<Object> lists;
        synchronized (LISTS) {
            lists = new ArrayList<>(LISTS.keySet());
        }
        // ZUI's own refresh: onAppsUpdated fills the list again from ZUI's app store and lays it
        // out (updateAdapterItems, where ours is applied). Laid out alone, the list was the one
        // our last pass left - without the apps that were in folders, so an app taken out of one
        // stayed missing until ZUX Home restarted. Our after-hook is held off for it: one pass.
        sRefreshing.set(Boolean.TRUE);
        try {
            for (Object list : lists) {
                try {
                    Reflect.call(list, "onAppsUpdated");
                } catch (Throwable t) {
                    L.d("native drawer: a list not refreshed (" + t + ")");
                }
            }
        } finally {
            sRefreshing.set(Boolean.FALSE);
        }
    }

    /**
     * An app tapped in a drawer: the drawer goes once the launch is under way, closed the way
     * ZUI closes it.
     *
     * <p>ZUI closes it only for launches it runs itself, and on the desktop displays the launch
     * goes elsewhere - so the drawer stayed open with the app starting behind it. Apps inside our
     * folders were left out (their entries are ZUI's workspace items, not drawer entries), and so
     * was ZUX Home's own drawer (22:11). Closed a moment later rather than at once, so the launch
     * animation still starts from the icon: the taskbar's drawer as ZUI hides it
     * ({@code TaskbarOverlayContext.hideAllAppsOverlay}), ZUX Home's by its state manager back
     * to {@code NORMAL}, as ZUI does in desktop mode.
     */
    private static void closeAfterLaunch(View view) {
        Object tag = view.getTag();
        // A tag test first: this runs for every click in the launcher.
        if (tag == null || view.getDisplay() == null) {
            return;
        }
        boolean entry = sAppInfoCls != null && sAppInfoCls.isInstance(tag)
                && folderIdOf(tag) == null && IconInfo.packageOf(tag) != null;
        if (!entry && ZuiFolders.childOf(tag) == null) {
            return;
        }
        int display = view.getDisplay().getDisplayId();
        Object activity;
        try {
            activity = ZuiFolders.lookup(view.getContext());
        } catch (Throwable t) {
            activity = null;
        }
        final Object where = activity;
        if (ZuiFolders.isLauncher(where)) {
            view.postDelayed(() -> closeHomeDrawer(where), CLOSE_AFTER_LAUNCH_MS);
        } else if (inStockDrawer(view)) {
            view.getRootView().postDelayed(() -> closeTaskbarDrawer(where, display),
                    CLOSE_AFTER_LAUNCH_MS);
        }
    }

    private static final java.util.Set<String> SAID_CLOSE = new java.util.HashSet<>();

    /** The taskbar's drawer hidden as ZUI hides it; our own close where that is not there. */
    private static void closeTaskbarDrawer(Object overlay, int display) {
        String how;
        try {
            overlay.getClass().getMethod("hideAllAppsOverlay").invoke(overlay);
            how = "ZUI's hideAllAppsOverlay";
        } catch (Throwable t) {
            how = TaskbarBridge.closeStockDrawer(display) ? "its sheet's close" : "nothing ("
                    + t + ")";
        }
        if (SAID_CLOSE.add("taskbar " + how)) {
            L.i("native drawer: the taskbar drawer closed after a launch, through " + how);
        }
    }

    /** ZUX Home's own drawer back to its home, as ZUI does after a launch in desktop mode. */
    private static void closeHomeDrawer(Object launcher) {
        try {
            if (!Boolean.TRUE.equals(launcher.getClass().getMethod("isInAllAppsState")
                    .invoke(launcher))) {
                return;
            }
            Object normal = Class.forName("com.android.launcher3.LauncherState", false,
                    launcher.getClass().getClassLoader()).getField("NORMAL").get(null);
            Object states = launcher.getClass().getMethod("getStateManager").invoke(launcher);
            for (java.lang.reflect.Method m : states.getClass().getMethods()) {
                if (m.getName().equals("goToState") && m.getParameterCount() == 1) {
                    m.invoke(states, normal);
                    if (SAID_CLOSE.add("home")) {
                        L.i("native drawer: ZUX Home's drawer closed after a launch, through "
                                + "its state manager");
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            L.d("native drawer: ZUX Home's drawer not closed after a launch (" + t + ")");
        }
    }

    private static final long CLOSE_AFTER_LAUNCH_MS = 220L;

    /**
     * An app tapped in the stock drawer that is already open on this screen: its window comes
     * forward instead of ZUI's launch, which in desktop mode opens another window of it. Only on
     * the monitor's desktop; the tablet behaves as ZUI made it.
     */
    private static boolean bringIfOpen(View view) {
        Object tag = view.getTag();
        // A tag test first: this runs for every click in the launcher.
        if (tag == null || sAppInfoCls == null || !sAppInfoCls.isInstance(tag)
                || folderIdOf(tag) != null || view.getDisplay() == null) {
            return false;
        }
        String pkg = IconInfo.packageOf(tag);
        int display = view.getDisplay().getDisplayId();
        return pkg != null && DesktopHost.isExternalOn(display) && inStockDrawer(view)
                && TaskbarApps.bringIfOpen(view.getContext(), pkg, display);
    }

    /**
     * Hold anything in the stock drawer and it comes out with your finger.
     *
     * <p>Claimed at {@code performLongClick}, which is hooked on {@code View} itself and therefore
     * fires for every view in the launcher - the desktop's own icons and the taskbar's among them,
     * both of which have gestures of their own. So this is deliberately narrow: an entry we can
     * read, inside the stock drawer's window and nowhere else.
     *
     * <p>The drag is global and carries its payload on the clip, because it has to cross from the
     * drawer's window into the launcher's activity, where the desktop is - and a local state
     * object does not survive that trip.
     */
    private static boolean dragOut(View view) {
        if (!Cfg.enabled() || !Cfg.drawerDrag()) {
            return false;
        }
        try {
            if (view.getClass().getName().startsWith("com.zuxos")) {
                // One of ours. Our own views arrange their own drags.
                return false;
            }
            if (!inStockDrawer(view)) {
                return false;
            }
            // Only where our desktop is. On the tablet the drawer drops onto ZUI's own home,
            // which knows nothing of our drag, so taking the long press there broke ZUI's.
            if (view.getDisplay() == null
                    || !DesktopHost.isOnDisplay(view.getDisplay().getDisplayId())) {
                return false;
            }
            Object tag = view.getTag();
            // A folder first, because a folder has no package and would otherwise be refused -
            // which is why folders were the one thing in the drawer that would not come out.
            Item item = folderBehind(view);
            if (item == null) {
                String pkg = IconInfo.packageOf(tag);
                item = pkg == null ? null : IconInfo.itemOf(view.getContext(), tag);
            }
            if (item == null) {
                return false;
            }
            DragPayload payload = new DragPayload(item, DragPayload.SRC_DRAWER, null);
            // Watched before it starts, so its first move is heard: the watcher decides between
            // a drag (closes the drawer once it moves) and a hold (menu when it is let go).
            DrawerHold.watch(view, item);
            boolean started = view.startDragAndDrop(payload.toClip(),
                    new View.DragShadowBuilder(view), payload, DragPayload.FLAGS);
            if (started) {
                // ZUI's own drag lets go of the press as it starts (prepareDrawDragView); ours too.
                IconPress.release(view);
            }
            L.i("native drawer: drag of " + item.label + " on display "
                    + view.getDisplay().getDisplayId() + (started ? " started" : " refused"));
            return started;
        } catch (Throwable t) {
            L.d("native drawer: could not start a drag (" + t + ")");
            return false;
        }
    }

    /**
     * A hold on one of our folders in a drawer on the tablet - ZUI's taskbar drawer or ZUX Home's
     * own: ZUI's menu with our folder's actions, rather than ZUI's app menu, which took the folder
     * for an app (app info for this module, "App isn't installed"). The monitor keeps its drag.
     */
    private static boolean folderMenu(View view) {
        try {
            if (!Cfg.enabled() || view.getDisplay() == null
                    || view.getDisplay().getDisplayId() != android.view.Display.DEFAULT_DISPLAY
                    || !inAppsList(view)) {
                return false;
            }
            Item folder = folderBehind(view);
            if (folder == null) {
                return false;
            }
            List<TaskbarMenu.Entry> menu = DrawerHold.menuEntries(view, folder);
            // In ZUX Home's own drawer ZUI's menu for the folder comes first and the folder
            // drags to the home screen once the finger moves, as ZUI's drawer apps do.
            if (ZuiFolderHome.hold(view, folder)) {
                return true;
            }
            if (!ZuiMenu.show(view, menu)) {
                return false;
            }
            // In the taskbar's drawer the menu gives way to ZUI's drag of the folder once the
            // finger moves, as for the drawer's apps; else as ZUI does after a popup on a hold
            // without a drag (skipHotseatDrag).
            if (!TaskbarFolderDrag.start(view, folder)) {
                IconPress.release(view);
            }
            return true;
        } catch (Throwable t) {
            L.d("native drawer: no folder menu (" + t + ")");
            return false;
        }
    }

    /** Whether a view is an entry of ZUI's app list, in either of its drawers. */
    private static boolean inAppsList(View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().endsWith("AllAppsRecyclerView")) {
                return true;
            }
        }
        return false;
    }

    /** Whether a view is inside the drawer's own window rather than some other one. */
    private static boolean inStockDrawer(View view) {
        return view.isAttachedToWindow() && TaskbarBridge.isAllAppsWindow(view.getRootView());
    }

    /** True for one of the synthetic entries this class puts in the drawer for a folder. */
    public static boolean isFolderEntry(Object entry) {
        return !sFolderEntries.isEmpty() && folderIdOf(entry) != null;
    }

    // --- small helpers ---------------------------------------------------

    /** The ComponentName an app entry carries, located by type and cached per class. */
    public static ComponentName componentOf(Object entry) {
        if (entry == null) {
            return null;
        }
        Class<?> cls = entry.getClass();
        if (sWithoutComponent.contains(cls)) {
            return null;
        }
        Field f = sComponentFields.get(cls);
        if (f == null) {
            for (Field candidate : Mirror.fields(cls)) {
                if (ComponentName.class.isAssignableFrom(candidate.getType())) {
                    f = candidate;
                    sComponentFields.put(cls, f);
                    break;
                }
            }
            if (f == null) {
                // Remember the miss: this runs on every click in the launcher.
                sWithoutComponent.add(cls);
                return null;
            }
        }
        Object value = Mirror.get(f, entry);
        return value instanceof ComponentName ? (ComponentName) value : null;
    }

    static String folderIdOf(Object entry) {
        ComponentName component = componentOf(entry);
        if (component == null || !FOLDER_PKG.equals(component.getPackageName())
                || !component.getClassName().startsWith(FOLDER_PREFIX)) {
            return null;
        }
        return component.getClassName().substring(FOLDER_PREFIX.length());
    }

    private static String titleOf(Object entry) {
        if (sShape == null || sShape.title == null) {
            return "";
        }
        Object title = Mirror.get(sShape.title, entry);
        return title != null ? title.toString() : "";
    }

    private static String keyOf(Context ctx, Object entry) {
        ComponentName component = componentOf(entry);
        if (component == null) {
            return null;
        }
        long serial = 0;
        if (sShape != null && sShape.user != null) {
            Object user = Mirror.get(sShape.user, entry);
            if (user instanceof UserHandle) {
                serial = IconInfo.serialOf(ctx, (UserHandle) user);
            }
        }
        return component.getPackageName() + "/" + component.getClassName() + "#" + serial;
    }

    private static int iconSizeOf(Object entry) {
        try {
            if (sShape == null || sShape.bitmap == null) {
                return 0;
            }
            Object bitmapInfo = Mirror.get(sShape.bitmap, entry);
            if (bitmapInfo == null) {
                return 0;
            }
            Field iconField = Mirror.fieldOfType(bitmapInfo.getClass(), Bitmap.class);
            Object icon = iconField != null ? Mirror.get(iconField, bitmapInfo) : null;
            if (icon instanceof Bitmap) {
                return ((Bitmap) icon).getWidth();
            }
        } catch (Throwable ignored) {
            // Any sensible default will do.
        }
        return 0;
    }

    /** Logs a state message once, so a per-frame hook cannot flood the log. */
    private static void note(String message) {
        if (!message.equals(sLastNote)) {
            sLastNote = message;
            L.i("native drawer: " + message);
        }
    }

    /**
     * The device's icon shape or icon theme changed: every icon made from the old ones is made
     * again - the apps' icons and the folder entries painted from them.
     */
    public static synchronized void forgetIcons() {
        if (sRepo != null) {
            sRepo.forgetIcons();
        }
        sFolderEntries.clear();
        sFolderContent.clear();
    }

    /**
     * The drawer state. A folder's entry is made again only when its name or apps change
     * ({@link #contentOf}), not on every save: each save of a rearrange drew every folder's
     * icon again on the main thread.
     */
    private static DrawerStore store(Context ctx) {
        return DrawerStore.shared(ctx);
    }

    /** A folder's name and apps, in order: what its entry's icon shows. */
    private static String contentOf(Item folder) {
        StringBuilder out = new StringBuilder(folder.label != null ? folder.label : "");
        for (Item child : folder.children) {
            out.append('|').append(child.key());
        }
        return out.toString();
    }

    /** The app repository, built once. Shared with the taskbar, which opens folders too. */
    public static synchronized AppsRepo repo(Context ctx) {
        if (sRepo == null) {
            sRepo = new AppsRepo(ctx);
            sRepo.reload();
        }
        return sRepo;
    }
}
