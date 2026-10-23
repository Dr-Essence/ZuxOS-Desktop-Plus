package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.hook.IconPress;
import com.zuxos.desktopplus.hook.ZuiMenu;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Our folders pinned to the tablet's bar, as ZUI's own folder icons there: built and opened as
 * ZUI's {@code TaskbarView} builds and opens its own folders, from the same folders of ours as
 * the drawer's ({@link ZuiFolders}). Our row keeps the tap, the hold menu and the drag.
 */
public final class ZuiFolderBar {

    private ZuiFolderBar() {
    }

    /**
     * One of our folders pinned to the tablet's bar, as ZUI's own folder icon there: built as
     * ZUI's {@code TaskbarView} builds its folders ({@code inflateFolderAndIcon} in the bar's
     * context, name hidden), so its preview, its folder, the motion and blur of its opening and
     * ZUI's own click and popup on each app inside are ZUI's bar's. Our row keeps the tap, the
     * hold menu and the drag; a tap opens it through {@link #openOnBar}. Null when it cannot be
     * ZUI's - the bar then keeps our own folder icon and window.
     */
    public static View barIcon(Context ctx, Item folder) {
        try {
            Object activity = ZuiFolders.on() ? ZuiFolders.lookup(ctx) : null;
            if (!ZuiFolders.isBar(activity) || !(activity instanceof Context)) {
                return null;
            }
            Context bar = (Context) activity;
            if (ZuiFolders.sFiled.isEmpty()) {
                // ZUI has not read its app list yet: this bar is rebuilt once it has.
                if (!ZuiFolderCells.sBarWaiting) {
                    ZuiFolderCells.sBarWaiting = true;
                    L.i("zui folders: the bar's folders wait for ZUI's app list");
                }
                return null;
            }
            String content = ZuiFolders.contentOf(folder);
            Map<String, ZuiFolders.Built> mine = ZuiFolders.BUILT.get(bar);
            if (mine == null) {
                mine = new HashMap<>();
                ZuiFolders.BUILT.put(bar, mine);
            }
            ZuiFolders.Built built = mine.get(folder.id);
            View icon;
            if (built != null && (built.content.equals(content) || ZuiFolders.isOpen(built))) {
                icon = built.icon;
            } else {
                Object info = ZuiFolders.folderInfo(bar, folder);
                if (info == null) {
                    return null;
                }
                int layout = bar.getResources().getIdentifier("folder_icon", "layout",
                        bar.getPackageName());
                icon = (View) ZuiFolders.sInflate.invoke(null, layout, bar, null, info);
                icon.getClass().getMethod("setTextVisible", boolean.class).invoke(icon, false);
                mine.put(folder.id, new ZuiFolders.Built(icon, content));
            }
            if (icon.getParent() instanceof ViewGroup) {
                ((ViewGroup) icon.getParent()).removeView(icon);
            }
            icon.setOnClickListener(ZuiFolderBar::openOnBar);
            if (L.once("ZuiFolders.bar")) {
                L.i("zui folders: the bar's pinned folders are ZUI's own folder icons");
            }
            return icon;
        } catch (Throwable t) {
            L.w("zui folders: the bar keeps our folder for " + folder.label + " (" + t + ")");
            return null;
        }
    }

    /** Whether a view is one of our folders as ZUI's folder icon. */
    public static boolean isZuiFolder(View view) {
        return view != null && ZuiFolders.sInflate != null
                && ZuiFolders.sInflate.getDeclaringClass().isInstance(view);
    }

    /**
     * Opens one of our bar folders as ZUI's bar opens its own ({@code expandFolder}: the bar's
     * window full screen, then the folder, with ZUI's click and hold on every app inside). The
     * window is made full screen first and the folder asked for only once it has grown: our row
     * finds its place in the bigger window a layout later than ZUI's own icons, and a folder
     * opened before that would open from where the bar used to be.
     */
    public static void openOnBar(View icon) {
        IconPress.release(icon);
        boolean asked = ZuiMenu.withRoom(icon, (activity, dragLayer) -> {
            try {
                Method expand = null;
                for (Method m : activity.getClass().getMethods()) {
                    if (m.getName().equals("expandFolder") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isInstance(icon)) {
                        expand = m;
                    }
                }
                if (expand == null) {
                    L.w("zui folders: ZUI's bar cannot open folders here (no expandFolder)");
                    return false;
                }
                expand.invoke(activity, icon);
                // ZUI opens it on its next frame; should it not (the notification shade down),
                // the bar gets its size back.
                Object folder = icon.getClass().getMethod("getFolder").invoke(icon);
                dragLayer.post(() -> {
                    try {
                        if (!Boolean.TRUE.equals(folder.getClass().getMethod("isOpen")
                                .invoke(folder))) {
                            activity.getClass().getMethod("setTaskbarWindowFullscreen",
                                    boolean.class).invoke(activity, false);
                        }
                    } catch (Throwable ignored) {
                        // The bar is gone with its window.
                    }
                });
                L.i("zui folders: opened on the bar, as ZUI's bar opens its folders");
                return true;
            } catch (Throwable t) {
                L.w("zui folders: could not open on the bar (" + t + ")");
                return false;
            }
        });
        if (!asked) {
            L.d("zui folders: the bar's folder not opened - not in ZUI's bar");
        }
    }
}
