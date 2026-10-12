package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
 * An app's own shortcuts in ZUI's shape, as ZUI shapes every app icon.
 *
 * <p>ZUI gives app icons its theme's shape in its icon provider
 * ({@code GraphicsUtils.getZuiThemeAdaptiveIconDrawable}, while its themed icons are off); a
 * shortcut's icon never passes there, in ZUI's own menus or ours, and kept the app's square
 * backing - boxy beside the round app icons (owner, 15:56). Both go through that same call now:
 * <ul>
 *   <li>ZUI's own shortcut rows ({@code DeepShortcutView.applyShortcutInfo}), once ZUI has
 *       filled them, the icon made off the main thread and kept, so a menu never waits;</li>
 *   <li>our menus' shortcut icons ({@link TaskbarApps#shortcutIcon}).</li>
 * </ul>
 */
final class ShortcutShapes {

    private static final String ROW = "com.android.launcher3.shortcuts.DeepShortcutView";
    private static final String GRAPHICS = "com.android.launcher3.icons.GraphicsUtils";
    /** Which shortcut a row's icon view is waiting for. */
    private static final int TAG_WANTS = 0x7A000302;

    private static final ExecutorService LOAD = Executors.newSingleThreadExecutor(
            Threads.named("zux-shortcut-icons", Thread.MIN_PRIORITY));
    private static final LruCache<String, Bitmap> MADE = new LruCache<>(24);

    private static Method sShape;
    private static Method sRegion;
    private static Method sThemed;

    private ShortcutShapes() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> graphics = Reflect.findClass(GRAPHICS, loader);
            Class<?> row = Reflect.findClass(ROW, loader);
            if (graphics == null || row == null) {
                L.w("shortcut shapes: ZUI's icon shape not found - shortcut icons as they are");
                return;
            }
            sShape = graphics.getMethod("getZuiThemeAdaptiveIconDrawable", Drawable.class,
                    int.class);
            sRegion = graphics.getMethod("isZuiRow");
            sThemed = graphics.getMethod("isThemedIconEnabled", Context.class);
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
     * The icon in ZUI's shape, exactly as ZUI shapes an app's: where ZUI shapes app icons, and
     * with its own call; else the icon as it is.
     */
    static Drawable shaped(Context ctx, Drawable icon) {
        if (icon == null || sShape == null || !Cfg.shortcutShapes()) {
            return icon;
        }
        try {
            // ZUI's own condition: its theme's shape, with its themed icons off.
            if (!Boolean.TRUE.equals(sRegion.invoke(null))
                    || Boolean.TRUE.equals(sThemed.invoke(null, ctx))) {
                return icon;
            }
            Object out = sShape.invoke(null, icon,
                    ctx.getResources().getDisplayMetrics().densityDpi);
            return out instanceof Drawable ? (Drawable) out : icon;
        } catch (Throwable t) {
            L.d("shortcut shapes: one left as it is (" + t + ")");
            return icon;
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
            LauncherApps apps = ctx.getSystemService(LauncherApps.class);
            Drawable raw = apps == null ? null : apps.getShortcutIconDrawable(shortcut,
                    ctx.getResources().getDisplayMetrics().densityDpi);
            Drawable shaped = shaped(ctx, raw);
            if (shaped == null || shaped == raw) {
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
