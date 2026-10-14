package com.zuxos.desktopplus.hook.drawer;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Our drawer folders answer a touch as ZUI's app icons do: the icon shrinks under the finger and
 * comes back when it lets go, and a drag lifts it from there.
 *
 * <p>ZUI's app icons press through their drawable ({@code FastBitmapDrawable}: to 0.8 in 200 ms,
 * accelerating; back in 200 ms, decelerating; {@code BubbleTextView.O} on the touch). ZUI's
 * {@code FolderIcon} has no press of its own, so ours gets the same: its preview - background,
 * apps and dot, which {@code FolderIcon.dispatchDraw} draws before its name - is drawn scaled
 * about its centre; the name stays. When a hold becomes a drag, ZUI's picture starts at the
 * pressed size and grows to its pickup size ({@code DragView}, 150 ms), as an app's does
 * ({@code TaskbarDragController.n0} passes the icon's pressed scale).
 */
final class FolderPress {

    /** ZUI's pressed scale for an app icon ({@code FastBitmapDrawable}). */
    static final float PRESSED = 0.8f;
    private static final long MS = 200L;

    /** Each pressed folder icon's scale now; one at rest is not in it. Main thread. */
    private static final Map<View, float[]> SCALE = new WeakHashMap<>();
    private static final Map<View, ValueAnimator> RUNNING = new WeakHashMap<>();
    /** ZUI draws a folder's drag picture now: the folder at rest. Main thread. */
    static boolean sDrawingPicture;
    /** Each icon's canvas save while its preview is drawn scaled. */
    private static final Map<View, Integer> SAVED = new WeakHashMap<>();

    private FolderPress() {
    }

    static int install(Class<?> folderIcon) {
        int hooked = 0;
        try {
            hooked += XposedBridge.hookAllMethods(folderIcon, "onTouchEvent",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 1 && param.args[0] instanceof MotionEvent) {
                                touched((View) param.thisObject, (MotionEvent) param.args[0]);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(folderIcon, "dispatchDraw",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            scaleFrom((View) param.thisObject, param.args);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            restore((View) param.thisObject, param.args);
                        }
                    }).size();
            // The name is drawn after the dot: the scale ends with the dot.
            hooked += XposedBridge.hookAllMethods(folderIcon, "drawDot", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    restore((View) param.thisObject, param.args);
                }
            }).size();
        } catch (Throwable t) {
            L.w("folder press: not installed (" + t + ")");
        }
        return hooked;
    }

    private static void touched(View icon, MotionEvent event) {
        try {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (ZuiFolders.folderOfIcon(icon) != null && inDrawer(icon)) {
                    animate(icon, PRESSED);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pressedOff(icon);
            }
        } catch (Throwable t) {
            L.d("folder press: touch not answered (" + t + ")");
        }
    }

    /** The icon back to rest, as ZUI's app icons come back. */
    static void pressedOff(View icon) {
        if (icon != null && SCALE.containsKey(icon)) {
            animate(icon, 1f);
        }
    }

    private static void animate(View icon, float to) {
        ValueAnimator old = RUNNING.remove(icon);
        if (old != null) {
            old.cancel();
        }
        float[] now = SCALE.get(icon);
        float from = now != null ? now[0] : 1f;
        if (from == to) {
            return;
        }
        float[] scale = now != null ? now : new float[]{1f};
        SCALE.put(icon, scale);
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(MS);
        // ZUI's: accelerating into the press, decelerating out of it.
        anim.setInterpolator(to < 1f ? new AccelerateInterpolator()
                : new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            scale[0] = (Float) a.getAnimatedValue();
            icon.invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (RUNNING.get(icon) == anim) {
                    RUNNING.remove(icon);
                }
                if (scale[0] >= 1f) {
                    SCALE.remove(icon);
                }
            }
        });
        RUNNING.put(icon, anim);
        anim.start();
    }

    private static void scaleFrom(View icon, Object[] args) {
        float[] scale = SCALE.get(icon);
        if (scale == null || scale[0] >= 1f || sDrawingPicture || args.length != 1
                || !(args[0] instanceof Canvas)) {
            return;
        }
        Canvas canvas = (Canvas) args[0];
        Rect preview = new Rect();
        Reflect.call(icon, "getPreviewBounds", preview);
        float cx = preview.isEmpty() ? icon.getWidth() / 2f : preview.exactCenterX();
        float cy = preview.isEmpty() ? icon.getWidth() / 2f : preview.exactCenterY();
        SAVED.put(icon, canvas.save());
        canvas.scale(scale[0], scale[0], cx, cy);
    }

    private static void restore(View icon, Object[] args) {
        Integer saved = SAVED.remove(icon);
        if (saved != null && args.length == 1 && args[0] instanceof Canvas) {
            ((Canvas) args[0]).restoreToCount(saved);
        }
    }

    private static boolean inDrawer(View icon) {
        for (android.view.ViewParent p = icon.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().endsWith("AllAppsContainerView")) {
                return true;
            }
        }
        return false;
    }
}
