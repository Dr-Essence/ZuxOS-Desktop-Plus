package com.zuxos.desktopplus.hook.taskbar;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.view.DragEvent;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * An app dragged out of ZUI's taskbar drawer, let go on the taskbar: pinned there.
 *
 * <p>ZUI's drawer drag is a system drag ({@code TaskbarDragController}: {@code startDragAndDrop}
 * with Android's activity clip), which SystemUI's drop window takes for a floating window or split
 * screen. That window covers the whole screen, the bar too, so a drop on the bar opened the app
 * instead (owner, 16:59). ZUI's clip already has a place for the part of the screen the shell
 * must leave alone - {@code DISALLOW_HIT_REGION}, which ZUI fills with its own transient bar - and
 * this puts our bar there. SystemUI's side ({@code ShellDropBar}) then keeps its window off that
 * region, so the drop reaches the bar's own window, whose strip ({@link TaskbarDrop}) pins the app
 * this remembered. Anywhere else the shell does as it always did.
 */
final class ZuiBarDrop {

    private static final String TASKBAR_CONTEXT =
            "com.android.launcher3.taskbar.BaseTaskbarContext";
    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";
    /** ZUI's {@code DragAndDropConstants.EXTRA_DISALLOW_HIT_REGION}, as SystemUI reads it. */
    private static final String DISALLOW = "DISALLOW_HIT_REGION";
    /** Android's {@code ClipDescription.MIMETYPE_APPLICATION_ACTIVITY}, the clip ZUI drags. */
    private static final String ACTIVITY = "application/vnd.android.activity";

    private static Class<?> sTaskbarContext;
    private static Method sLookup;

    /** The app ZUI's drag carries, from its start until it ends; null with none. Main thread. */
    private static Item sItem;
    private static int sDisplay = -1;
    private static boolean sPinned;

    private ZuiBarDrop() {
    }

    static void install(ClassLoader loader) {
        try {
            sTaskbarContext = Reflect.findClass(TASKBAR_CONTEXT, loader);
            Class<?> context = Reflect.findClass(CONTEXT, loader);
            if (sTaskbarContext == null || context == null) {
                L.w("zui bar drop: ZUI's taskbar context not found - a drop on the bar stays "
                        + "ZUI's");
                return;
            }
            sLookup = context.getMethod("lookupContext", Context.class);
            int hooked = XposedBridge.hookAllMethods(View.class, "startDragAndDrop",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            starting((View) param.thisObject, param.args);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sItem != null && !Boolean.TRUE.equals(param.getResult())) {
                                // Android did not start it: nothing to wait for.
                                sItem = null;
                            }
                        }
                    }).size();
            Health.hooked("taskbar: apps dragged onto the bar are pinned", hooked, Cfg::barDrop);
            L.i("zui bar drop: ZUI's drawer drags can be let go on the bar x" + hooked);
        } catch (Throwable t) {
            L.w("zui bar drop: not installed (" + t + ")");
        }
    }

    /** A drag about to start: when it is ZUI's from a taskbar, its app and our bar's place. */
    private static void starting(View source, Object[] args) {
        try {
            sItem = null;
            if (!Cfg.barDrop() || args.length < 1 || !(args[0] instanceof ClipData)) {
                return;
            }
            ClipData clip = (ClipData) args[0];
            ClipDescription description = clip.getDescription();
            if (description == null || !description.hasMimeType(ACTIVITY)
                    || !sTaskbarContext.isInstance(sLookup.invoke(null, source.getContext()))) {
                return;
            }
            int display = source.getDisplay() == null ? -1 : source.getDisplay().getDisplayId();
            Item item = IconInfo.itemOf(source.getContext(), source.getTag());
            RectF bar = TaskbarDrop.barOnScreen(display);
            Intent intent = clip.getItemCount() > 0 ? clip.getItemAt(0).getIntent() : null;
            if (item == null || bar == null || intent == null) {
                L.i("zui bar drop: a drag of " + (item == null ? "something that is not one app"
                        : item.pkg) + " left to ZUI" + (bar == null ? " (no bar showing)" : ""));
                return;
            }
            Object zui = intent.getExtras() == null ? null : intent.getExtras().get(DISALLOW);
            if (zui instanceof RectF) {
                // ZUI's own transient bar stays left alone as well.
                bar.union((RectF) zui);
            }
            intent.putExtra(DISALLOW, bar);
            sItem = item;
            sDisplay = display;
            sPinned = false;
            L.i("zui bar drop: ZUI's drag of " + item.pkg + " on display " + display
                    + ", the bar " + bar.toShortString() + " left to the bar"
                    + (zui instanceof RectF ? " (with ZUI's own " + ((RectF) zui).toShortString()
                    + ")" : ""));
        } catch (Throwable t) {
            sItem = null;
            L.d("zui bar drop: a drag left to ZUI (" + t + ")");
        }
    }

    /** The app of ZUI's drag under way on this display, or null when this drag is not one. */
    static Item itemFor(DragEvent event, int display) {
        ClipDescription description = event.getClipDescription();
        return sItem != null && display == sDisplay && description != null
                && description.hasMimeType(ACTIVITY) ? sItem : null;
    }

    /** ZUI's drag was let go on the bar and its app pinned. */
    static void pinned() {
        sPinned = true;
    }

    /** ZUI's drag is over: where it went, for the log. */
    static void ended(boolean taken) {
        if (sItem != null) {
            L.i("zui bar drop: ZUI's drag of " + sItem.pkg + " ended - " + (sPinned
                    ? "pinned to the bar" : taken ? "taken by the shell" : "taken by nothing"));
            sItem = null;
        }
    }
}
