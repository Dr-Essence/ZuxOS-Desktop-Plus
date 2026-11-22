package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Minimise, maximise or restore, and close on the monitor's title bars, as on DeX: no tap on the
 * pill first.
 *
 * <p>On the monitor ZUI's title bar is only its pill ({@code ov_desktop_mode_window_decor_pad});
 * those actions are in the "…" menu it opens. ZUI's own button title bars belong to its tablet
 * desktop mode and PC mode, and their view holder assumes one of those modes (a maximise button
 * made only in tablet desktop mode), so they cannot be put on the monitor.
 *
 * <p>So ZUI's pill bar gets three buttons that are the menu's own items: each carries the menu
 * item's id and the bar's own click listener ({@code DesktopModeTouchEventListener}, the one the
 * menu's items reach), so a click runs what the menu does - ZUI's minimise, its full screen or
 * floating, its close, with its checks and toasts. The pictures are ZUI's desktop-mode buttons,
 * light or dark by the night mode as ZUI picks them. Spacers keep the pill in the middle; it
 * still opens the menu, and the bar still drags the window.
 */
public final class WindowButtons {

    private static final int FREEFORM = 5;
    private static final int FULLSCREEN = 1;
    private static final float BUTTON_DP = 36f;

    /** Each title bar's own click listener, kept from its holder's making for its buttons. */
    private static final Map<View, View.OnClickListener> CLICKS = new WeakHashMap<>();
    /** Each title bar's buttons, once made. */
    private static final Map<View, Row> ROWS = new WeakHashMap<>();

    private static Ids sIds;
    private static boolean sNoIds;

    private WindowButtons() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> holder = Reflect.findClass(
                    SysUiNames.OV_DESKTOP_MODE_WINDOW_DECORATION_VIEW_HOLDER, loader);
            if (holder == null) {
                L.w("window buttons: ZUI's monitor title bar not found, the pill stays alone");
                return;
            }
            int made = XposedBridge.hookAllConstructors(holder, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 2 && param.args[0] instanceof View
                                && param.args[2] instanceof View.OnClickListener) {
                            View caption = caption(param.thisObject);
                            if (caption != null) {
                                CLICKS.put(caption, (View.OnClickListener) param.args[2]);
                            }
                        }
                    } catch (Throwable t) {
                        L.d("window buttons: a title bar left as it is (" + t + ")");
                    }
                }
            }).size();
            int bound = XposedBridge.hookAllMethods(holder, SysUiNames.BIND_DATA,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                bind(param.thisObject, param.args[0]);
                            } catch (Throwable t) {
                                L.d("window buttons: a title bar left as it is (" + t + ")");
                            }
                        }
                    }).size();
            if (made == 0 || bound == 0) {
                L.w("window buttons: ZUI's monitor title bar calls not found, the pill stays "
                        + "alone");
                return;
            }
            hookRoom(loader);
            L.i("window buttons: minimise, maximise and close on the monitor's title bars");
        } catch (Throwable t) {
            L.w("window buttons: not installed, the pill stays alone (" + t + ")");
        }
    }

    /**
     * After ZUI's {@code updateRelayoutParams}, the bar's room for its buttons, in ZUI's own
     * terms: a maximised window's bar as wide as the window (width id 0, as ZUI makes a floating
     * one's) instead of the pill's width; and the buttons one of the bar's occluding elements, so
     * an app that draws its own header under a see-through bar is told where they are and leaves
     * them their touches ({@code mLimitTouchRegionToSystemAreas}).
     */
    private static void hookRoom(ClassLoader loader) {
        try {
            Class<?> decoration = Reflect.findClass(SysUiNames.DESKTOP_MODE_WINDOW_DECORATION,
                    loader);
            Method update = null;
            if (decoration != null) {
                for (Method m : decoration.getDeclaredMethods()) {
                    Class<?>[] types = m.getParameterTypes();
                    if (m.getName().equals(SysUiNames.UPDATE_RELAYOUT_PARAMS) && types.length > 2
                            && types[1] == Context.class
                            && types[2] == ActivityManager.RunningTaskInfo.class) {
                        update = m;
                    }
                }
            }
            if (update == null) {
                L.w("window buttons: ZUI's title-bar decision not found - a maximised window's "
                        + "bar keeps the pill's width, with no room for its buttons");
                return;
            }
            Class<?> params = update.getParameterTypes()[0];
            Room room = new Room(params, Class.forName(SysUiNames.OCCLUDING_CAPTION_ELEMENT,
                    false, loader), Class.forName(SysUiNames.OCCLUDING_CAPTION_ALIGNMENT, false,
                    loader));
            XposedBridge.hookMethod(update, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        room.make(param.args[0], (Context) param.args[1],
                                (ActivityManager.RunningTaskInfo) param.args[2]);
                    } catch (Throwable t) {
                        L.d("window buttons: the bar's room left to ZUI (" + t + ")");
                    }
                }
            });
        } catch (Throwable t) {
            L.w("window buttons: the bar's room not made, a maximised window keeps the pill "
                    + "alone (" + t + ")");
        }
    }

    /** The bar's data bound: its buttons made once, then set for the window as it is now. */
    private static void bind(Object holder, Object data) {
        Object field = Reflect.field(data, SysUiNames.TASK_INFO);
        View caption = caption(holder);
        if (!(field instanceof ActivityManager.RunningTaskInfo)
                || !(caption instanceof ViewGroup)) {
            return;
        }
        ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) field;
        int mode = modeOf(task);
        boolean wanted = Cfg.windowButtons() && displayOf(task) != Display.DEFAULT_DISPLAY
                && (mode == FREEFORM || mode == FULLSCREEN);
        Row row = ROWS.get(caption);
        if (row == null) {
            View.OnClickListener click = CLICKS.get(caption);
            if (!wanted || click == null || ids(caption.getContext()) == null) {
                return;
            }
            row = add((ViewGroup) caption, click);
            if (row == null) {
                return;
            }
            ROWS.put(caption, row);
            if (L.once("WindowButtons")) {
                L.i("window buttons: added to a title bar on display " + displayOf(task));
            }
        }
        row.show(wanted, mode == FREEFORM, night(caption.getContext()));
    }

    /**
     * The buttons at the bar's end, and a block as wide on its start, with room shared equally
     * on both sides of ZUI's pill so it stays in the middle. Neither block nor room takes a
     * touch: the bar's own drag and click see them.
     */
    private static Row add(ViewGroup bar, View.OnClickListener click) {
        if (!(bar instanceof LinearLayout)
                || ((LinearLayout) bar).getOrientation() != LinearLayout.HORIZONTAL) {
            return null;
        }
        Context ctx = bar.getContext();
        int size = Ui.dp(ctx, BUTTON_DP);
        Ids ids = sIds;
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Row row = new Row(new View(ctx), buttons, button(ctx, ids.minimize, click, "Minimise"),
                button(ctx, ids.fullscreen, click, "Maximise"),
                button(ctx, ids.close, click, "Close"));
        buttons.addView(row.minimize, new LinearLayout.LayoutParams(size,
                ViewGroup.LayoutParams.MATCH_PARENT));
        buttons.addView(row.toggle, new LinearLayout.LayoutParams(size,
                ViewGroup.LayoutParams.MATCH_PARENT));
        buttons.addView(row.close, new LinearLayout.LayoutParams(size,
                ViewGroup.LayoutParams.MATCH_PARENT));
        int width = size * 3;
        bar.addView(row.balance, 0, new LinearLayout.LayoutParams(width,
                ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(room(ctx), 1, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(room(ctx), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(buttons, new LinearLayout.LayoutParams(width,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private static View room(Context ctx) {
        View room = new View(ctx);
        room.setClickable(false);
        room.setFocusable(false);
        return room;
    }

    private static ImageView button(Context ctx, int id, View.OnClickListener click,
            String name) {
        ImageView button = new ImageView(ctx);
        button.setId(id);
        button.setContentDescription(name);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setFocusable(false);
        button.setOnClickListener(click);
        TypedArray ripple = ctx.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackgroundBorderless});
        try {
            button.setBackground(ripple.getDrawable(0));
        } finally {
            ripple.recycle();
        }
        return button;
    }

    /** ZUI's own night test for its title bars' light or dark buttons. */
    private static boolean night(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static View caption(Object holder) {
        Object view = Reflect.field(holder, SysUiNames.CAPTION_VIEW);
        return view instanceof View ? (View) view : null;
    }

    /** The task's windowing mode ({@code getWindowingMode}, not in the public SDK). */
    private static int modeOf(ActivityManager.RunningTaskInfo task) {
        Object mode = Reflect.call(task, SysUiNames.GET_WINDOWING_MODE);
        return mode instanceof Integer ? (Integer) mode : 0;
    }

    private static int displayOf(ActivityManager.RunningTaskInfo task) {
        Object display = Reflect.field(task, "displayId");
        return display instanceof Integer ? (Integer) display : Display.DEFAULT_DISPLAY;
    }

    /** ZUI's menu item ids and button pictures, by name, once; null if any is missing. */
    private static Ids ids(Context ctx) {
        if (sIds != null || sNoIds) {
            return sIds;
        }
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        Ids ids = new Ids(
                res.getIdentifier(SysUiNames.OVC_WD_HANDLE_MENU_ITEM_MINIMIZE, "id", pkg),
                res.getIdentifier(SysUiNames.OVC_WD_HANDLE_MENU_ITEM_FULLSCREEN, "id", pkg),
                res.getIdentifier(SysUiNames.OVC_WD_HANDLE_MENU_ITEM_FREEFORM, "id", pkg),
                res.getIdentifier(SysUiNames.OVC_WD_HANDLE_MENU_ITEM_CLOSE, "id", pkg),
                pictures(res, pkg, SysUiNames.ZUI_DESKTOPMODE_NORMAL),
                pictures(res, pkg, SysUiNames.ZUI_DESKTOPMODE_DARK));
        if (!ids.complete()) {
            sNoIds = true;
            L.w("window buttons: ZUI's menu items or button pictures not found, the pill stays "
                    + "alone");
            return null;
        }
        sIds = ids;
        return ids;
    }

    /** Minimise, maximise, restore and close, in that order, for one shade. */
    private static int[] pictures(Resources res, String pkg, String prefix) {
        return new int[]{
                res.getIdentifier(prefix + SysUiNames.PICTURE_MINIMIZE, "drawable", pkg),
                res.getIdentifier(prefix + SysUiNames.PICTURE_MAX, "drawable", pkg),
                res.getIdentifier(prefix + SysUiNames.PICTURE_FREEFORM, "drawable", pkg),
                res.getIdentifier(prefix + SysUiNames.PICTURE_CLOSE, "drawable", pkg),
        };
    }

    private static final class Ids {
        final int minimize;
        final int fullscreen;
        final int freeform;
        final int close;
        final int[] light;
        final int[] dark;

        Ids(int minimize, int fullscreen, int freeform, int close, int[] light, int[] dark) {
            this.minimize = minimize;
            this.fullscreen = fullscreen;
            this.freeform = freeform;
            this.close = close;
            this.light = light;
            this.dark = dark;
        }

        boolean complete() {
            if (minimize == 0 || fullscreen == 0 || freeform == 0 || close == 0) {
                return false;
            }
            for (int i = 0; i < light.length; i++) {
                if (light[i] == 0 || dark[i] == 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /** ZUI's relayout parameters for a title bar, and what the buttons need of them. */
    private static final class Room {
        private final Field mLayout;
        private final Field mVisible;
        private final Field mWidth;
        private final Field mHeight;
        private final Field mElements;
        private final Constructor<?> mNewElement;
        private final Field mElementRect;
        private final Field mElementAlignment;
        private final Object mCustomize;
        private int mPill;

        Room(Class<?> params, Class<?> element, Class<?> alignment) throws Exception {
            mLayout = params.getField(SysUiNames.M_LAYOUT_RES_ID);
            mVisible = params.getField(SysUiNames.M_IS_CAPTION_VISIBLE);
            mWidth = params.getField(SysUiNames.M_CAPTION_WIDTH_ID);
            mHeight = params.getField(SysUiNames.M_CAPTION_HEIGHT_ID);
            mElements = params.getField(SysUiNames.M_OCCLUDING_CAPTION_ELEMENTS);
            mNewElement = element.getDeclaredConstructor();
            mNewElement.setAccessible(true);
            mElementRect = element.getField(SysUiNames.M_RECT);
            mElementAlignment = element.getField(SysUiNames.M_ALIGNMENT);
            mCustomize = alignment.getField(SysUiNames.CUSTOMIZE).get(null);
        }

        @SuppressWarnings("unchecked")
        void make(Object params, Context ctx, ActivityManager.RunningTaskInfo task)
                throws Exception {
            int mode = modeOf(task);
            if (!Cfg.windowButtons() || displayOf(task) == Display.DEFAULT_DISPLAY
                    || (mode != FREEFORM && mode != FULLSCREEN) || !mVisible.getBoolean(params)
                    || mLayout.getInt(params) != pill(ctx) || ids(ctx) == null) {
                return;
            }
            if (mode == FULLSCREEN && mWidth.getInt(params) != 0) {
                mWidth.setInt(params, 0);
            }
            Rect bounds = taskBounds(task);
            int height = mHeight.getInt(params) == 0 ? 0
                    : ctx.getResources().getDimensionPixelSize(mHeight.getInt(params));
            if (bounds == null || height <= 0) {
                return;
            }
            int width = Ui.dp(ctx, BUTTON_DP) * 3;
            Object element = mNewElement.newInstance();
            mElementRect.set(element, new Rect(bounds.width() - width, 0, bounds.width(),
                    height));
            mElementAlignment.set(element, mCustomize);
            ((List<Object>) mElements.get(params)).add(element);
        }

        /** ZUI's pill layout, by name, once; -1 (no layout's id) when it is not there. */
        private int pill(Context ctx) {
            if (mPill == 0) {
                int id = ctx.getResources().getIdentifier(
                        SysUiNames.OV_DESKTOP_MODE_WINDOW_DECOR_PAD, "layout",
                        ctx.getPackageName());
                mPill = id == 0 ? -1 : id;
            }
            return mPill;
        }

        private static Rect taskBounds(ActivityManager.RunningTaskInfo task) {
            Object config = Reflect.call(task, SysUiNames.GET_CONFIGURATION);
            Object window = Reflect.field(config, SysUiNames.WINDOW_CONFIGURATION);
            Object bounds = Reflect.call(window, SysUiNames.GET_BOUNDS);
            return bounds instanceof Rect ? (Rect) bounds : null;
        }
    }

    /** One bar's buttons and the block that balances them. */
    private static final class Row {
        final View balance;
        final View buttons;
        final ImageView minimize;
        final ImageView toggle;
        final ImageView close;

        Row(View balance, View buttons, ImageView minimize, ImageView toggle, ImageView close) {
            this.balance = balance;
            this.buttons = buttons;
            this.minimize = minimize;
            this.toggle = toggle;
            this.close = close;
        }

        /**
         * Shown or not; a floating window's middle button is ZUI's full screen, a maximised
         * one's its floating. ZUI's "normal" pictures are dark glyphs for a light theme.
         */
        void show(boolean shown, boolean floating, boolean night) {
            int visibility = shown ? View.VISIBLE : View.GONE;
            if (buttons.getVisibility() != visibility) {
                buttons.setVisibility(visibility);
                balance.setVisibility(shown ? View.INVISIBLE : View.GONE);
            }
            if (!shown) {
                return;
            }
            Ids ids = sIds;
            int[] pictures = night ? ids.dark : ids.light;
            minimize.setImageResource(pictures[0]);
            toggle.setId(floating ? ids.fullscreen : ids.freeform);
            toggle.setContentDescription(floating ? "Maximise" : "Restore");
            toggle.setImageResource(floating ? pictures[1] : pictures[2]);
            close.setImageResource(pictures[3]);
        }
    }
}
