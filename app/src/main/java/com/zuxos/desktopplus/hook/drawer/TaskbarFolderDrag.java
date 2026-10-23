package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A folder of ours held in the taskbar's drawer: ZUI's drag of it in that drawer, as ZUI drags the
 * drawer's apps, so it can be put in a new place ({@link DrawerRearrange}) or pinned to the bar.
 *
 * <p>ZUI's taskbar drags only app icons ({@code TaskbarDragController.l0}: a
 * {@code BubbleTextView} or an {@code ActiveIconView}), and a folder's own drag goes through a
 * delegate that does nothing outside ZUX Home ({@code LauncherDelegate$b.b}). So the drag is
 * started as ZUI starts an app's ({@code TaskbarDragController.n0}): ZUI's picture of the icon
 * ({@code DragPreviewProvider}), at the scale and place ZUI works out for it, on ZUI's controller
 * ({@code startDrag}), with the menu already shown as its pre-drag - the menu stays while the
 * finger rests, and gives way to the drag once it moves.
 */
final class TaskbarFolderDrag {

    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";
    private static final String PREVIEW = "com.android.launcher3.graphics.DragPreviewProvider";
    private static final String OPTIONS = "com.android.launcher3.dragndrop.DragOptions";
    private static final String DRAG_SOURCE = "com.android.launcher3.DragSource";

    private TaskbarFolderDrag() {
    }

    /** After ZUI's menu for the folder is shown: its drag, ready for the finger to move. */
    static boolean start(View icon, Item folder) {
        try {
            Context ctx = icon.getContext();
            Object activity = ZuiFolders.lookup(ctx);
            if (!Cfg.drawerRearrange() || !Cfg.taskbarDrawerRearrange() || activity == null
                    || !activity.getClass().getName().endsWith("TaskbarOverlayContext")
                    || Settings.System.getInt(ctx.getContentResolver(), "zui_ov_desktop_mode", 0)
                    == 1) {
                return false;
            }
            Object controller = Reflect.call(activity, "getDragController");
            ClassLoader loader = ctx.getClassLoader();
            Object popup = Class.forName(POPUP, false, loader)
                    .getMethod("getOpen", Context.class).invoke(null, activity);
            Object pre = popup == null ? null : Reflect.call(popup, "createPreDragCondition", true);
            Method begin = startDrag(controller);
            if (controller == null || pre == null || begin == null) {
                L.i("zui folders: " + folder.label + " held in the taskbar's drawer, not dragged"
                        + " - " + (pre == null ? "ZUI's menu not found" : "ZUI's drag not found"));
                return false;
            }
            Object preview = Class.forName(PREVIEW, false, loader).getConstructor(View.class)
                    .newInstance(icon);
            Drawable picture = (Drawable) Reflect.call(preview, "createDrawable");
            int[] at = new int[2];
            // The overload for the picture, not the one for a view.
            Object scaled = picture == null ? null : preview.getClass()
                    .getMethod("getScaleAndPosition", Drawable.class, int[].class)
                    .invoke(preview, picture, at);
            if (!(scaled instanceof Float)) {
                L.i("zui folders: " + folder.label + " not dragged - ZUI's picture not made");
                return false;
            }
            float scale = (Float) scaled;
            Rect bounds = new Rect();
            Reflect.call(icon, "getPreviewBounds", bounds);
            if (bounds.isEmpty()) {
                bounds.set(0, 0, icon.getWidth(), icon.getHeight());
            }
            Object options = Class.forName(OPTIONS, false, loader).getConstructor()
                    .newInstance();
            options.getClass().getField("preDragCondition").set(options, pre);
            Object offset = Reflect.call(pre, "getDragOffset");
            Point shift = offset instanceof Point ? (Point) offset : new Point();
            Object source = Proxy.newProxyInstance(loader,
                    new Class<?>[]{Class.forName(DRAG_SOURCE, false, loader)},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onDropCompleted") && args != null
                                && args.length == 3 && args[2] instanceof Boolean) {
                            // What ZUI's own source tells it (n0's b0) - and taken either way:
                            // ZUI's flight back (k0) sizes a view by its whole width unless it is
                            // an app icon, and a folder's cell is twice its icon's width, so the
                            // picture grew on its way back. Not taken, the folder shows again
                            // in its place as the drag ends (DrawerSession.end).
                            setTaken(controller, true);
                            if (!(Boolean) args[2]) {
                                L.i("zui folders: " + folder.label + " let go where nothing"
                                        + " took it - shown again in its place");
                            }
                        }
                        return null;
                    });
            // The size ZUI's drag takes the picture to be, as it sets it for an app's icon (n0:
            // getIconSize): its drag view grows by this less the picture's width (startDrag). Left
            // at the last app's size, a folder's picture grew by the difference - three times too
            // big and blurred. Set to the picture's width and ZUI's pre-drag growth: the lift ZUI's
            // home screen gives a held folder while its menu shows (LauncherDragController:
            // pre_drag_view_scale).
            if (!setIconSize(controller, bounds.width() + preDragGrowth(ctx))) {
                L.i("zui folders: " + folder.label + " not dragged - ZUI's drag size not found");
                return false;
            }
            // Picked up at the size shown, as ZUI's home folders are (ZuiFolderHome).
            ZuiFolderHome.holding(true);
            try {
                begin.invoke(controller, picture, null, icon, at[0] + shift.x,
                        at[1] + bounds.top + shift.y, source, icon.getTag(), bounds, scale,
                        scale, options);
            } finally {
                ZuiFolderHome.holding(false);
            }
            L.i("zui folders: " + folder.label
                    + " held in the taskbar's drawer - ZUI's drag of it");
            return true;
        } catch (Throwable t) {
            L.w("zui folders: " + folder.label + " not dragged in the taskbar's drawer (" + t
                    + ")");
            return false;
        }
    }

    /** {@code TaskbarDragController.startDrag(Drawable, View, DraggableView, int, int, ...)}. */
    private static Method startDrag(Object controller) {
        if (controller == null) {
            return null;
        }
        for (Method m : controller.getClass().getMethods()) {
            if (m.getName().equals("startDrag") && m.getParameterCount() == 11
                    && m.getParameterTypes()[0] == Drawable.class) {
                return m;
            }
        }
        return null;
    }

    /** ZUI's growth of a held icon while its menu shows ({@code pre_drag_view_scale}), or 0. */
    private static int preDragGrowth(Context ctx) {
        int id = ctx.getResources().getIdentifier("pre_drag_view_scale", "dimen",
                ctx.getPackageName());
        return id != 0 ? ctx.getResources().getDimensionPixelSize(id) : 0;
    }

    /** ZUI's size of the icon being dragged ({@code TaskbarDragController.g}); false if absent. */
    private static boolean setIconSize(Object controller, int size) {
        try {
            Field f = controller.getClass().getDeclaredField("g");
            if (f.getType() != int.class || size <= 0) {
                return false;
            }
            f.setAccessible(true);
            f.setInt(controller, size);
            return true;
        } catch (Throwable t) {
            L.d("zui folders: ZUI's drag size not set (" + t + ")");
            return false;
        }
    }

    /** ZUI's own note of a drop that took the drag ({@code TaskbarDragController.m}). */
    static void setTaken(Object controller, boolean taken) {
        try {
            Field f = controller.getClass().getDeclaredField("m");
            if (f.getType() == boolean.class) {
                f.setAccessible(true);
                f.setBoolean(controller, taken);
            }
        } catch (Throwable t) {
            L.d("zui folders: ZUI's drop note not set (" + t + ")");
        }
    }
}
