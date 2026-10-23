package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Threads;
import com.zuxos.desktopplus.core.Ui;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * An app's own shortcuts in ZUI's shape, made exactly as ZUI makes its app icons.
 *
 * <p>ZUI's app icons get their shape from its icon factory: the app's own icon goes through
 * {@code BaseIconFactory.createBadgedIconBitmap} ({@code LauncherActivityCachingLogic.loadIcon}),
 * which cuts it to the theme's shape. A shortcut's icon never does - its menus draw it as the app
 * gave it, on its square backing, boxy beside the round app icons. Ours go through that same
 * factory call:
 * <ul>
 *   <li>ZUI's own shortcut rows ({@code DeepShortcutView.applyShortcutInfo}), once ZUI has
 *       filled them, the icon made off the main thread and kept, so a menu never waits;</li>
 *   <li>our menus' shortcut icons ({@link TaskbarApps#shortcutIcon}).</li>
 * </ul>
 * Two other calls fall short: {@code GraphicsUtils.getZuiThemeAdaptiveIconDrawable} shapes only
 * in one of ZUI's regions, and {@code CacheableShortcutInfo.getIcon} with ZUI's theme flattens
 * the icon to a square before shaping, so it comes out a box on a plate.
 */
final class ShortcutShapes {

    private static final String ROW = "com.android.launcher3.shortcuts.DeepShortcutView";
    private static final String ICONS = "com.android.launcher3.icons.LauncherIcons";
    /** Which shortcut a row's icon view is waiting for. */
    private static final int TAG_WANTS = 0x7A000302;

    private static final ExecutorService LOAD = Executors.newSingleThreadExecutor(
            Threads.named("zux-shortcut-icons", Thread.MIN_PRIORITY));
    private static final LruCache<String, Bitmap> MADE = new LruCache<>(24);

    /** ZUI's {@code LauncherIcons.obtain(Context)}: one of its icon factories, from its pool. */
    private static Method sObtain;
    /** The factory's {@code createBadgedIconBitmap(Drawable)}, which app icons go through. */
    private static Method sShape;
    private static boolean sSaid;

    private ShortcutShapes() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> icons = Reflect.findClass(ICONS, loader);
            Class<?> row = Reflect.findClass(ROW, loader);
            if (icons == null || row == null) {
                L.w("shortcut shapes: ZUI's icon factory not found - shortcut icons as they are");
                return;
            }
            sObtain = icons.getMethod("obtain", Context.class);
            sShape = icons.getMethod("createBadgedIconBitmap", Drawable.class);
            int hooked = XposedBridge.hookAllMethods(row, "applyShortcutInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            reshape((View) param.thisObject, param.args);
                        }
                    }).size();
            Health.hooked("menus: app shortcut icons in ZUI's shape", hooked,
                    Cfg::shortcutShapes);
            L.i("shortcut shapes: app shortcut icons in ZUI's own shape x" + hooked);
        } catch (Throwable t) {
            sShape = null;
            L.w("shortcut shapes: not installed (" + t + ")");
        }
    }

    /**
     * The shortcut's icon cut to ZUI's shape by ZUI's icon factory, as an app's icon is, drawn
     * into a bitmap of our own. An icon that is not adaptive has no shape to cut and comes as
     * the app drew it; null where there is none, for the caller's own.
     */
    static Drawable shaped(Context ctx, ShortcutInfo shortcut) {
        if (shortcut == null || sShape == null || !Cfg.shortcutShapes()) {
            return null;
        }
        AutoCloseable factory = null;
        try {
            LauncherApps apps = ctx.getSystemService(LauncherApps.class);
            Drawable raw = apps == null ? null : apps.getShortcutIconDrawable(shortcut,
                    ctx.getResources().getDisplayMetrics().densityDpi);
            if (raw == null) {
                return null;
            }
            if (!(raw instanceof AdaptiveIconDrawable)) {
                // ZUI's factory sets any other icon on a white plate (wrapToAdaptiveIcon), as it
                // does a legacy app's - a white square over a plain glyph. Left as the app drew it.
                return raw;
            }
            factory = (AutoCloseable) sObtain.invoke(null, ctx);
            Object info = sShape.invoke(factory, raw);
            Object icon = info == null ? null : info.getClass().getField("icon").get(info);
            if (!sSaid) {
                sSaid = true;
                L.i("shortcut shapes: " + shortcut.getPackage() + "'s "
                        + raw.getClass().getSimpleName() + " through ZUI's icon factory -> "
                        + (icon instanceof Bitmap ? ((Bitmap) icon).getWidth() + "px "
                        + ((Bitmap) icon).getConfig() : "nothing"));
            }
            if (!(icon instanceof Bitmap)) {
                return null;
            }
            Bitmap made = (Bitmap) icon;
            // Drawn here, where a failure is caught: ZUI's bitmap may be one only the GPU can
            // draw, which a menu's software canvas would throw on.
            Bitmap soft = made.getConfig() == Bitmap.Config.HARDWARE
                    ? made.copy(Bitmap.Config.ARGB_8888, false) : made;
            return soft == null ? null : new BitmapDrawable(ctx.getResources(), soft);
        } catch (Throwable t) {
            L.d("shortcut shapes: one left as it is (" + t + ")");
            return null;
        } finally {
            if (factory != null) {
                try {
                    // Back to ZUI's pool, as ZUI hands its factories back.
                    factory.close();
                } catch (Throwable ignored) {
                    // A factory not returned is made again by ZUI's pool.
                }
            }
        }
    }

    private static void reshape(View row, Object[] args) {
        try {
            if (sShape == null || !Cfg.shortcutShapes()) {
                return;
            }
            ShortcutInfo shortcut = null;
            for (Object arg : args) {
                if (arg instanceof ShortcutInfo) {
                    shortcut = (ShortcutInfo) arg;
                }
            }
            Object found = Reflect.call(row, "getIconView");
            if (shortcut == null || !(found instanceof View)) {
                return;
            }
            View icon = (View) found;
            Context ctx = row.getContext();
            int size = sizeOf(icon);
            String key = shortcut.getUserHandle().hashCode() + "/" + shortcut.getPackage() + "/"
                    + shortcut.getId() + "/" + shortcut.getLastChangedTimestamp() + "/" + size;
            Bitmap ready = MADE.get(key);
            if (ready != null) {
                icon.setBackground(new BitmapDrawable(ctx.getResources(), ready));
                return;
            }
            // ZUI's icon stays until ours is made; a row reused for another shortcut meanwhile
            // keeps that one's.
            icon.setTag(TAG_WANTS, key);
            ShortcutInfo wanted = shortcut;
            LOAD.execute(() -> {
                Bitmap made = make(ctx, wanted, size);
                if (made == null) {
                    return;
                }
                MADE.put(key, made);
                icon.post(() -> {
                    if (key.equals(icon.getTag(TAG_WANTS))) {
                        icon.setBackground(new BitmapDrawable(ctx.getResources(), made));
                    }
                });
            });
        } catch (Throwable t) {
            L.d("shortcut shapes: one row left as ZUI drew it (" + t + ")");
        }
    }

    /** The icon view's size: laid out, asked for, or ZUI's usual. */
    private static int sizeOf(View icon) {
        int size = Math.max(icon.getWidth(), icon.getLayoutParams() != null
                ? icon.getLayoutParams().width : 0);
        return size > 0 ? size : Ui.dp(icon.getContext(), 40);
    }

    /** Background thread: the shortcut's icon in ZUI's shape; null to leave ZUI's. */
    private static Bitmap make(Context ctx, ShortcutInfo shortcut, int size) {
        try {
            Drawable shaped = shaped(ctx, shortcut);
            if (shaped == null) {
                return null;
            }
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            shaped.setBounds(0, 0, size, size);
            shaped.draw(new Canvas(out));
            return out;
        } catch (Throwable t) {
            L.d("shortcut shapes: no icon made for " + shortcut.getId() + " (" + t + ")");
            return null;
        }
    }
}
