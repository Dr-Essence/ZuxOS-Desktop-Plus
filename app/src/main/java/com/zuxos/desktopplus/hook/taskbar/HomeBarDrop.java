package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.drawer.ZuiFolders;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * An app or a folder of apps dragged from ZUI's home screen and let go on the taskbar moves
 * there, as ZUI's own dock takes it.
 *
 * <p>ZUI's home-screen drag is ZUX Home's own ({@code DragController}, in its window), and the bar
 * is another window: the drag never reached it, and let go over the bar the item went back. For a
 * drag from the home screen - its pages, its dock, or one of ZUI's own folders on it - a drop
 * target of ours joins ZUI's own drag ({@code addDropTarget}, the newest, so asked first) over the
 * bar only. The bar shows its line with the icon there; let go, the item is pinned at the line and
 * leaves the home screen through ZUI's own removal ({@code Launcher.removeItem}), as a pin dragged
 * off the bar onto the home screen leaves the bar (drag row 2: home to bar moves). A drop the bar
 * would refuse is ZUI's to send back.
 */
final class HomeBarDrop {

    private static final String DROP_TARGET = "com.android.launcher3.DropTarget";
    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";
    /** ZUI's {@code ItemInfo.ITEM_TYPE_APPLICATION}. */
    private static final int APPLICATION = 0;

    /** The drag from the home screen under way, or null. Main thread. */
    private static Drag sDrag;
    private static Method sLookup;

    private HomeBarDrop() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> workspace = Reflect.findClass("com.android.launcher3.Workspace", loader);
            Class<?> context = Reflect.findClass(CONTEXT, loader);
            if (workspace == null || context == null
                    || Reflect.findClass(DROP_TARGET, loader) == null) {
                L.w("home bar drop: ZUI's home screen drag not found - its items stay off the"
                        + " bar");
                return;
            }
            sLookup = context.getMethod("lookupContext", Context.class);
            int hooked = XposedBridge.hookAllMethods(workspace, "onDragStart",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            begin(param.thisObject, param.args);
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(workspace, "onDragEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    end();
                }
            }).size();
            Health.hooked("taskbar: home screen items dragged onto the bar", hooked,
                    Cfg::homeToBar);
            L.i("home bar drop: the home screen's apps and folders can be let go on the bar x"
                    + hooked);
        } catch (Throwable t) {
            L.w("home bar drop: not installed (" + t + ")");
        }
    }

    /** One drag from the home screen, and our target on ZUI's controller for it. */
    private static final class Drag {
        final Object launcher;
        final Object controller;
        final View layer;
        final Object info;
        final Item item;
        /** Why the bar will not take it whatever the bar holds; null when it may. */
        final String never;
        final Drawable icon;
        final int display;
        Object target;
        float lastX = -1f;
        boolean onBar;

        Drag(Object launcher, Object controller, View layer, Object info, Item item,
                String never, Drawable icon, int display) {
            this.launcher = launcher;
            this.controller = controller;
            this.layer = layer;
            this.info = info;
            this.item = item;
            this.never = never;
            this.icon = icon;
            this.display = display;
        }
    }

    /** A drag starting on ZUX Home's controller: ours to hear when it is from the home screen. */
    private static void begin(Object workspace, Object[] args) {
        end();
        try {
            if (!Cfg.homeToBar() || args.length == 0 || args[0] == null
                    || !(workspace instanceof View)) {
                return;
            }
            View home = (View) workspace;
            if (home.getDisplay() == null
                    || home.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) {
                return;
            }
            Object drag = args[0];
            Object source = Reflect.field(drag, "dragSource");
            Object launcher = sLookup.invoke(null, home.getContext());
            if (launcher == null || !fromHome(launcher, workspace, source)
                    || Boolean.TRUE.equals(Reflect.call(launcher, "isInEditViewMode"))) {
                // In ZUI's edit mode its own targets - make a folder, remove - lie along the
                // bottom, over the bar: they keep the drag.
                return;
            }
            Object multi = Reflect.field(drag, "multiDragSupport");
            Object chosen = multi == null ? null : Reflect.field(multi, "selectedViews");
            if (chosen instanceof List && !((List<?>) chosen).isEmpty()) {
                if (L.once("HomeBarDrop.group")) {
                    L.i("home bar drop: a group from the home screen stays ZUI's - one at a time"
                            + " onto the bar");
                }
                return;
            }
            Object info = Reflect.field(drag, "dragInfo");
            Context ctx = home.getContext();
            Item item;
            String never = null;
            if (info != null && info.getClass().getName().endsWith(".FolderInfo")) {
                Object title = Reflect.field(info, "title");
                item = Item.folder(title != null ? title.toString() : "Folder");
                Object contents = Reflect.call(info, "getContents");
                for (Object child : contents instanceof List ? (List<?>) contents
                        : java.util.Collections.emptyList()) {
                    Item app = isApp(child) ? IconInfo.itemOf(ctx, child) : null;
                    if (app == null) {
                        never = "Only folders of apps can be pinned here";
                        break;
                    }
                    item.children.add(app);
                }
                if (item.children.isEmpty()) {
                    never = "Only folders of apps can be pinned here";
                }
            } else if (isApp(info)) {
                item = IconInfo.itemOf(ctx, info);
            } else {
                // A widget, a shortcut: the bar holds none, and the drag stays ZUI's.
                return;
            }
            Object controller = Reflect.call(launcher, "getDragController");
            Object layer = Reflect.call(launcher, "getDragLayer");
            if (item == null || controller == null || !(layer instanceof View)) {
                return;
            }
            Drag ours = new Drag(launcher, controller, (View) layer, info, item, never,
                    iconOf(Reflect.field(drag, "originalView")), Display.DEFAULT_DISPLAY);
            ClassLoader loader = workspace.getClass().getClassLoader();
            ours.target = Proxy.newProxyInstance(loader,
                    new Class<?>[]{Class.forName(DROP_TARGET, false, loader)},
                    (proxy, method, a) -> call(ours, proxy, method.getName(), a,
                            method.getReturnType()));
            Reflect.call(controller, "addDropTarget", ours.target);
            sDrag = ours;
        } catch (Throwable t) {
            sDrag = null;
            L.d("home bar drop: a home screen drag left to ZUI (" + t + ")");
        }
    }

    /**
     * Whether a drag's source is the home screen: its pages, its dock, or one of ZUI's own
     * folders there. The drawers' drags have theirs, and our folders are the drawer's.
     */
    private static boolean fromHome(Object launcher, Object workspace, Object source) {
        if (source == null) {
            return false;
        }
        if (source == workspace || source == Reflect.call(launcher, "getHotseat")) {
            return true;
        }
        if (!source.getClass().getName().equals("com.android.launcher3.folder.Folder")) {
            return false;
        }
        Object info = Reflect.field(source, "mInfo");
        Object id = info == null ? null : Reflect.field(info, "id");
        return id instanceof Integer && (Integer) id > ZuiFolders.ID_BASE;
    }

    private static boolean isApp(Object info) {
        Object type = info == null ? null : Reflect.field(info, "itemType");
        return type instanceof Integer && (Integer) type == APPLICATION;
    }

    /** Our {@code DropTarget}, over the bar only. */
    private static Object call(Drag drag, Object proxy, String name, Object[] args,
            Class<?> returns) {
        try {
            switch (name) {
                case "isDropEnabled":
                    return sDrag == drag;
                case "getHitRectRelativeToDragLayer":
                    barRect(drag, (Rect) args[0]);
                    return null;
                case "onDragEnter":
                case "onDragOver":
                    over(drag, args[0]);
                    return null;
                case "onDragExit":
                    // ZUI's drop leaves the target first: the line stays for the drop.
                    if (!Boolean.TRUE.equals(Reflect.field(args[0], "dragComplete"))) {
                        off(drag);
                    }
                    return null;
                case "acceptDrop":
                    return accept(drag);
                case "onDrop":
                    drop(drag, args[0]);
                    return null;
                case "getDropView":
                    return drag.layer;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && proxy == args[0];
                case "toString":
                    return "HomeBarDrop.target";
                default:
                    break;
            }
        } catch (Throwable t) {
            L.d("home bar drop: " + name + " failed (" + t + ")");
        }
        return returns == boolean.class ? Boolean.FALSE : null;
    }

    /** The bar's place in ZUX Home's drag layer; empty while no bar shows. */
    private static void barRect(Drag drag, Rect out) {
        RectF bar = TaskbarDrop.barOnScreen(drag.display);
        if (bar == null) {
            out.setEmpty();
            return;
        }
        int[] layer = new int[2];
        drag.layer.getLocationOnScreen(layer);
        out.set(Math.round(bar.left) - layer[0], Math.round(bar.top) - layer[1],
                Math.round(bar.right) - layer[0], Math.round(bar.bottom) - layer[1] + 1);
    }

    private static void over(Drag drag, Object dragObject) {
        int[] layer = new int[2];
        drag.layer.getLocationOnScreen(layer);
        Object x = Reflect.field(dragObject, "x");
        drag.lastX = layer[0] + (x instanceof Integer ? (Integer) x : 0);
        drag.onBar = true;
        TaskbarDrop.hintAt(drag.display, drag.lastX, drag.icon);
    }

    private static void off(Drag drag) {
        if (drag.onBar) {
            drag.onBar = false;
            TaskbarDrop.hintAt(drag.display, -1f);
        }
    }

    /** Whether the bar takes it; refused, ZUI sends the item back and the bar says why. */
    private static boolean accept(Drag drag) {
        String no = drag.never != null ? drag.never
                : TaskbarDrop.refusal(drag.display, drag.item);
        if (no == null) {
            return true;
        }
        off(drag);
        TaskbarMenu.toast(drag.layer.getContext(), no);
        L.i("home bar drop: " + name(drag.item) + " not pinned - " + no);
        return false;
    }

    /** Let go on the bar: pinned at the line, and off the home screen by ZUI's own removal. */
    private static void drop(Drag drag, Object dragObject) {
        setBoolean(dragObject, "deferDragViewCleanupPostAnimation", false);
        off(drag);
        if (!TaskbarDrop.pinAt(drag.display, drag.item, drag.lastX)) {
            L.i("home bar drop: " + name(drag.item) + " let go on the bar, not pinned");
            return;
        }
        Object original = Reflect.field(dragObject, "originalView");
        boolean removed = false;
        for (Method m : drag.launcher.getClass().getMethods()) {
            if (m.getName().equals("removeItem") && m.getParameterCount() == 4
                    && m.getParameterTypes()[0] == View.class) {
                try {
                    m.invoke(drag.launcher, original instanceof View ? original : null,
                            drag.info, true, "moved to the taskbar");
                    removed = true;
                } catch (Throwable t) {
                    L.w("home bar drop: ZUI kept it on the home screen (" + t + ")");
                }
                break;
            }
        }
        L.i("home bar drop: " + name(drag.item) + " pinned to the bar"
                + (removed ? " and taken off the home screen" : ", still on the home screen"));
    }

    /** The drag is over: our target goes with it. */
    private static void end() {
        Drag drag = sDrag;
        sDrag = null;
        if (drag != null) {
            off(drag);
            Reflect.call(drag.controller, "removeDropTarget", drag.target);
        }
    }

    /** A copy of the dragged app's icon, for the bar's line; null for a folder. */
    private static Drawable iconOf(Object view) {
        Object icon = Reflect.call(view, "getIcon");
        if (!(icon instanceof Drawable)) {
            return null;
        }
        Drawable.ConstantState state = ((Drawable) icon).getConstantState();
        return state != null ? state.newDrawable().mutate() : null;
    }

    private static String name(Item item) {
        return item.pkg != null ? item.pkg : "folder \"" + item.label + "\"";
    }

    private static void setBoolean(Object o, String name, boolean value) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(o, value);
                return;
            } catch (NoSuchFieldException e) {
                // Up the hierarchy.
            } catch (Throwable t) {
                return;
            }
        }
    }
}
