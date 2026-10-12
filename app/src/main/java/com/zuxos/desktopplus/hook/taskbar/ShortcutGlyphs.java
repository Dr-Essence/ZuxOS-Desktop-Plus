package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Threads;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.hook.ZuiLook;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * An app's own shortcuts in ZUI's app menu drawn as ZUI draws its own menu icons (owner's choice,
 * 2026-10-12): the app's one-colour icon in ZUI's shortcut grey where it has one, else its icon
 * in a circle - a photo, a contact's face, stays in colour. In our drawer folders ZUI drew them
 * on their square backing, boxy, and round on its home screen (owner, 15:56).
 *
 * <p>At ZUI's own row ({@code DeepShortcutView.applyShortcutInfo}), once ZUI has filled it: ZUI
 * lays the row out, styles and animates it; only its icon is ours. The app's icon is read off the
 * main thread and kept, so a menu never waits for it.
 */
final class ShortcutGlyphs {

    private static final String ROW = "com.android.launcher3.shortcuts.DeepShortcutView";
    /** Which shortcut a row's icon view is waiting for. */
    private static final int TAG_WANTS = 0x7A000302;
    /** The share of the icon a one-colour glyph fills, about as ZUI's own glyphs do. */
    private static final float GLYPH = 0.75f;

    private static final ExecutorService LOAD = Executors.newSingleThreadExecutor(
            Threads.named("zux-shortcut-icons", Thread.MIN_PRIORITY));
    private static final LruCache<String, Bitmap> MADE = new LruCache<>(24);

    private ShortcutGlyphs() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> row = Reflect.findClass(ROW, loader);
            if (row == null) {
                L.w("shortcut glyphs: ZUI's shortcut row not found - its icons as ZUI draws them");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(row, "applyShortcutInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            restyle((View) param.thisObject, param.args);
                        }
                    }).size();
            Health.hooked("menus: app shortcut icons like ZUI's own", hooked,
                    Cfg::shortcutGlyphs);
            L.i("shortcut glyphs: app shortcuts drawn like ZUI's own menu icons x" + hooked);
        } catch (Throwable t) {
            L.w("shortcut glyphs: not installed (" + t + ")");
        }
    }

    private static void restyle(View row, Object[] args) {
        try {
            if (!Cfg.shortcutGlyphs()) {
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
            int tint = ZuiLook.shortcutIcon(ctx);
            int size = sizeOf(icon);
            String key = shortcut.getUserHandle().hashCode() + "/" + shortcut.getPackage() + "/"
                    + shortcut.getId() + "/" + shortcut.getLastChangedTimestamp() + "/" + tint
                    + "/" + size;
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
                Bitmap made = make(ctx, wanted, tint, size);
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
            L.d("shortcut glyphs: one left as ZUI drew it (" + t + ")");
        }
    }

    /** The icon view's size: laid out, asked for, or ZUI's usual. */
    private static int sizeOf(View icon) {
        int size = Math.max(icon.getWidth(), icon.getLayoutParams() != null
                ? icon.getLayoutParams().width : 0);
        return size > 0 ? size : Ui.dp(icon.getContext(), 40);
    }

    /** Background thread: the app's icon for the shortcut, as a glyph or in a circle. */
    private static Bitmap make(Context ctx, ShortcutInfo shortcut, int tint, int size) {
        try {
            LauncherApps apps = ctx.getSystemService(LauncherApps.class);
            Drawable raw = apps == null ? null : apps.getShortcutIconDrawable(shortcut,
                    ctx.getResources().getDisplayMetrics().densityDpi);
            if (raw == null) {
                return null;
            }
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Drawable mono = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && raw instanceof AdaptiveIconDrawable
                    ? ((AdaptiveIconDrawable) raw).getMonochrome() : null;
            if (mono != null) {
                // The layer is half as big again as what shows of it (108 of 72), so it is laid
                // out past the glyph's box on every side by a quarter of the box.
                mono = mono.mutate();
                mono.setTint(tint);
                int box = Math.round(size * GLYPH);
                int at = (size - box) / 2;
                int bleed = box / 4;
                mono.setBounds(at - bleed, at - bleed, at + box + bleed, at + box + bleed);
                mono.draw(canvas);
                return out;
            }
            Path circle = new Path();
            circle.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
            canvas.clipPath(circle);
            raw.setBounds(0, 0, size, size);
            raw.draw(canvas);
            return out;
        } catch (Throwable t) {
            L.d("shortcut glyphs: no icon made for " + shortcut.getId() + " (" + t + ")");
            return null;
        }
    }
}
