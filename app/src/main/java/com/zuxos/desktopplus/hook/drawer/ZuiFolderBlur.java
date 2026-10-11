package com.zuxos.desktopplus.hook.drawer;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The taskbar drawer blurred behind our open folder, as ZUI blurs behind its home folders.
 *
 * <p>ZUI blurs what is behind a folder only for a folder of its home screen: its blur animation
 * ({@code FolderBlurBgAnim}, render blur 0 to 70) is added in {@code FolderAnimationManager}
 * only when the folder's own context is {@code Launcher}. Our drawer folders are in the taskbar
 * drawer's window, so they opened with no blur and the drawer's rows read through them (15:14).
 * And there ZUI's way - cutting the folder's shape out of its window and blurring what is behind
 * the window - could not work anyway: the drawer's sheet is in the folder's own window.
 *
 * <p>So the sheet beside the folder is blurred at ZUI's radius as the folder opens, and sharp
 * again as it closes. Set once each way: 1.0.211 eased the radius in, a new blur of the whole
 * sheet on every frame of ZUI's open, and on the 165 Hz screen that cost the frames ZUI moves the
 * folder's apps in on - they popped in at the end (22:11). Set once, the GPU blurs the sheet once
 * and reuses it while ZUI animates.
 */
final class ZuiFolderBlur {

    private static final String SHEET =
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView";
    /** ZUI's render blur behind an open home folder ({@code FolderBlurBgAnim}: 0 to 70). */
    private static final float ZUI_BLUR = 70f;

    private static boolean sSaid;

    private ZuiFolderBlur() {
    }

    static void install(Class<?> folderCls) {
        try {
            int hooked = XposedBridge.hookAllMethods(folderCls, "animateOpen",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (inDrawer(param.thisObject)) {
                                blur((View) param.thisObject, true);
                            }
                        }
                    }).size();
            XC_MethodHook sharp = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (inDrawer(param.thisObject)) {
                        blur((View) param.thisObject, false);
                    }
                }
            };
            hooked += XposedBridge.hookAllMethods(folderCls, "handleClose", sharp).size();
            hooked += XposedBridge.hookAllMethods(folderCls, "onDetachedFromWindow", sharp)
                    .size();
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

    /** The drawer's sheet beside the folder blurred at ZUI's radius, or sharp again. */
    private static void blur(View folder, boolean on) {
        try {
            if (!(folder.getParent() instanceof ViewGroup)) {
                return;
            }
            Class<?> sheetCls = Class.forName(SHEET, false, folder.getClass().getClassLoader());
            ViewGroup layer = (ViewGroup) folder.getParent();
            for (int i = 0; i < layer.getChildCount(); i++) {
                View sheet = layer.getChildAt(i);
                if (sheetCls.isInstance(sheet)) {
                    sheet.setRenderEffect(on ? RenderEffect.createBlurEffect(ZUI_BLUR, ZUI_BLUR,
                            Shader.TileMode.CLAMP) : null);
                }
            }
            if (on && !sSaid) {
                sSaid = true;
                L.i("zui folder blur: the drawer blurred behind our folder as it opens");
            }
        } catch (Throwable t) {
            L.d("zui folder blur: not applied (" + t + ")");
        }
    }
}
