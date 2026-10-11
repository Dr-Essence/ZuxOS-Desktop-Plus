package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * ZUI's own menus, carrying our actions - on the tablet, where everything looks like ZUI.
 *
 * <p>ZUI's compact popup, the one its taskbar shows for an app ({@code popup_container_zui},
 * {@code ZuiPopupContainerWithArrow.populateAndShowRowsZui}), with our rows as ZUI's own
 * shortcuts ({@link ZuiShortcuts}): up to five as rows, more as ZUI's icon grid, beside whatever
 * it stands on. With no app shortcuts asked for, ZUI makes no rows for them; it does the same for
 * its navigation buttons' menus ({@code ZuiNavPopupController}). Over the taskbar, opened as ZUI
 * opens its own popups there ({@code showPopupMenuForIcon}). Icons are drawn in ZUI's shortcut
 * colour, as its own are.
 */
public final class ZuiMenu {

    /** One row: what it says, its icon, what it does. */
    public interface Row {
        String title();

        Drawable icon();

        void run();

        /** An icon of its own - an app's shortcut's - drawn as it is, not in ZUI's colour. */
        default boolean ownIcon() {
            return false;
        }
    }

    private static final String OPEN = "com.android.launcher3.popup.PopupContainerWithArrow";
    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";

    private static boolean sSaidShow;

    private ZuiMenu() {
    }

    /**
     * ZUI's popup with these rows, at {@code anchor}; false where it cannot be built.
     *
     * <p>On the taskbar, as ZUI opens its own ({@code showPopupMenuForIcon}): the bar's window is
     * made full screen first and the popup shown once it has the room, so it opens above the bar.
     * ZUI shrinks the window back itself when the popup goes ({@code onDragEndOrViewRemoved}).
     */
    public static boolean show(View anchor, List<? extends Row> rows) {
        return show(anchor, () -> anchor, rows, null);
    }

    /**
     * The same, beside a view asked for only once the popup has its room ({@code anchor}, which
     * may be null for none): a point on the bar is where the bar is after its window grew.
     * {@code closed} runs when the popup goes, or at once if none is shown.
     */
    public static boolean show(View source, Supplier<View> anchor, List<? extends Row> rows,
            Runnable closed) {
        if (!ZuiShortcuts.ready()) {
            return false;
        }
        try {
            ClassLoader loader = source.getContext().getClassLoader();
            Class<?> contextClass = Class.forName(CONTEXT, false, loader);
            Object activity = contextClass.getMethod("lookupContext", Context.class)
                    .invoke(null, source.getContext());
            if (activity == null) {
                return false;
            }
            View dragLayer = (View) contextClass.getMethod("getDragLayer").invoke(activity);
            Method fullscreen = taskbarMethod(activity, "setTaskbarWindowFullscreen",
                    boolean.class);
            Method isFull = taskbarMethod(activity, "isTaskbarWindowFullscreen");
            if (fullscreen == null || isFull == null
                    || Boolean.TRUE.equals(isFull.invoke(activity))) {
                return open(anchor, rows, activity, dragLayer, loader, closed);
            }
            fullscreen.invoke(activity, true);
            Runnable[] once = new Runnable[1];
            View.OnLayoutChangeListener[] laid = new View.OnLayoutChangeListener[1];
            once[0] = () -> {
                dragLayer.removeOnLayoutChangeListener(laid[0]);
                dragLayer.removeCallbacks(once[0]);
                if (!open(anchor, rows, activity, dragLayer, loader, closed)) {
                    // Nothing shown, so nothing of ZUI's will give the bar its size back.
                    try {
                        fullscreen.invoke(activity, false);
                    } catch (Throwable ignored) {
                        // The bar is gone with its window.
                    }
                }
            };
            laid[0] = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (b - t != ob - ot) {
                    v.post(once[0]);
                }
            };
            dragLayer.addOnLayoutChangeListener(laid[0]);
            // Should the window be laid out without a change of size, the popup still comes.
            dragLayer.postDelayed(once[0], ROOM_WAIT_MS);
            return true;
        } catch (Throwable t) {
            L.w("zui menu: ZUI's popup not built (" + t + ")");
            return false;
        }
    }

    /** How long a popup over the taskbar waits for the bar's window to grow, at most. */
    private static final long ROOM_WAIT_MS = 250L;

    private static boolean open(Supplier<View> anchorWhenReady, List<? extends Row> rows,
            Object activity, View dragLayer, ClassLoader loader, Runnable closed) {
        boolean shown = false;
        try {
            View anchor = anchorWhenReady.get();
            if (anchor == null) {
                return false;
            }
            Class<?> contextClass = Class.forName(CONTEXT, false, loader);
            // One popup at a time, as ZUI's own showForIcon has it.
            if (Class.forName(OPEN, false, loader).getMethod("getOpen", contextClass)
                    .invoke(null, activity) != null) {
                return false;
            }
            Context ctx = anchor.getContext();
            int layout = ctx.getResources().getIdentifier("popup_container_zui", "layout",
                    ctx.getPackageName());
            if (layout == 0) {
                L.w("zui menu: ZUI's popup layout not found");
                return false;
            }
            LayoutInflater inflater = (LayoutInflater) contextClass
                    .getMethod("getLayoutInflater").invoke(activity);
            View popup = inflater.inflate(layout, (ViewGroup) dragLayer, false);
            int colour = ZuiLook.shortcutIcon(popup.getContext());
            int size = Math.round(24 * ctx.getResources().getDisplayMetrics().density);
            List<Object> shortcuts = new ArrayList<>();
            for (Row row : rows) {
                Icon icon = ZuiShortcuts.iconOf(row.icon(), size);
                if (!row.ownIcon()) {
                    icon.setTint(colour);
                }
                shortcuts.add(ZuiShortcuts.make(ctx, row.title(), icon, row::run, activity, null,
                        anchor));
            }
            popup.getClass().getMethod("populateAndShowRowsZui", View.class, int.class,
                    List.class, List.class).invoke(popup, anchor, 0, Collections.emptyList(),
                    shortcuts);
            if (popup.getParent() == null) {
                return false;
            }
            shown = true;
            popup.requestFocus();
            if (closed != null) {
                popup.getClass().getMethod("addOnCloseCallback", Runnable.class)
                        .invoke(popup, closed);
            }
            focusOverTheBar(activity, popup, dragLayer);
            if (!sSaidShow) {
                sSaidShow = true;
                L.i("zui menu: ZUI's own compact popup with our rows");
            }
            return true;
        } catch (Throwable t) {
            L.w("zui menu: ZUI's popup not built (" + t + ")");
            return false;
        } finally {
            if (!shown && closed != null) {
                closed.run();
            }
        }
    }

    /** A public method of the taskbar's context, or null where the context is not the bar's. */
    private static Method taskbarMethod(Object activity, String name, Class<?>... args) {
        try {
            return activity.getClass().getMethod(name, args);
        } catch (NoSuchMethodException notTheBar) {
            return null;
        }
    }

    /**
     * Over the taskbar, the bar's window takes the keyboard's focus while a popup is up and gives
     * it back after, as ZUI's {@code showForIcon} has it do.
     */
    private static void focusOverTheBar(Object activity, Object popup, View dragLayer) {
        if (popup == null) {
            return;
        }
        Method focus = taskbarMethod(activity, "onPopupVisibilityChanged", boolean.class);
        if (focus == null) {
            // Not over the taskbar: the drawer and the home screen keep their own focus.
            return;
        }
        try {
            focus.invoke(activity, true);
            popup.getClass().getMethod("addOnCloseCallback", Runnable.class).invoke(popup,
                    (Runnable) () -> dragLayer.post(() -> {
                        try {
                            focus.invoke(activity, false);
                        } catch (Throwable ignored) {
                            // The bar is gone with its window.
                        }
                    }));
        } catch (Throwable t) {
            L.d("zui menu: focus not given to the bar (" + t + ")");
        }
    }
}
