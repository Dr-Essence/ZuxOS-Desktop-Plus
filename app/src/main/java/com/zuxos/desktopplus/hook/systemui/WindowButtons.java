package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.FullScreenWire;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.Glyphs;

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
 * light or dark by the night mode as ZUI picks them. Last, right of close, our Full screen
 * ({@link FullScreenWindows#askToggle}), as the menu's row of ours asks for it. The row ends
 * where ZUI's resize corner begins, and is one of the bar's occluding elements, so it keeps its
 * touches over an app that draws its own header under a see-through bar.
 *
 * <p>With the buttons there the pill's dots go: hidden, so it takes no touches and the bar under
 * it drags the window, and a double-click on it maximises or restores, as ZUI does on the
 * monitor (a click on the bar itself never opens ZUI's menu there). A bar without the buttons,
 * a split screen's, keeps its dots and its menu.
 */
public final class WindowButtons {

    private static final int FREEFORM = 5;
    private static final int FULLSCREEN = 1;
    /**
     * {@code InsetsSource.FLAG_FORCE_CONSUMING}: the title bar's room is taken out of the app's
     * window even where the app draws edge to edge. ZUI sets it for every app that does not ask
     * for a see-through bar ({@code DesktopModeWindowDecoration.updateRelayoutParams}).
     */
    private static final int FLAG_FORCE_CONSUMING = 1 << 2;
    private static final float BUTTON_DP = 36f;
    /** ZUI's button colours, from its {@code zui_desktopmode_normal_*} and {@code _dark_*}. */
    private static final int GLYPH_LIGHT = 0xFF191919;
    private static final int GLYPH_DARK = 0xFFFAFAFA;
    private static final float GLYPH_DP = 18f;
    /** The runtime's own allocation, for a class with no constructor left to call. */
    private static final String UNSAFE = "sun.misc.Unsafe";
    private static final String THE_UNSAFE = "theUnsafe";
    private static final String ALLOCATE_INSTANCE = "allocateInstance";

    /** Each title bar's own click listener, kept from its holder's making for its buttons. */
    private static final Map<View, View.OnClickListener> CLICKS = new WeakHashMap<>();
    /** Each title bar's buttons, once made. */
    private static final Map<View, Row> ROWS = new WeakHashMap<>();

    private static Ids sIds;
    private static boolean sNoIds;
    private static int sCornerPx = -1;

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
            row = add((ViewGroup) caption, click, pill(holder));
            if (row == null) {
                return;
            }
            ROWS.put(caption, row);
            if (L.once("WindowButtons")) {
                L.i("window buttons: added to a title bar on display " + displayOf(task));
            }
        }
        boolean night = night(caption.getContext());
        row.show(wanted, mode == FREEFORM, night, task.taskId, displayOf(task));
        if (wanted && solid(task)) {
            // ZUI clears a maximised window's bar; over a full-screen app's header nothing would
            // show through but the header itself.
            int colour = barColour(caption.getContext(), night,
                    FullScreenWindows.revealed(task.taskId)
                            || Boolean.TRUE.equals(Reflect.field(task, SysUiNames.IS_FOCUSED)));
            if (colour != 0) {
                caption.setBackgroundColor(colour);
            }
        }
    }

    /**
     * The buttons at the bar's end, and a block as wide on its start, with room shared equally
     * on both sides of ZUI's pill so it stays in the middle. Neither block nor room takes a
     * touch: the bar's own drag and click see them.
     */
    private static Row add(ViewGroup bar, View.OnClickListener click, View pill) {
        if (!(bar instanceof LinearLayout)
                || ((LinearLayout) bar).getOrientation() != LinearLayout.HORIZONTAL) {
            return null;
        }
        Context ctx = bar.getContext();
        int size = Ui.dp(ctx, BUTTON_DP);
        Ids ids = sIds;
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        ImageView full = null;
        if (Cfg.fullScreen()) {
            full = button(ctx, View.generateViewId(), null, FullScreenWire.ENTER);
        }
        Row row = new Row(new View(ctx), buttons, pill,
                button(ctx, ids.minimize, click, "Minimise"),
                button(ctx, ids.fullscreen, click, "Maximise"), full,
                button(ctx, ids.close, click, "Close"));
        for (View b : new View[]{row.minimize, row.toggle, row.close, row.full}) {
            if (b != null) {
                buttons.addView(b, new LinearLayout.LayoutParams(size,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        // The pointer on the bar or leaving it: a full-screen window's bar shown from the top
        // edge stays while it is there. Leaving the bar for a button is a leave and an enter.
        Row hovered = row;
        View.OnHoverListener feel = (v, e) -> {
            try {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_ENTER
                        || action == MotionEvent.ACTION_HOVER_EXIT) {
                    FullScreenWindows.hover(hovered.taskId,
                            action == MotionEvent.ACTION_HOVER_ENTER);
                }
            } catch (Throwable t) {
                L.d("window buttons: the pointer left as it is (" + t + ")");
            }
            return false;
        };
        bar.setOnHoverListener(feel);
        for (View b : new View[]{row.minimize, row.toggle, row.close, row.full}) {
            if (b != null) {
                b.setOnHoverListener(feel);
            }
        }
        if (full != null) {
            Row r = row;
            full.setOnClickListener(v -> {
                try {
                    FullScreenWindows.askToggle(ctx, r.taskId, r.display);
                    L.i("window buttons: full screen for task " + r.taskId + " - ZUX Home asked");
                } catch (Throwable t) {
                    L.w("window buttons: ZUX Home not asked (" + t + ")");
                }
            });
        }
        int width = rowWidth(ctx);
        LinearLayout.LayoutParams balance = new LinearLayout.LayoutParams(width,
                ViewGroup.LayoutParams.MATCH_PARENT);
        balance.setMarginStart(cornerPx(ctx));
        bar.addView(row.balance, 0, balance);
        bar.addView(room(ctx), 1, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(room(ctx), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(width,
                ViewGroup.LayoutParams.MATCH_PARENT);
        end.setMarginEnd(cornerPx(ctx));
        bar.addView(buttons, end);
        return row;
    }

    /**
     * ZUI's resize corner's reach into a window ({@code ovf_cornerSize_Inner}): the buttons end
     * that far from the bar's right edge, so a press on the last one is a press, not a resize.
     */
    private static int cornerPx(Context ctx) {
        if (sCornerPx < 0) {
            Resources res = ctx.getResources();
            int id = res.getIdentifier(SysUiNames.OVF_CORNER_SIZE_INNER, "dimen",
                    ctx.getPackageName());
            sCornerPx = id == 0 ? 0 : res.getDimensionPixelSize(id);
        }
        return sCornerPx;
    }

    /** The row's width: three of ZUI's buttons, and our Full screen while it is offered. */
    private static int rowWidth(Context ctx) {
        return Ui.dp(ctx, BUTTON_DP) * (Cfg.fullScreen() ? 4 : 3);
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
        if (click != null) {
            button.setOnClickListener(click);
        }
        TypedArray ripple = ctx.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackgroundBorderless});
        try {
            button.setBackground(ripple.getDrawable(0));
        } finally {
            ripple.recycle();
        }
        return button;
    }

    /**
     * Whether a bar is ZUI's solid one, all of it the bar's: shown from the top edge over a
     * full-screen app. A maximised window's stays see-through, with only its buttons taking
     * touches: whether its app is laid out below it is up to the system ({@link Room#make}), and
     * a solid bar over an app that is not hid its tabs and took their clicks.
     */
    private static boolean solid(ActivityManager.RunningTaskInfo task) {
        return FullScreenWindows.revealed(task.taskId);
    }

    /**
     * Whether the app asks for a see-through title bar to draw its own header under - Chrome's
     * tabs, Claude's bar: ZUI's own test ({@code isTransparentCaptionBarAppearance}).
     */
    private static boolean ownHeader(ActivityManager.RunningTaskInfo task) {
        Object appearance = Reflect.call(task.taskDescription,
                SysUiNames.GET_TOP_OPAQUE_SYSTEM_BARS_APPEARANCE);
        return appearance instanceof Integer && ((Integer) appearance
                & WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND) != 0;
    }

    /** ZUI's title bar colour for the shade and focus, by name; 0 when it is not there. */
    private static int barColour(Context ctx, boolean night, boolean focused) {
        String name = night
                ? (focused ? SysUiNames.ZUI_DESKTOP_MODE_CAPTION_BAR_DARK_FOCUS
                        : SysUiNames.ZUI_DESKTOP_MODE_CAPTION_BAR_DARK_NO_FOCUS)
                : (focused ? SysUiNames.ZUI_DESKTOP_MODE_CAPTION_BAR_NORMAL_FOCUS
                        : SysUiNames.ZUI_DESKTOP_MODE_CAPTION_BAR_NORMAL_NO_FOCUS);
        int id = ctx.getResources().getIdentifier(name, "color", ctx.getPackageName());
        return id == 0 ? 0 : ctx.getColor(id);
    }

    /** ZUI's own night test for its title bars' light or dark buttons. */
    private static boolean night(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static View pill(Object holder) {
        Object view = Reflect.field(holder, SysUiNames.CAPTION_HANDLE);
        return view instanceof View ? (View) view : null;
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
        private final Class<?> mElement;
        /** The element's constructor, or null: folded away when SystemUI was compiled. */
        private final Constructor<?> mNewElement;
        private final Field mElementRect;
        private final Field mElementAlignment;
        private final Object mCustomize;
        private final Field mLimitTouch;
        private final Field mInsetFlags;
        private int mPill;
        private Object mUnsafe;
        private Method mAllocate;

        Room(Class<?> params, Class<?> element, Class<?> alignment) throws Exception {
            mLayout = params.getField(SysUiNames.M_LAYOUT_RES_ID);
            mVisible = params.getField(SysUiNames.M_IS_CAPTION_VISIBLE);
            mWidth = params.getField(SysUiNames.M_CAPTION_WIDTH_ID);
            mHeight = params.getField(SysUiNames.M_CAPTION_HEIGHT_ID);
            mElements = params.getField(SysUiNames.M_OCCLUDING_CAPTION_ELEMENTS);
            mElement = element;
            Constructor<?>[] made = element.getDeclaredConstructors();
            mNewElement = made.length == 0 ? null : made[0];
            if (mNewElement != null) {
                mNewElement.setAccessible(true);
            }
            mLimitTouch = params.getField(SysUiNames.M_LIMIT_TOUCH_REGION_TO_SYSTEM_AREAS);
            mInsetFlags = params.getField(SysUiNames.M_INSET_SOURCE_FLAGS);
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
            if (solid(task)) {
                // All of the bar is the bar's: a press on it never reaches an app's header.
                mLimitTouch.setBoolean(params, false);
            } else if (mode == FULLSCREEN && !FullScreenWindows.has(task.taskId)
                    && ownHeader(task)) {
                // A maximised app that draws its own header - Chrome's tabs - laid out below the
                // bar as ZUI lays out every app that does not: the system takes the bar's room
                // out of the app's window, edge to edge or not.
                mInsetFlags.setInt(params, mInsetFlags.getInt(params) | FLAG_FORCE_CONSUMING);
                if (L.once("WindowButtons.below")) {
                    L.i("window buttons: an app with its own header laid out below its bar (its"
                            + " room consumed, as ZUI does for other apps)");
                }
            }
            int end = bounds.width() - cornerPx(ctx);
            Object element = newElement(params);
            mElementRect.set(element, new Rect(end - rowWidth(ctx), 0, end, height));
            mElementAlignment.set(element, mCustomize);
            ((List<Object>) mElements.get(params)).add(element);
            if (L.once("WindowButtons.room")) {
                L.i("window buttons: the buttons keep their touches over an app's own header");
            }
        }

        /**
         * A new element, as ZUI's {@code new} makes one. Its constructor is empty and was folded
         * away when SystemUI was compiled, so there may be none to call: the instance is then
         * allocated as the runtime does for {@code new}, with nothing skipped.
         */
        private Object newElement(Object params) throws Exception {
            if (mNewElement != null) {
                // Where it is an inner class, its constructor takes its parameters.
                return mNewElement.getParameterCount() == 1
                        ? mNewElement.newInstance(params) : mNewElement.newInstance();
            }
            if (mAllocate == null) {
                Class<?> unsafe = Class.forName(UNSAFE);
                Field one = unsafe.getDeclaredField(THE_UNSAFE);
                one.setAccessible(true);
                mUnsafe = one.get(null);
                mAllocate = unsafe.getMethod(ALLOCATE_INSTANCE, Class.class);
            }
            return mAllocate.invoke(mUnsafe, mElement);
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

    /** One bar's buttons, the block that balances them, and ZUI's pill they replace. */
    private static final class Row {
        final View balance;
        final View buttons;
        final View pill;
        final ImageView minimize;
        final ImageView toggle;
        /** Our Full screen, or null while it is not offered. */
        final ImageView full;
        final ImageView close;
        /** The window the bar stands for now, for our Full screen. */
        int taskId = -1;
        int display = Display.DEFAULT_DISPLAY;

        Row(View balance, View buttons, View pill, ImageView minimize, ImageView toggle,
                ImageView full, ImageView close) {
            this.balance = balance;
            this.buttons = buttons;
            this.pill = pill;
            this.minimize = minimize;
            this.toggle = toggle;
            this.full = full;
            this.close = close;
        }

        /**
         * Shown or not; a floating window's middle button is ZUI's full screen, a maximised
         * one's its floating. ZUI's "normal" pictures are dark glyphs for a light theme.
         */
        void show(boolean shown, boolean floating, boolean night, int taskId, int display) {
            int visibility = shown ? View.VISIBLE : View.GONE;
            if (buttons.getVisibility() != visibility) {
                buttons.setVisibility(visibility);
                balance.setVisibility(shown ? View.INVISIBLE : View.GONE);
            }
            // The pill's dots only where there are no buttons; hidden, it takes no touches.
            int dots = shown ? View.INVISIBLE : View.VISIBLE;
            if (pill != null && pill.getVisibility() != dots) {
                pill.setVisibility(dots);
            }
            if (!shown) {
                return;
            }
            this.taskId = taskId;
            this.display = display;
            Ids ids = sIds;
            int[] pictures = night ? ids.dark : ids.light;
            minimize.setImageResource(pictures[0]);
            toggle.setId(floating ? ids.fullscreen : ids.freeform);
            toggle.setContentDescription(floating ? "Maximise" : "Restore");
            toggle.setImageResource(floating ? pictures[1] : pictures[2]);
            close.setImageResource(pictures[3]);
            if (full != null) {
                boolean on = FullScreenWindows.has(taskId);
                Drawable glyph = Glyphs.of(on ? Glyphs.EXIT_FULL_SCREEN : Glyphs.FULL_SCREEN);
                if (glyph != null) {
                    glyph.setTint(night ? GLYPH_DARK : GLYPH_LIGHT);
                    // Our glyphs have no size of their own: ZUI's buttons' drawn size.
                    LayerDrawable sized = new LayerDrawable(new Drawable[]{glyph});
                    int px = Ui.dp(full.getContext(), GLYPH_DP);
                    sized.setLayerSize(0, px, px);
                    full.setImageDrawable(sized);
                }
                full.setContentDescription(on ? FullScreenWire.EXIT : FullScreenWire.ENTER);
            }
        }
    }
}
