package com.zuxos.desktopplus.hook.taskbar;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Picture;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.Display;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.Tasks;
import com.zuxos.desktopplus.hook.ZuiApps;
import com.zuxos.desktopplus.hook.drawer.ZuiFolderModel;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * An app dragged out of the taskbar's drawer over the home screen lands on the home screen, as
 * one dragged out of ZUX Home's own drawer does.
 *
 * <p>Android hands an app's drag only to the window it started in and to windows that take
 * every drag ({@code DragState.isValidDropTarget}: "application extras"), so ZUX Home's home
 * screen is never told of the taskbar drawer's drag. Its own way in for drags from elsewhere is
 * {@code BaseItemDragListener}: a drag of its own, started at the system drag's point
 * ({@code DragOptions.simulatedDndStartPoint}), fed every {@code DragEvent}
 * ({@code DragController.onDragEvent}). The drawer's window is told of the drag, and it is in the
 * same process on the same screen: it starts ZUX Home's drag there and hands it every event, moved
 * from its own coordinates to the home screen's. From then on it is ZUI's drag, from ZUI's drawer -
 * the home screen opening as for its own drawer ({@code SPRING_LOADED}), its reorder, folders,
 * drop animation and database - with the system's shadow hidden so only ZUI's own icon is dragged.
 * SystemUI stands aside for it (the clip says so, {@code ShellDropBar}).
 *
 * <p>A pinned app dragged off the tablet's bar with the home screen behind it goes the same way
 * ({@link #armForPin}): its drag reaches ZUX Home's windows, a catcher in the home screen's drag
 * layer starts ZUI's drag the moment the finger leaves the bar - from ZUI's own stand-in for an
 * icon from elsewhere ({@code DraggableView.ofType}, as {@code PendingItemDragHelper} does) - and
 * gives it up when the finger goes back over the bar, where the pin moves along it as before.
 * Placed on the home screen, the app leaves the bar (drag row 2: bar to home moves it).
 */
final class ZuiHomeDrop {

    /** The clip's word to SystemUI that the home screen takes this drag. */
    static final String HOME_TAKES = "com.zuxos.desktopplus.HOME_TAKES";

    private static final String OPTIONS = "com.android.launcher3.dragndrop.DragOptions";
    private static final String DRAGGABLE = "com.android.launcher3.dragndrop.DraggableView";
    private static final String DRAG_SOURCE = "com.android.launcher3.DragSource";
    /** ZUI's {@code DraggableView.DRAGGABLE_ICON}. */
    private static final int DRAGGABLE_ICON = 0;
    private static final int FULLSCREEN = 1;

    /** ZUX Home's drag controller carrying the drag under way, or null. Main thread. */
    private static Object sController;
    private static View sFaded;
    /** From the catcher's coordinates to the home screen's, for the drag under way. */
    private static int sDx;
    private static int sDy;
    private static Method sObtain;
    private static java.lang.reflect.Field sX;
    private static java.lang.reflect.Field sY;

    private ZuiHomeDrop() {
    }

    /** Whether the home screen is what is showing on this screen, full screen and in front. */
    static boolean homeBehind(Context ctx, int display) {
        if (!Cfg.homeDrop()) {
            return false;
        }
        List<android.app.ActivityManager.RunningTaskInfo> tasks = TaskbarApps.tasksOn(ctx, display);
        if (tasks.isEmpty() || !ctx.getPackageName().equals(Tasks.packageOf(tasks.get(0)))) {
            return false;
        }
        // TaskInfo.configuration is not in the public SDK: read by name.
        Object config = Reflect.field(Reflect.field(tasks.get(0), "configuration"),
                "windowConfiguration");
        Object mode = config == null ? null : Reflect.call(config, "getWindowingMode");
        return !(mode instanceof Integer) || (Integer) mode == FULLSCREEN;
    }

    /**
     * ZUX Home's own drag of this app, started at the system drag's start; true when it began. The
     * catcher is the drawer's window's view that is told of the system drag.
     */
    static boolean begin(View catcher, View source, Item item, DragEvent started) {
        try {
            ClassLoader loader = catcher.getContext().getClassLoader();
            if (!Class.forName(DRAGGABLE, false, loader).isInstance(source)) {
                L.i("zui home drop: no home screen to hand the drag to");
                return false;
            }
            // From ZUI's drawer: ZUI opens the home screen for it as for its own (SPRING_LOADED).
            Object[] app = appOf(loader, item, IconInfo.userOf(source.getTag()));
            if (app == null || !startZuiDrag(catcher, source, null, app[0], (Drawable) app[1],
                    started)) {
                return false;
            }
            // Only ZUI's own icon is dragged now: the system's card is hidden, the drawer too.
            ZuiBarDrop.hideShadow(catcher);
            View drawer = catcher.getRootView();
            drawer.animate().alpha(0f).setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                    .start();
            sFaded = drawer;
            L.i("zui home drop: the drag of " + item.pkg + " handed to the home screen, moved by "
                    + sDx + "," + sDy);
            return true;
        } catch (Throwable t) {
            L.w("zui home drop: not handed to the home screen (" + t + ")");
            sController = null;
            return false;
        }
    }

    /** ZUI's drawer entry for the app and its icon, or null where ZUI has none. */
    private static Object[] appOf(ClassLoader loader, Item item, UserHandle user) {
        Object launcher = ZuiApps.launcher(loader);
        Object app = launcher == null ? null
                : ZuiApps.appInfo(launcher, new ComponentName(item.pkg, item.cls), user);
        Object icon = app == null ? null : Reflect.call(app, "newIcon", launcher);
        if (!(icon instanceof Drawable)) {
            L.i("zui home drop: ZUI's app not found for " + item.pkg);
            return null;
        }
        return new Object[]{app, icon};
    }

    /**
     * ZUX Home's {@code DragController.startDrag} of {@code info} - an app of ZUI's drawer, or a
     * new folder - with {@code icon}, at the event's point, from {@code draggable} for
     * {@code dragSource} (null: ZUI's drawer); true when it began.
     */
    private static boolean startZuiDrag(View catcher, Object draggable, Object dragSource,
            Object info, Drawable icon, DragEvent at) throws ReflectiveOperationException {
        ClassLoader loader = catcher.getContext().getClassLoader();
        Object launcher = ZuiApps.launcher(loader);
        if (launcher == null) {
            L.i("zui home drop: no home screen to hand the drag to");
            return false;
        }
        View homeLayer = (View) Reflect.call(launcher, "getDragLayer");
        int[] home = new int[2];
        int[] here = new int[2];
        homeLayer.getLocationOnScreen(home);
        catcher.getLocationOnScreen(here);
        // The drawer's window and the home screen's need not share their origin: every event is
        // moved from the catcher's place to the home screen's.
        sDx = here[0] - home[0];
        sDy = here[1] - home[1];
        Object apps = Reflect.call(launcher, "getAppsView");
        Object controller = Reflect.call(launcher, "getDragController");
        Method start = startDrag(controller);
        if (controller == null || start == null) {
            L.i("zui home drop: ZUI's drag not found");
            return false;
        }
        Object options = Class.forName(OPTIONS, false, loader).getConstructor().newInstance();
        Point point = new Point(Math.round(at.getX() + sDx), Math.round(at.getY() + sDy));
        options.getClass().getField("simulatedDndStartPoint").set(options, point);
        int half = Math.max(1, icon.getIntrinsicWidth()) / 2;
        start.invoke(controller, icon, draggable, point.x - half, point.y - half,
                dragSource != null ? dragSource : apps, info, null, 1f, 1f, options);
        sController = controller;
        return true;
    }

    /** ZUX Home's {@code DragController.startDrag(Drawable, DraggableView, ...)}. */
    private static Method startDrag(Object controller) {
        for (Class<?> c = controller == null ? null : controller.getClass(); c != null;
                c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("startDrag") && m.getParameterCount() == 10
                        && m.getParameterTypes()[0] == Drawable.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** Whether a drag of ours is with the home screen now. */
    static boolean active() {
        return sController != null;
    }

    /** One more event of the system drag, for ZUI's drag: it moves, drops or ends with it. */
    static boolean forward(DragEvent event) {
        Object controller = sController;
        if (controller == null) {
            return false;
        }
        DragEvent moved = moved(event);
        try {
            return Boolean.TRUE.equals(Reflect.call(controller, "onDragEvent", moved));
        } finally {
            if (moved != event) {
                // DragEvent.recycle is not in the public SDK.
                Reflect.call(moved, "recycle");
            }
        }
    }

    /**
     * The event where the home screen has it: a copy moved by the two windows' offset
     * ({@code DragEvent.obtain(DragEvent)}, its {@code mX}/{@code mY}), or the event itself when
     * they share their origin or the copy cannot be made.
     */
    private static DragEvent moved(DragEvent event) {
        if (sDx == 0 && sDy == 0) {
            return event;
        }
        try {
            if (sObtain == null) {
                sObtain = DragEvent.class.getDeclaredMethod("obtain", DragEvent.class);
                sX = DragEvent.class.getDeclaredField("mX");
                sY = DragEvent.class.getDeclaredField("mY");
                sObtain.setAccessible(true);
                sX.setAccessible(true);
                sY.setAccessible(true);
            }
            DragEvent copy = (DragEvent) sObtain.invoke(null, event);
            sX.setFloat(copy, event.getX() + sDx);
            sY.setFloat(copy, event.getY() + sDy);
            return copy;
        } catch (Throwable t) {
            L.d("zui home drop: an event not moved (" + t + ")");
            return event;
        }
    }

    /** Let go over the bar instead: ZUI's drag gives up, the bar pins the app. */
    static void cancel() {
        Object controller = sController;
        if (controller != null && Boolean.TRUE.equals(Reflect.call(controller, "isDragging"))) {
            Reflect.call(controller, "cancelDrag");
        }
    }

    /**
     * The system drag is over: ZUI's ends with it if it has not - its end handed on, or, with none
     * ({@code ended} null: the drawer's window went first), cancelled - and the drawer comes back.
     * Left going, the home screen stayed open for a drop.
     */
    static void end(DragEvent ended) {
        Object controller = sController;
        if (controller != null && Boolean.TRUE.equals(Reflect.call(controller, "isDragging"))) {
            if (ended != null) {
                forward(ended);
            } else {
                Reflect.call(controller, "cancelDrag");
            }
        }
        sController = null;
        View drawer = sFaded;
        sFaded = null;
        if (drawer != null) {
            drawer.animate().cancel();
            drawer.setAlpha(1f);
        }
    }

    // --- a pin off the bar ---------------------------------------------------

    /** The pin dragged off the bar toward the home screen, or null. Main thread. */
    private static Item sPin;
    /** Our catcher in the home screen's drag layer, for the pin's drag. */
    private static View sPinCatcher;
    /** The bar's window, where the pin's drag started: its card is shown or emptied there. */
    private static View sPinBar;
    private static View.DragShadowBuilder sPinCard;
    /** ZUI took the pin's app: it was placed on the home screen. */
    private static boolean sPinPlaced;
    /** ZUI's drag of the pin was started at least once. */
    private static boolean sPinHanded;
    /** The pin's own icon on the bar, drawn for a folder's drag. */
    private static java.lang.ref.WeakReference<View> sPinView;
    /** ZUI's drag could not be started for this pin: not tried again until its next drag. */
    private static boolean sPinRefused;

    /**
     * A pin about to be dragged off the bar: with the home screen full screen behind the tablet's
     * bar, a catcher goes into the home screen's drag layer and the drag may go there (true: it is
     * to start across ZUX Home's windows). Anywhere else the pin moves along the bar only.
     */
    static boolean armForPin(View pin, Item item, View.DragShadowBuilder card) {
        disarmPin();
        try {
            int display = TaskbarTray.displayIdOf(pin);
            boolean app = item.type == Item.TYPE_APP && item.pkg != null;
            boolean folder = item.type == Item.TYPE_FOLDER && !item.children.isEmpty();
            if (display != Display.DEFAULT_DISPLAY || !(app || folder)
                    || !homeBehind(pin.getContext(), display)) {
                return false;
            }
            Object launcher = ZuiApps.launcher(pin.getContext().getClassLoader());
            Object layer = launcher == null ? null : Reflect.call(launcher, "getDragLayer");
            if (!(layer instanceof ViewGroup) || ((View) layer).getDisplay() == null
                    || ((View) layer).getDisplay().getDisplayId() != display) {
                L.i("zui home drop: no home screen behind the bar for " + item.pkg);
                return false;
            }
            ViewGroup home = (ViewGroup) layer;
            View catcher = new View(home.getContext());
            catcher.setOnDragListener(ZuiHomeDrop::onPinDrag);
            catcher.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    // The home screen gone mid-drag: ZUI's drag with it, the pin stays.
                    if (sPinCatcher == v) {
                        pinEnded(null);
                    }
                }
            });
            home.addView(catcher, fullLayer(home));
            sPin = item;
            sPinCatcher = catcher;
            sPinBar = pin.getRootView();
            sPinView = new java.lang.ref.WeakReference<>(pin);
            sPinCard = card;
            return true;
        } catch (Throwable t) {
            L.d("zui home drop: the pin stays on the bar (" + t + ")");
            disarmPin();
            return false;
        }
    }

    /**
     * All of the drag layer, its insets too ({@code InsettableFrameLayout.LayoutParams
     * .ignoreInsets}): a drop near the screen's top edge is still the home screen's.
     */
    private static ViewGroup.LayoutParams fullLayer(ViewGroup layer) {
        ViewGroup.LayoutParams full = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        try {
            Object lp = layer.getClass()
                    .getMethod("generateLayoutParams", ViewGroup.LayoutParams.class)
                    .invoke(layer, full);
            lp.getClass().getField("ignoreInsets").setBoolean(lp, true);
            return (ViewGroup.LayoutParams) lp;
        } catch (Throwable t) {
            L.d("zui home drop: the catcher keeps the home screen's insets (" + t + ")");
            return full;
        }
    }

    /** The pin's drag did not start after all: the catcher goes. */
    static void disarmPin() {
        View catcher = sPinCatcher;
        sPin = null;
        sPinCatcher = null;
        sPinBar = null;
        sPinCard = null;
        sPinPlaced = false;
        sPinHanded = false;
        sPinRefused = false;
        if (catcher != null) {
            remove(catcher);
        }
    }

    private static boolean onPinDrag(View catcher, DragEvent event) {
        Item pin = sPin;
        if (pin == null || catcher != sPinCatcher) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (sController == null && !sPinRefused) {
                    pinToHome(catcher, pin, event);
                }
                forward(event);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                // Back over the bar: ZUI's drag gives up and the pin's card is back, so the pin
                // moves along the bar as before.
                if (sController != null) {
                    forward(event);
                    cancel();
                    sController = null;
                    card(sPinCard);
                }
                return true;
            case DragEvent.ACTION_DROP:
                return sController != null && forward(event);
            case DragEvent.ACTION_DRAG_ENDED:
                pinEnded(event);
                return true;
            default:
                return true;
        }
    }

    /** The finger has left the bar for the home screen: ZUI's own drag of the pin's app. */
    private static void pinToHome(View catcher, Item pin, DragEvent at) {
        try {
            ClassLoader loader = catcher.getContext().getClassLoader();
            Object draggable = Class.forName(DRAGGABLE, false, loader)
                    .getMethod("ofType", int.class).invoke(null, DRAGGABLE_ICON);
            Object[] what = pin.type == Item.TYPE_FOLDER ? folderOf(catcher, pin)
                    : appOf(loader, pin, userOf(catcher.getContext(), pin.userSerial));
            if (what != null && startZuiDrag(catcher, draggable, pinSource(loader), what[0],
                    (Drawable) what[1], at)) {
                sPinHanded = true;
                card(ZuiBarDrop.NO_SHADOW);
                L.i("zui home drop: the pin " + name(pin) + " handed to the home screen");
                return;
            }
        } catch (Throwable t) {
            L.w("zui home drop: the pin " + name(pin) + " not handed to the home screen (" + t
                    + ")");
            sController = null;
        }
        sPinRefused = true;
    }

    /**
     * A folder pinned to the bar, for the home screen: ZUI's new folder for it, which ZUI fills
     * once placed ({@code ZuiFolderHome.forHome}), dragged as the bar draws it. Drawn as ZUI's
     * {@code BitmapRenderer} draws, through a {@code Picture} into a hardware bitmap: ZUI's icons
     * are hardware bitmaps, which a software canvas cannot draw (ZUI's folder skips them), and the
     * folder was dragged as an empty box.
     */
    private static Object[] folderOf(View catcher, Item pin) {
        View icon = sPinView != null ? sPinView.get() : null;
        Object launcher = ZuiApps.launcher(catcher.getContext().getClassLoader());
        if (icon == null || icon.getWidth() <= 0 || !(launcher instanceof Context)) {
            return null;
        }
        Object info = ZuiFolderModel.forHome((Context) launcher, pin);
        if (info == null) {
            return null;
        }
        Picture picture = new Picture();
        icon.draw(picture.beginRecording(icon.getWidth(), icon.getHeight()));
        picture.endRecording();
        return new Object[]{info, new BitmapDrawable(icon.getResources(),
                Bitmap.createBitmap(picture))};
    }

    private static String name(Item pin) {
        return pin.pkg != null ? pin.pkg : pin.label;
    }

    /** ZUI's {@code DragSource} for the pin: it is told whether ZUI placed the app. */
    private static Object pinSource(ClassLoader loader) throws ClassNotFoundException {
        return Proxy.newProxyInstance(loader,
                new Class<?>[]{Class.forName(DRAG_SOURCE, false, loader)},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onDropCompleted":
                            if (args != null && args.length == 3
                                    && Boolean.TRUE.equals(args[2])) {
                                sPinPlaced = true;
                            }
                            return null;
                        case "equals":
                            return args != null && args.length == 1 && proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "ZuxDesktopPlus bar pin";
                        default:
                            return null;
                    }
                });
    }

    private static UserHandle userOf(Context ctx, long serial) {
        UserManager users = ctx.getSystemService(UserManager.class);
        UserHandle user = users == null ? null : users.getUserForSerialNumber(serial);
        return user != null ? user : Process.myUserHandle();
    }

    /** The pin's card on screen: shown, or emptied while ZUI's own icon is dragged. */
    private static void card(View.DragShadowBuilder card) {
        View bar = sPinBar;
        if (bar == null || card == null) {
            return;
        }
        try {
            bar.updateDragShadow(card);
        } catch (Throwable t) {
            L.d("zui home drop: the pin's card not changed (" + t + ")");
        }
    }

    /**
     * The pin's drag is over (null: the home screen went first): ZUI's ends with it, and an app
     * ZUI placed on the home screen leaves the bar. Told by the catcher and by the bar's strip,
     * whichever is first.
     */
    static void pinEnded(DragEvent ended) {
        Item pin = sPin;
        View bar = sPinBar;
        View catcher = sPinCatcher;
        if (pin == null) {
            return;
        }
        boolean placed = sPinPlaced;
        boolean handed = sPinHanded;
        end(ended);
        sPin = null;
        sPinCatcher = null;
        sPinBar = null;
        sPinCard = null;
        if (placed && bar != null) {
            TaskbarPins.unpin(bar.getContext(), pin.key());
            TaskbarRunning.refreshAll();
        }
        L.i("zui home drop: the pin " + name(pin) + (placed ? " moved to the home screen"
                : handed ? " not placed by ZUI - it stays on the bar" : " stayed on the bar"));
        if (catcher != null) {
            // Not while the drag's end is being handed out.
            catcher.post(() -> remove(catcher));
        }
    }

    private static void remove(View catcher) {
        if (catcher.getParent() instanceof ViewGroup) {
            ((ViewGroup) catcher.getParent()).removeView(catcher);
        }
    }
}
