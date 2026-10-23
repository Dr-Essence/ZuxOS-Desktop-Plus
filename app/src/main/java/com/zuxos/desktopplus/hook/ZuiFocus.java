package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A keyboard's or a game controller's focus among ZUI's views, for the drawers and the taskbar
 * alike: ZUI's own focus mark, and its popups kept to their rows.
 *
 * <p>ZUI's popups ask for the focus as they open, which one opened by touch never gets: Android
 * keeps no focus in touch mode. While a popup is open, an arrow pressed with the focus outside it
 * puts the focus on its first row, and an arrow that would leave it is kept ({@link #keepIn});
 * every row shows ZUI's focus mark ({@link #markRows}). A menu a key opens is marked as such
 * while it is made ({@link #byKeys}), for what it leaves out.
 */
public final class ZuiFocus {

    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";
    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";
    private static final String CONTROLLERS = ".TaskbarControllers";

    /** A popup whose rows carry the focus mark. */
    private static final int TAG_MARKED = 0x7A000501;
    /** A menu being opened by a key, for the length of the call. Main thread. */
    private static boolean sByKeys;

    private ZuiFocus() {
    }

    /** What opens a menu, telling whether one showed. */
    public interface Opening {
        boolean open() throws Exception;
    }

    /**
     * A menu opened by a key: while it is made, ZUI's floating window and split screen are left out
     * of it ({@link #byKeys()}) - a controller has no use for them. A menu opened by touch, a
     * stylus or a mouse keeps them.
     */
    public static boolean byKeys(Opening opening) throws Exception {
        sByKeys = true;
        try {
            return opening.open();
        } finally {
            sByKeys = false;
        }
    }

    /** Whether the menu being made now was asked for by a key. */
    public static boolean byKeys() {
        return sByKeys;
    }

    /** The focus direction of a D-pad key, or 0 for any other key. */
    public static int direction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return View.FOCUS_RIGHT;
            default:
                return 0;
        }
    }

    /** ZUI's context of a view ({@code ActivityContext.lookupContext}), or null. */
    public static Object lookup(Context ctx) {
        try {
            return Class.forName(CONTEXT, false, ctx.getClassLoader())
                    .getMethod("lookupContext", Context.class).invoke(null, ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    /** ZUI's popup open over this layer ({@code PopupContainerWithArrow.getOpen}), or null. */
    public static Object openPopup(View layer, Object activity) {
        try {
            for (java.lang.reflect.Method m : Class.forName(POPUP, false,
                    layer.getContext().getClassLoader()).getMethods()) {
                if (m.getName().equals("getOpen") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(activity)) {
                    return m.invoke(null, activity);
                }
            }
        } catch (Throwable t) {
            L.d("zui focus: no popup read (" + t + ")");
        }
        return null;
    }

    /**
     * While ZUI's popup is open: an arrow with the focus outside it puts the focus on its first
     * row, and one that would leave it is kept. False with no popup open.
     */
    public static boolean keepIn(View layer, int direction) {
        Object activity = lookup(layer.getContext());
        Object popup = activity == null ? null : openPopup(layer, activity);
        if (!(popup instanceof ViewGroup)) {
            return false;
        }
        markRows(popup);
        return keepWithin(layer, (ViewGroup) popup, direction);
    }

    /**
     * An arrow kept inside {@code group}, a popup or an open folder: with the focus outside it,
     * the focus goes to its first row; one that would leave it, or reach a text field, is kept.
     * A folder's name is one, and taking the focus starts ZUI's rename of the folder.
     */
    public static boolean keepWithin(View layer, ViewGroup group, int direction) {
        View focused = layer.findFocus();
        if (focused == null || !within(focused, group)) {
            View first = firstRow(group);
            return first != null && first.requestFocus();
        }
        View next = focused.focusSearch(direction);
        return next == null || !within(next, group) || next instanceof EditText;
    }

    /** The popup's first row takes the focus when the popup kept it for itself. */
    public static void focusFirst(Object popup) {
        if (!(popup instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) popup;
        View focused = group.findFocus();
        if (focused == null || focused == group) {
            View first = firstRow(group);
            if (first != null) {
                first.requestFocus();
            }
        }
    }

    /** The first row of a popup or folder that can take the focus, never a text field; or null. */
    public static View firstRow(ViewGroup popup) {
        for (View v : popup.getFocusables(View.FOCUS_DOWN)) {
            if (v != popup && !(v instanceof EditText)) {
                return v;
            }
        }
        return null;
    }

    /**
     * The taskbar's own popup for an icon ({@code TaskbarPopupController.showForIcon}), from the
     * bar's context or its drawer's: the bar's holds its controllers, the drawer's reaches them
     * through its drag controller.
     */
    public static boolean showOnTaskbar(View icon, Object activity) throws Exception {
        Object controllers = Reflect.field(activity, "mControllers");
        if (!isControllers(controllers)) {
            controllers = null;
            Object drags = Reflect.call(activity, "getDragController");
            List<Field> fields = drags == null ? new ArrayList<>()
                    : Mirror.fields(drags.getClass());
            for (Field f : fields) {
                if (f.getType().getName().endsWith(CONTROLLERS)) {
                    controllers = Mirror.get(f, drags);
                    break;
                }
            }
        }
        Object popups = controllers == null ? null
                : Reflect.field(controllers, "taskbarPopupController");
        if (popups == null) {
            return false;
        }
        return popups.getClass().getMethod("showForIcon", View.class).invoke(popups, icon)
                != null;
    }

    private static boolean isControllers(Object o) {
        return o != null && o.getClass().getName().endsWith(CONTROLLERS);
    }

    /** Whether {@code v} is {@code group} or inside it. */
    public static boolean within(View v, ViewGroup group) {
        for (View p = v; p != null; p = parentOf(p)) {
            if (p == group) {
                return true;
            }
        }
        return false;
    }

    public static View parentOf(View v) {
        ViewParent p = v.getParent();
        return p instanceof View ? (View) p : null;
    }

    /**
     * ZUI's focus mark on every row and button of its popup. ZUI's text rows
     * ({@code deep_shortcut_zui}, {@code system_shortcut}) have plain shapes behind them, with no
     * focused state, and the controller moved through them unseen; its icon buttons - floating
     * window, split screen, app info and the rest - have a borderless ripple whose focused state
     * does not show either. The mark is a foreground seen only while the row is focused - which
     * only keys do - once per popup.
     */
    public static void markRows(Object popup) {
        if (!(popup instanceof ViewGroup) || ((View) popup).getTag(TAG_MARKED) != null) {
            return;
        }
        ViewGroup group = (ViewGroup) popup;
        group.setTag(TAG_MARKED, Boolean.TRUE);
        int marked = 0;
        for (View v : group.getFocusables(View.FOCUS_DOWN)) {
            if (v == group || v.getForeground() != null) {
                continue;
            }
            v.setForeground(mark(group.getContext()));
            marked++;
        }
        if (marked > 0 && L.once("ZuiFocus.marked")) {
            L.i("zui focus: ZUI's focus mark on " + marked + " row(s) of its menu");
        }
    }

    /**
     * ZUI's own mark for the item a keyboard or controller is on, as its drawers draw it
     * ({@code FocusIndicatorHelper}: {@code Utilities.getFocusRectColor}, a stroke of
     * {@code focused_background_stroke_width} rounded by {@code focused_background_radius}),
     * shown only in the focused state.
     */
    public static Drawable mark(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return mark(ctx, dimen(ctx, "focused_background_radius", 20 * density));
    }

    /** ZUI's mark with corners of its own, for an item smaller than ZUI's drawer cells. */
    public static Drawable mark(Context ctx, float radius) {
        float density = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(Math.round(dimen(ctx, "focused_background_stroke_width", 2 * density)),
                colour(ctx));
        StateListDrawable mark = new StateListDrawable();
        mark.addState(new int[]{android.R.attr.state_focused}, ring);
        return mark;
    }

    private static float dimen(Context ctx, String name, float otherwise) {
        int id = ctx.getResources().getIdentifier(name, "dimen", ctx.getPackageName());
        return id != 0 ? ctx.getResources().getDimension(id) : otherwise;
    }

    /** ZUI's focus colour ({@code Utilities.getFocusRectColor}), or its resource's. */
    private static int colour(Context ctx) {
        try {
            return (Integer) Class.forName("com.android.launcher3.Utilities", false,
                    ctx.getClassLoader()).getMethod("getFocusRectColor", Context.class)
                    .invoke(null, ctx);
        } catch (Throwable t) {
            int id = ctx.getResources().getIdentifier("focused_background", "color",
                    ctx.getPackageName());
            return id != 0 ? ctx.getColor(id) : 0xFF4D8EFF;
        }
    }
}
