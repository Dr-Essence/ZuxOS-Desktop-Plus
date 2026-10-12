package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

/**
 * ZUI's own popup look, from ZUX Home's resources: what its app menus are drawn with, light or
 * dark as ZUI itself is. Where a resource is missing, a plain value close to ZUI's.
 */
public final class ZuiLook {

    private ZuiLook() {
    }

    /** ZUI's popup panel ({@code ic_popupcontainer_bg_new}): its fill, hairline and corners. */
    public static Drawable popupBackground(Context ctx) {
        int id = ctx.getResources().getIdentifier("ic_popupcontainer_bg_new", "drawable",
                ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    /** The text of ZUI's popup rows ({@code popup_container_text_color}). */
    public static int popupText(Context ctx) {
        return colour(ctx, "popup_container_text_color", 0xFF1A1A1A);
    }

    /** The colour ZUI draws its shortcut icons in ({@code ic_system_shortcut_solid}). */
    public static int shortcutIcon(Context ctx) {
        return colour(ctx, "ic_system_shortcut_solid", 0xFF666666);
    }

    /**
     * One of our row titles in the owner's language, where ZUI - or Android, for Open - has
     * the same word: ZUI's own rows are in it, ours were in English beside them. Any other
     * title as it is.
     */
    public static String label(Context ctx, String title) {
        try {
            if (title.equals("Open")) {
                // Android's own "Open", from its app chooser.
                int id = Resources.getSystem().getIdentifier("whichViewApplicationLabel",
                        "string", "android");
                return id != 0 ? Resources.getSystem().getString(id) : title;
            }
            String zui = title.equals("Close") ? "recent_task_option_close"
                    : title.equals("New window") ? "new_window_option_taskbar"
                    : title.equals("App info") ? "applications_settings"
                    : title.equals("Select") ? "listview_x_title_no_item_selected" : null;
            int id = zui == null ? 0 : ctx.getResources().getIdentifier(zui, "string",
                    ctx.getPackageName());
            return id != 0 ? ctx.getString(id) : title;
        } catch (Throwable t) {
            return title;
        }
    }

    private static int colour(Context ctx, String name, int fallback) {
        int id = ctx.getResources().getIdentifier(name, "color", ctx.getPackageName());
        return id != 0 ? ctx.getColor(id) : fallback;
    }
}
