package com.zuxos.desktopplus.hook.systemui;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import android.view.AttachedSurfaceControl;
import android.view.DragEvent;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The shell's drop window leaves the taskbar to the taskbar.
 *
 * <p>While an app is dragged, SystemUI's drop window ("ShellDropTarget",
 * {@code DragAndDropController}) covers the whole screen to open it as a floating window or in
 * split screen. ZUI's drag names the part it must not take - {@code DISALLOW_HIT_REGION}, its
 * transient bar - and the shell's {@code SplitDragPolicy.getTargetAtLocation} skips it, but its
 * window still lies over it, so a drop there reached nothing. For the drag of that one drag, the
 * window stops taking touches there ({@code setTouchableRegion}), and a drop on the bar reaches the
 * bar's own window (the launcher's {@code ZuiBarDrop} puts our bar into that region). Everywhere
 * else the shell does what it always did; at the drag's end its window takes the whole screen
 * again.
 */
public final class ShellDropBar {

    private static final String CONTROLLER =
            "com.android.wm.shell.draganddrop.DragAndDropController";
    /** ZUI's {@code DragAndDropConstants.EXTRA_DISALLOW_HIT_REGION}. */
    private static final String DISALLOW = "DISALLOW_HIT_REGION";

    /** The shell's window we left a region of for the drag under way; null with none. */
    private static AttachedSurfaceControl sNarrowed;
    private static boolean sSaidNoClip;

    private ShellDropBar() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> controller = Reflect.findClass(CONTROLLER, loader);
            if (controller == null) {
                L.w("shell drop bar: SystemUI's drag and drop not found, its window covers the "
                        + "bar as before");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(controller, "onDrag", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    after(param);
                }
            }).size();
            L.i("shell drop bar: the shell's drop window can leave the bar to the bar x"
                    + hooked);
        } catch (Throwable t) {
            L.w("shell drop bar: not installed (" + t + ")");
        }
    }

    private static void after(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args.length != 2 || !(param.args[0] instanceof View)
                    || !(param.args[1] instanceof DragEvent)) {
                return;
            }
            View window = (View) param.args[0];
            DragEvent event = (DragEvent) param.args[1];
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if (Boolean.TRUE.equals(param.getResult())) {
                        leaveBar(window, event);
                    }
                    break;
                case DragEvent.ACTION_DROP:
                    if (sNarrowed != null) {
                        L.i("shell drop bar: the shell took a drop at " + Math.round(event.getX())
                                + "," + Math.round(event.getY()) + " - "
                                + (Boolean.TRUE.equals(param.getResult()) ? "used" : "not used"));
                    }
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    restore();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            L.d("shell drop bar: left as the shell had it (" + t + ")");
        }
    }

    /** The drag's region ZUI keeps out of the shell, out of the shell's window's touch too. */
    private static void leaveBar(View window, DragEvent event) {
        restore();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !Cfg.enabled()
                || !Cfg.barDrop()) {
            // A window's touchable region can be set from Android 13; the tablet runs 16.
            return;
        }
        ClipData clip = event.getClipData();
        Intent intent = clip == null || clip.getItemCount() == 0 ? null
                : clip.getItemAt(0).getIntent();
        if (intent == null) {
            if (!sSaidNoClip) {
                sSaidNoClip = true;
                L.i("shell drop bar: a drag with no intent at its start, the window left whole");
            }
            return;
        }
        // Read through the extras: Intent.getExtra is not in the public SDK.
        Object extra = intent.getExtras() == null ? null : intent.getExtras().get(DISALLOW);
        AttachedSurfaceControl surface = window.getRootSurfaceControl();
        if (!(extra instanceof RectF) || ((RectF) extra).isEmpty() || surface == null) {
            return;
        }
        Rect bar = new Rect();
        ((RectF) extra).roundOut(bar);
        // The window fills its screen from its corner, so the screen's coordinates are its own.
        Region touchable = new Region(0, 0, Math.max(window.getWidth(), bar.right),
                Math.max(window.getHeight(), bar.bottom));
        touchable.op(bar, Region.Op.DIFFERENCE);
        surface.setTouchableRegion(touchable);
        sNarrowed = surface;
        L.i("shell drop bar: the drop window leaves " + bar.toShortString() + " to the bar");
    }

    /** The shell's window takes its whole screen again. */
    private static void restore() {
        AttachedSurfaceControl narrowed = sNarrowed;
        sNarrowed = null;
        if (narrowed != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                narrowed.setTouchableRegion(null);
            } catch (Throwable t) {
                L.d("shell drop bar: the window's whole screen not given back (" + t + ")");
            }
        }
    }
}
