package com.zuxos.desktopplus.hook.drawer;

import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.motion.Motion;

import java.util.Map;
import java.util.WeakHashMap;

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
 * <p>So the sheet beside the folder is blurred, as the folder opens and back as it closes, with
 * ZUI's own numbers: its render blur radius and the folder's own opening time. Only while that
 * runs is anything set; the GPU keeps the effect after.
 */
final class ZuiFolderBlur {

    private static final String SHEET =
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView";
    /** ZUI's render blur behind an open home folder ({@code FolderBlurBgAnim}: 0 to 70). */
    private static final float ZUI_BLUR = 70f;

    /** The blur animation running on each sheet, to turn round from where it is. */
    private static final Map<View, ValueAnimator> RUNNING = new WeakHashMap<>();
    /** The blur each sheet has now. */
    private static final Map<View, Float> NOW = new WeakHashMap<>();
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
                                blur((View) param.thisObject, ZUI_BLUR);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(folderCls, "handleClose",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inDrawer(param.thisObject)) {
                                blur((View) param.thisObject, 0f);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(folderCls, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inDrawer(param.thisObject)) {
                                for (View sheet : sheetsBeside((View) param.thisObject)) {
                                    stop(sheet);
                                    sheet.setRenderEffect(null);
                                    NOW.remove(sheet);
                                }
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

    /** The drawer's sheet beside the folder blurred to this much, over the folder's own time. */
    private static void blur(View folder, float to) {
        try {
            long duration = openDuration(folder);
            for (View sheet : sheetsBeside(folder)) {
                stop(sheet);
                Float now = NOW.get(sheet);
                ValueAnimator anim = ValueAnimator.ofFloat(now != null ? now : 0f, to);
                anim.setDuration(duration);
                anim.setInterpolator(Motion.EASE);
                anim.addUpdateListener(a -> set(sheet, (float) a.getAnimatedValue()));
                RUNNING.put(sheet, anim);
                anim.start();
            }
            if (!sSaid && to > 0f) {
                sSaid = true;
                L.i("zui folder blur: the drawer blurs behind our folder as it opens ("
                        + duration + "ms)");
            }
        } catch (Throwable t) {
            L.d("zui folder blur: not applied (" + t + ")");
        }
    }

    private static void set(View sheet, float px) {
        NOW.put(sheet, px);
        sheet.setRenderEffect(px < 0.5f ? null
                : RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP));
    }

    private static void stop(View sheet) {
        ValueAnimator running = RUNNING.remove(sheet);
        if (running != null) {
            running.cancel();
        }
    }

    /** ZUI's folder opening time ({@code config_materialFolderExpandDuration_open}). */
    private static long openDuration(View folder) {
        int id = folder.getResources().getIdentifier("config_materialFolderExpandDuration_open",
                "integer", folder.getContext().getPackageName());
        return id != 0 ? folder.getResources().getInteger(id) : Motion.MEDIUM;
    }

    /** The taskbar drawer sheets in the folder's own drag layer. */
    private static java.util.List<View> sheetsBeside(View folder) {
        java.util.List<View> out = new java.util.ArrayList<>();
        try {
            if (!(folder.getParent() instanceof ViewGroup)) {
                return out;
            }
            Class<?> sheetCls = Class.forName(SHEET, false, folder.getClass().getClassLoader());
            ViewGroup layer = (ViewGroup) folder.getParent();
            for (int i = 0; i < layer.getChildCount(); i++) {
                if (sheetCls.isInstance(layer.getChildAt(i))) {
                    out.add(layer.getChildAt(i));
                }
            }
        } catch (Throwable t) {
            L.d("zui folder blur: no sheet beside the folder (" + t + ")");
        }
        return out;
    }
}
