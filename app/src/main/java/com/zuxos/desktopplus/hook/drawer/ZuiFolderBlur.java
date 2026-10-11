package com.zuxos.desktopplus.hook.drawer;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The taskbar drawer blurred behind our open folder, following ZUI's own folder blur.
 *
 * <p>Our drawer folders move as ZUX Home's ({@link ZuiFolders}), and in that motion ZUI's
 * {@code Folder} works out its blur at every step: {@code setWallpaperBlur} fills its
 * {@code LauncherBlurAreaData} (radius and strength, from nothing to full as it opens and back as
 * it closes). On the home screen ZUI cuts the folder's shape out of the home's window and blurs
 * the wallpaper behind it. In the taskbar's drawer that cannot work: the drawer's sheet is in the
 * folder's own window, so the folder showed the drawer's rows straight through (14:12). Here the
 * sheet itself is blurred, step for step with ZUI's numbers, and sharp again as the folder
 * leaves. Only while ZUI moves the folder is anything set; the GPU keeps the effect after.
 */
final class ZuiFolderBlur {

    private static final String SHEET =
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView";
    /** The most the sheet is blurred, in pixels: ZUI's surface radius is on another scale. */
    private static final float MAX_PX = 60f;
    /** ZUI's own folder blur radius when fully open ({@code updateTbFolderAnimBlurParams}). */
    private static final float ZUI_FULL = 160f;

    private static boolean sSaid;

    private ZuiFolderBlur() {
    }

    static void install(Class<?> folderCls) {
        try {
            int hooked = XposedBridge.hookAllMethods(folderCls, "setWallpaperBlur",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (inDrawer(param.thisObject)) {
                                blur((View) param.thisObject, amount(param.thisObject));
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(folderCls, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inDrawer(param.thisObject)) {
                                blur((View) param.thisObject, 0f);
                            }
                        }
                    }).size();
            L.i("zui folder blur: the drawer blurred behind our folders x" + hooked);
        } catch (Throwable t) {
            L.w("zui folder blur: not installed, the drawer stays sharp (" + t + ")");
        }
    }

    /** One of our folders, in the taskbar drawer's window. */
    private static boolean inDrawer(Object folder) {
        Object where = ZuiFolders.contextOf(folder);
        return folder instanceof View && ZuiFolders.isOurs(folder) && where != null
                && !ZuiFolders.isLauncher(where) && !ZuiFolders.isBar(where);
    }

    /** ZUI's blur for the folder now, as pixels of blur on the sheet; 0 for none. */
    private static float amount(Object folder) {
        try {
            Object data = folder.getClass().getMethod("getBlurAreaData").invoke(folder);
            float radius = ((Number) Reflect.field(data, "blurRadius")).floatValue();
            float alpha = ((Number) Reflect.field(data, "blurAlpha")).floatValue();
            if (radius <= 0f || alpha <= 0f) {
                return 0f;
            }
            return MAX_PX * Math.min(1f, radius / ZUI_FULL) * Math.min(1f, alpha);
        } catch (Throwable t) {
            L.d("zui folder blur: no blur from ZUI (" + t + ")");
            return 0f;
        }
    }

    /** The drawer's sheet beside the folder, blurred by this much; sharp at 0. */
    private static void blur(View folder, float px) {
        try {
            if (!(folder.getParent() instanceof ViewGroup)) {
                return;
            }
            Class<?> sheetCls = Class.forName(SHEET, false, folder.getClass().getClassLoader());
            ViewGroup layer = (ViewGroup) folder.getParent();
            for (int i = 0; i < layer.getChildCount(); i++) {
                View sheet = layer.getChildAt(i);
                if (sheetCls.isInstance(sheet)) {
                    sheet.setRenderEffect(px < 0.5f ? null
                            : RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP));
                }
            }
            if (!sSaid && px > 0f) {
                sSaid = true;
                L.i("zui folder blur: the drawer blurred behind our folder, as ZUI opens it");
            }
        } catch (Throwable t) {
            L.d("zui folder blur: not applied (" + t + ")");
        }
    }
}
