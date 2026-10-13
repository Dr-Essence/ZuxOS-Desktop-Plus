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
 * An app dragged out of ZUI's taskbar drawer, let go on the taskbar: pinned there; with the home
 * screen behind, let go on the home screen: placed there ({@link ZuiHomeDrop}).
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
    private static int sDepth;
    /** Our catcher in the drawer's own window for the drag under way, or null. */
    private static View sCatcher;
    /** The drawer's icon the drag started from, for ZUX Home's drag; held only for the drag. */
    private static View sSource;
    /** The home screen is behind the drawer: it takes the drag ({@link ZuiHomeDrop}). */
    private static boolean sHome;

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
                            // Once per drag: the public call goes through a second one.
                            if (sDepth++ == 0) {
                                starting((View) param.thisObject, param.args);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (--sDepth == 0 && sItem != null
                                    && !Boolean.TRUE.equals(param.getResult())) {
                                // Android did not start it: nothing to wait for.
                                sItem = null;
                                removeCatcher();
                            }
                        }
                    }).size();
            hooked += hookBarYields(loader);
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
            boolean home = ZuiHomeDrop.homeBehind(source.getContext(), display);
            if (home) {
                intent.putExtra(ZuiHomeDrop.HOME_TAKES, true);
            }
            sItem = item;
            sDisplay = display;
            sPinned = false;
            sHome = home;
            sSource = source;
            addCatcher(source.getRootView(), bar);
            TaskbarDrop.relayoutBar(display);
            L.i("zui bar drop: ZUI's drag of " + item.pkg + " on display " + display
                    + ", the bar " + bar.toShortString() + " left to the bar"
                    + (zui instanceof RectF ? " (with ZUI's own " + ((RectF) zui).toShortString()
                    + ")" : "") + (home ? "; the home screen behind takes it" : ""));
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

    /**
     * While a system drag is under way the bar gives its touch region up ("let touches pass
     * through", Launcher3's {@code TaskbarInsetsController}), so a drop over the bar lands in
     * the window under it - ZUI's drawer, which ignores it. A view of ours over the whole of the
     * drawer's window, there for this drag only, takes a drop over the bar to the bar. The shell
     * still covers the rest of the screen, so nothing else ever reaches it.
     */
    private static void addCatcher(View root, RectF bar) {
        removeCatcher();
        if (!(root instanceof android.view.ViewGroup) || TaskbarTray.dragLayerOf(root) != null) {
            // The bar's own window: its strip does it.
            return;
        }
        View catcher = new View(root.getContext());
        catcher.setOnDragListener((v, event) -> onCatcherDrag(v, event, bar));
        int display = sDisplay;
        catcher.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // The drawer's window gone before the drag's end reached it: the drag is over
                // for us, and the bar takes its touches back.
                if (sCatcher == v) {
                    sCatcher = null;
                    ended(false);
                    TaskbarDrop.relayoutBar(display);
                }
            }
        });
        ((android.view.ViewGroup) root).addView(catcher, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        sCatcher = catcher;
    }

    private static boolean onCatcherDrag(View catcher, DragEvent event, RectF bar) {
        Item item = itemFor(event, sDisplay);
        if (item == null) {
            return false;
        }
        int[] at = new int[2];
        catcher.getLocationOnScreen(at);
        float x = at[0] + event.getX();
        boolean over = bar.contains(x, at[1] + event.getY());
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (sHome && sSource != null) {
                    ZuiHomeDrop.begin(catcher, sSource, item, event);
                }
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                TaskbarDrop.hintAt(sDisplay, over ? x : -1f);
                ZuiHomeDrop.forward(event);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                TaskbarDrop.hintAt(sDisplay, -1f);
                return true;
            case DragEvent.ACTION_DROP:
                TaskbarDrop.hintAt(sDisplay, -1f);
                if (over) {
                    ZuiHomeDrop.cancel();
                    if (TaskbarDrop.pinAt(sDisplay, item, x)) {
                        pinned();
                        return true;
                    }
                }
                if (ZuiHomeDrop.active() && ZuiHomeDrop.forward(event)) {
                    sPlaced = true;
                    return true;
                }
                L.i("zui bar drop: let go in the drawer's window at " + Math.round(x) + ","
                        + Math.round(at[1] + event.getY()) + (over ? ", over the bar" : ""));
                return false;
            case DragEvent.ACTION_DRAG_ENDED:
                TaskbarDrop.hintAt(sDisplay, -1f);
                ZuiHomeDrop.end(event);
                ended(event.getResult());
                int display = sDisplay;
                catcher.post(() -> {
                    removeCatcher();
                    TaskbarDrop.relayoutBar(display);
                });
                return true;
            default:
                return true;
        }
    }

    /** The drop went to the home screen, through ZUI's own drag. */
    private static boolean sPlaced;

    /**
     * While ZUI's drawer drag is under way the bar lets touches through, so a drop over it reaches
     * the drawer's window - the one Android tells of the drag. Launcher3 does the same for the
     * bar's own system drags ({@code TaskbarInsetsController.updateInsetsTouchability}: "let
     * touches pass through"); the drawer's drag is its own controller's, which that rule misses.
     */
    private static int hookBarYields(ClassLoader loader) {
        Class<?> insets = Reflect.findClass("com.android.launcher3.taskbar.TaskbarInsetsController",
                loader);
        if (insets == null) {
            L.w("zui bar drop: the bar's touch region not found - a drop on the bar stays lost");
            return 0;
        }
        return XposedBridge.hookAllMethods(insets, "updateInsetsTouchability",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // Only while our catcher is there to take the drop - and to be
                            // told the drag ended, which gives the bar its touches back.
                            if (sItem == null || sCatcher == null || param.args.length != 1) {
                                return;
                            }
                            Object bar = Reflect.call(param.thisObject, "getContext");
                            if (!(bar instanceof Context) || ((Context) bar).getDisplay() == null
                                    || ((Context) bar).getDisplay().getDisplayId() != sDisplay) {
                                return;
                            }
                            Object info = param.args[0];
                            Object region = Reflect.field(info, "touchableRegion");
                            if (region instanceof android.graphics.Region) {
                                ((android.graphics.Region) region).setEmpty();
                                Reflect.call(info, "setTouchableInsets", TOUCHABLE_INSETS_REGION);
                            }
                        } catch (Throwable t) {
                            L.d("zui bar drop: the bar kept its touches (" + t + ")");
                        }
                    }
                }).size();
    }

    /** {@code ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION}. */
    private static final int TOUCHABLE_INSETS_REGION = 3;

    private static void removeCatcher() {
        View catcher = sCatcher;
        sCatcher = null;
        if (catcher != null && catcher.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) catcher.getParent()).removeView(catcher);
        }
    }

    /** ZUI's drag was let go on the bar and its app pinned. */
    static void pinned() {
        sPinned = true;
    }

    /** ZUI's drag is over: where it went, for the log. */
    static void ended(boolean taken) {
        if (sItem != null) {
            L.i("zui bar drop: ZUI's drag of " + sItem.pkg + " ended - " + (sPinned
                    ? "pinned to the bar" : sPlaced ? "placed on the home screen"
                    : taken ? "taken by the shell" : "taken by nothing"));
            sItem = null;
            sSource = null;
            sHome = false;
            sPlaced = false;
        }
    }
}
