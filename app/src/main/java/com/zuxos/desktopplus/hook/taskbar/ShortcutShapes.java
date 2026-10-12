package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
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
 * An app's own shortcuts in ZUI's shape, as ZUI draws a shortcut on its home screen.
 *
 * <p>ZUI has two ways of loading a shortcut's icon ({@code CacheableShortcutInfo.getIcon}): its
 * menus' rows take the plain one, which keeps the app's square backing - boxy beside the round
 * app icons (owner, 15:56); its home screen's shortcuts and their drag take the one for its theme
 * ({@code getIcon(.., useZuiTheme, forAnim)}, through {@code Utilities.getZuiThemedIcon}), which
 * cuts the icon to the theme's shape with ZUI's own icon factory. Ours take that one:
 * <ul>
 *   <li>ZUI's own shortcut rows ({@code DeepShortcutView.applyShortcutInfo}), once ZUI has
 *       filled them, the icon made off the main thread and kept, so a menu never waits;</li>
 *   <li>our menus' shortcut icons ({@link TaskbarApps#shortcutIcon}).</li>
 * </ul>
 * The call of the last build ({@code GraphicsUtils.getZuiThemeAdaptiveIconDrawable}) shapes only
 * in one of ZUI's regions, and left them boxy on the owner's (16:29).
 */
final class ShortcutShapes {

    private static final String ROW = "com.android.launcher3.shortcuts.DeepShortcutView";
    private static final String ICONS = "com.android.launcher3.icons.CacheableShortcutInfo";
    /** Which shortcut a row's icon view is waiting for. */
    private static final int TAG_WANTS = 0x7A000302;

    private static final ExecutorService LOAD = Executors.newSingleThreadExecutor(
            Threads.named("zux-shortcut-icons", Thread.MIN_PRIORITY));
    private static final LruCache<String, Bitmap> MADE = new LruCache<>(24);

    /** ZUI's {@code CacheableShortcutInfo.getIcon(Context, ShortcutInfo, int, boolean, boolean)}. */
    private static Method sThemed;
    private static boolean sSaid;

    private ShortcutShapes() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> icons = Reflect.findClass(ICONS, loader);
            Class<?> row = Reflect.findClass(ROW, loader);
            if (icons == null || row == null) {
                L.w("shortcut shapes: ZUI's themed shortcut icons not found - shortcut icons as "
                        + "they are");
                return;
            }
            sThemed = icons.getMethod("getIcon", Context.class, ShortcutInfo.class, int.class,
                    boolean.class, boolean.class);
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
            sThemed = null;
            L.w("shortcut shapes: not installed (" + t + ")");
        }
    }

    /**
     * The shortcut's icon as ZUI draws it on its home screen, in its theme's shape, drawn into a
     * bitmap of our own; null where that cannot be had, for the caller's own.
     */
    static Drawable shaped(Context ctx, ShortcutInfo shortcut) {
        if (shortcut == null || sThemed == null || !Cfg.shortcutShapes()) {
            return null;
        }
        try {
            Object out = sThemed.invoke(null, ctx, shortcut,
                    ctx.getResources().getDisplayMetrics().densityDpi, true, true);
            Drawable icon = out instanceof Drawable ? (Drawable) out : null;
            if (!sSaid) {
                sSaid = true;
                L.i("shortcut shapes: ZUI's themed icon for " + shortcut.getPackage() + " is "
                        + (icon == null ? "none" : icon.getClass().getSimpleName() + " "
                        + icon.getIntrinsicWidth() + "x" + icon.getIntrinsicHeight()));
            }
            int size = icon == null ? 0 : icon.getIntrinsicWidth();
            if (size <= 0) {
                return null;
            }
            // Drawn here, where a failure is caught: ZUI's bitmap may be one only the GPU can
            // draw, which a menu's software canvas would throw on.
            Bitmap soft = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, size, size);
            icon.draw(new Canvas(soft));
            return new BitmapDrawable(ctx.getResources(), soft);
        } catch (Throwable t) {
            L.d("shortcut shapes: one left as it is (" + t + ")");
            return null;
        }
    }

    private static void reshape(View row, Object[] args) {
        try {
            if (sThemed == null || !Cfg.shortcutShapes()) {
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
