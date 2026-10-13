package com.zuxos.desktopplus.hook.taskbar;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.RectF;
import android.view.DragEvent;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.drawer.ZuiFolders;
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
    /**
     * The root of the window the drag started in: it stays attached for as long as the drag
     * does, where the icon itself may go with ZUI's drawer or folder closing.
     */
    private static View sSourceRoot;
    /** The home screen is behind the drawer: it takes the drag ({@link ZuiHomeDrop}). */
    private static boolean sHome;
    /** The bar's place on the screen for the drag under way. */
    private static RectF sBar;

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
            hooked += hookDrawerTakes(loader);
            hooked += hookShadowKeptHidden();
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
            // Only the drawer's window hands the drag to the home screen (its catcher); one out
            // of the bar's own window, a folder of the bar's, keeps the shell for the rest.
            boolean home = TaskbarTray.dragLayerOf(source.getRootView()) == null
                    && ZuiHomeDrop.homeBehind(source.getContext(), display);
            if (home) {
                intent.putExtra(ZuiHomeDrop.HOME_TAKES, true);
            }
            sItem = item;
            sDisplay = display;
            sPinned = false;
            sHome = home;
            sSource = source;
            sSourceRoot = source.getRootView();
            sBar = bar;
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

    /**
     * The app of ZUI's drag under way on this display, or null when this drag is not one. The
     * drag's end is known by our own state: Android sends {@code ACTION_DRAG_ENDED} without the
     * clip's description.
     */
    static Item itemFor(DragEvent event, int display) {
        if (sItem == null || display != sDisplay) {
            return null;
        }
        if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
            return sItem;
        }
        ClipDescription description = event.getClipDescription();
        return description != null && description.hasMimeType(ACTIVITY) ? sItem : null;
    }

    /**
     * Whether the bar on this display has given up its touches for ZUI's drawer drag, so a drop
     * over it reaches the drawer's window and our catcher there.
     */
    static boolean holdsBar(int display) {
        return sItem != null && sCatcher != null && display == sDisplay;
    }

    /**
     * While a system drag is under way the bar gives its touch region up ("let touches pass
     * through", Launcher3's {@code TaskbarInsetsController}), so a drop over the bar lands in
     * the window under it - ZUI's drawer, which ignores it. A view of ours in the drawer's window,
     * there for this drag only, takes a drop over the bar to the bar. It reaches past the window
     * on every side: the drawer's layer keeps the bar's height clear at its bottom, and a drop
     * over the bar came at the window's very edge (y 1904 of 1904, 15:43), outside a view the
     * layer's own size. The shell still covers the rest of the screen, so nothing else ever
     * reaches it.
     */
    private static void addCatcher(View root, RectF bar) {
        removeCatcher();
        if (!(root instanceof android.view.ViewGroup) || TaskbarTray.dragLayerOf(root) != null) {
            // The bar's own window: its strip does it.
            return;
        }
        View catcher = new View(root.getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.max(root.getWidth(), MeasureSpec.getSize(widthSpec));
                int height = Math.max(root.getHeight(), MeasureSpec.getSize(heightSpec));
                setMeasuredDimension(width * REACH, height * REACH);
                // Centred on the window: as far past each edge as the window is wide or high.
                setTranslationX(-width * (REACH - 1) / 2f);
                setTranslationY(-height * (REACH - 1) / 2f);
            }
        };
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
                    finish(null, false);
                    TaskbarDrop.relayoutBar(display);
                }
            }
        });
        ((android.view.ViewGroup) root).addView(catcher, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        sCatcher = catcher;
    }

    /** How many times the window's size the catcher spans, centred on it. */
    private static final int REACH = 3;

    /** Over the bar, its far edge included: the screen's edge is where a finger lets go of it. */
    private static boolean overBar(RectF bar, float x, float y) {
        return x >= bar.left && x <= bar.right && y >= bar.top && y <= bar.bottom + 1f;
    }

    private static boolean onCatcherDrag(View catcher, DragEvent event, RectF bar) {
        Item item = itemFor(event, sDisplay);
        if (item == null) {
            return false;
        }
        int[] at = new int[2];
        catcher.getLocationOnScreen(at);
        float x = at[0] + event.getX();
        float y = at[1] + event.getY();
        boolean over = overBar(bar, x, y);
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
                    hideShadow(catcher);
                    return true;
                }
                L.i("zui bar drop: let go in the drawer's window at " + Math.round(x) + ","
                        + Math.round(y) + (over ? ", over the bar" : ""));
                return false;
            case DragEvent.ACTION_DRAG_ENDED:
                TaskbarDrop.hintAt(sDisplay, -1f);
                int display = sDisplay;
                finish(event, event.getResult());
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

    /**
     * The drawer's window takes the drag where it is ours. ZUI makes that window untouchable for
     * as long as a system drag is under way ({@code TaskbarOverlayDragLayer.onComputeInternalInsets}
     * empties its touch region), so the shell under it gets the drag; Android then tells the
     * drawer's window only that a drag started, never where it moves or is let go. While ZUI's
     * drag of ours is under way the window keeps the bar's part, which the shell leaves alone - or,
     * with the home screen taking the drag and the shell stood aside, all of it.
     */
    private static int hookDrawerTakes(ClassLoader loader) {
        Class<?> layer = Reflect.findClass(
                "com.android.launcher3.taskbar.overlay.TaskbarOverlayDragLayer", loader);
        if (layer == null) {
            L.w("zui bar drop: the drawer's window not found - a drop on the bar stays lost");
            return 0;
        }
        return XposedBridge.hookAllMethods(layer, "onComputeInternalInsets",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View catcher = sCatcher;
                            if (sItem == null || catcher == null || param.args.length != 1
                                    || catcher.getRootView() != param.thisObject) {
                                return;
                            }
                            Object info = param.args[0];
                            if (sHome) {
                                Reflect.call(info, "setTouchableInsets", TOUCHABLE_INSETS_FRAME);
                                return;
                            }
                            RectF bar = sBar;
                            Object region = Reflect.field(info, "touchableRegion");
                            if (bar == null || !(region instanceof android.graphics.Region)) {
                                return;
                            }
                            int[] at = new int[2];
                            ((View) param.thisObject).getLocationOnScreen(at);
                            ((android.graphics.Region) region).union(new android.graphics.Rect(
                                    Math.round(bar.left) - at[0], Math.round(bar.top) - at[1],
                                    Math.round(bar.right) - at[0],
                                    Math.round(bar.bottom) - at[1]));
                            Reflect.call(info, "setTouchableInsets", TOUCHABLE_INSETS_REGION);
                        } catch (Throwable t) {
                            L.d("zui bar drop: the drawer's window kept ZUI's touch region ("
                                    + t + ")");
                        }
                    }
                }).size();
    }

    /** {@code ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME}. */
    private static final int TOUCHABLE_INSETS_FRAME = 0;
    /** {@code ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION}. */
    private static final int TOUCHABLE_INSETS_REGION = 3;

    private static void removeCatcher() {
        View catcher = sCatcher;
        sCatcher = null;
        if (catcher != null && catcher.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) catcher.getParent()).removeView(catcher);
        }
    }

    /**
     * ZUI's drag was let go on the bar and its app pinned: its card goes with the drop, emptied
     * through a view of the window the drag started in - the catcher, or, for a drag out of the
     * bar's own window, which has none, that window's root. An app dragged out of one of our
     * folders pinned on the bar moves out of the folder, as ZUI's "Remove from folder" takes it;
     * one from the taskbar's drawer stays a copy, as every drawer drag is.
     */
    static void pinned() {
        sPinned = true;
        View root = sSourceRoot;
        View inDrag = sCatcher != null ? sCatcher : root;
        if (inDrag != null) {
            hideShadow(inDrag);
        }
        View source = sSource;
        if (source == null || root == null || TaskbarTray.dragLayerOf(root) == null) {
            return;
        }
        Runnable out = ZuiFolders.removeAction(source.getTag());
        if (out != null) {
            // Once the drop is handled: ZUI's drag still holds the folder's icon until its end.
            root.post(out);
            L.i("zui bar drop: " + (sItem != null ? sItem.pkg : "the app")
                    + " moved out of its folder to the bar");
        }
    }

    /** A shadow of nothing: the system's card for ZUI's drag, emptied. */
    static final View.DragShadowBuilder NO_SHADOW = new View.DragShadowBuilder() {
        @Override
        public void onProvideShadowMetrics(Point size, Point touch) {
            size.set(1, 1);
            touch.set(0, 0);
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            // Nothing.
        }
    };
    /** ZUI's card is hidden for the rest of the drag under way. */
    private static boolean sShadowHidden;

    /**
     * Empties ZUI's drag card now, and keeps it empty for the rest of the drag: once the drop is
     * ours - on the bar, or on the home screen, whose own icon is dragged instead - nothing else
     * will ever take the card away. ZUI asks for the drag's surface back for its return animation
     * ({@code DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION} in its flags, 2816); Android then
     * hands it back only for a drop nobody took, and otherwise leaves it on screen until a
     * timeout, five seconds, where the finger let go (owner, 16:30). Empty, it is not seen.
     */
    static void hideShadow(View inDrawer) {
        sShadowHidden = true;
        try {
            inDrawer.updateDragShadow(NO_SHADOW);
        } catch (Throwable t) {
            L.d("zui bar drop: the card not emptied (" + t + ")");
        }
    }

    /**
     * ZUI animates its card from the icon to the card for 300 ms from the drag's start
     * ({@code TaskbarDragController.k0}, {@code updateDragShadow} on every frame), which drew it
     * again over an emptied one. While ours is hidden, every new shadow is the empty one.
     */
    private static int hookShadowKeptHidden() {
        return XposedBridge.hookAllMethods(View.class, "updateDragShadow", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (sShadowHidden && sItem != null && param.args.length == 1
                        && param.args[0] != NO_SHADOW) {
                    param.args[0] = NO_SHADOW;
                }
            }
        }).size();
    }

    /**
     * ZUI's drag is over, by its end or by the drawer's window going first: ZUX Home's drag ends
     * with it ({@code ended} is null when there is no event to hand on), then the outcome.
     */
    private static void finish(DragEvent ended, boolean taken) {
        ZuiHomeDrop.end(ended);
        ended(taken);
    }

    /** ZUI's drag is over: where it went, for the log. */
    static void ended(boolean taken) {
        if (sItem != null) {
            L.i("zui bar drop: ZUI's drag of " + sItem.pkg + " ended - " + (sPinned
                    ? "pinned to the bar" : sPlaced ? "placed on the home screen"
                    : taken ? "taken by the shell" : "taken by nothing"));
            sItem = null;
            sSource = null;
            sSourceRoot = null;
            sHome = false;
            sBar = null;
            sShadowHidden = false;
            sPlaced = false;
        }
    }
}
