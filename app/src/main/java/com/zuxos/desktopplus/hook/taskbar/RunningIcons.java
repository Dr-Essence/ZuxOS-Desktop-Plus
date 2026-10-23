package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.core.theme.Bevel;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.desktop.DragPayload;
import com.zuxos.desktopplus.desktop.FolderIconDrawable;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.drawer.DrawerFolderWindow;
import com.zuxos.desktopplus.hook.drawer.NativeDrawerHooks;
import com.zuxos.desktopplus.hook.drawer.ZuiFolderBar;
import com.zuxos.desktopplus.hook.panel.QuickTiles;
import com.zuxos.desktopplus.hook.recents.TaskOverview;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Our own icons in the taskbar row ({@link TaskbarRunning}): a pin, an open app or one of its
 * windows, a folder, each the size of the launcher's own icons; what a pinned icon does under a
 * finger or a mouse ({@code PinGesture}), and the press feel on all of them ({@link Press}).
 */
public final class RunningIcons {

    private RunningIcons() {
    }

    /** The size the launcher's own icons are, so ours are not the odd ones out. */
    static int iconSize(ViewGroup icons) {
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0
                    && IconInfo.packageOfView(child) != null) {
                TaskbarRunning.SIZES.put(icons, child.getWidth());
                return child.getWidth();
            }
        }
        Integer known = TaskbarRunning.SIZES.get(icons);
        if (known != null) {
            return known;
        }
        // ZUI's drawer button is laid out at the bar's icon size - 60px on the monitor, beside
        // 60px apps in the probe - and it is there whether or not any app icon is. One size for
        // every bar is how the tablet's got the monitor's, small at the tablet's density.
        View button = TaskbarStart.allAppsButton(icons);
        return TaskbarStart.size(button, icons.getContext());
    }

    /**
     * An icon for something pinned here.
     *
     * <p>Pinned by us, in our own row, and so unpinned from the same menu - the launcher knows
     * nothing about it either way.
     */
    static View pinIcon(Context ctx, Item pin, int size, int displayId) {
        View icon = pin.type == Item.TYPE_FOLDER
                ? folderIcon(ctx, pin, size, displayId) : iconFor(ctx, pin.pkg, size, displayId);
        if (icon == null) {
            return null;
        }
        final String key = pin.key();
        Runnable menu = () -> {
            List<TaskbarMenu.Entry> entries = new ArrayList<>();
            if (pin.pkg != null) {
                entries.addAll(TaskbarApps.entriesFor(ctx, pin.pkg,
                        android.os.Process.myUserHandle(), displayId));
                // After Open, ZUI's own floating window and split screen.
                entries.addAll(Math.min(1, entries.size()), ZuiWindows.entries(icon, pin.pkg,
                        android.os.Process.myUserHandle()));
            } else {
                entries.add(new TaskbarMenu.Entry("Open folder",
                        () -> openFolder(ctx, icon, pin, displayId)));
            }
            entries.add(new TaskbarMenu.Entry("Unpin", () -> {
                TaskbarPins.unpin(ctx, key);
                TaskbarRunning.refreshAll();
            }));
            int[] at = new int[2];
            icon.getLocationOnScreen(at);
            TaskbarMenu.showEntries(icon, displayId, at[0] + icon.getWidth() / 2f, entries);
        };
        icon.setOnLongClickListener(null);
        icon.setLongClickable(false);
        icon.setOnTouchListener(new PinGesture(pin, menu));
        // The controller's Triangle asks for the same menu (TaskbarKeys).
        icon.setTag(TaskbarKeys.TAG_MENU, menu);
        return icon;
    }

    /**
     * A folder pinned to the bar.
     *
     * <p>On the tablet's bar it is ZUI's own folder ({@link ZuiFolderBar#barIcon}). On the monitor,
     * or should ZUI's not be had, it is drawn the way folders are drawn everywhere else here and
     * opens in the window the monitor's drawer folders use.
     */
    private static View folderIcon(Context ctx, Item folder, int size, int displayId) {
        if (displayId == android.view.Display.DEFAULT_DISPLAY) {
            // The tablet's bar: ZUI's own folder, opened as ZUI's bar opens its folders.
            View zui = ZuiFolderBar.barIcon(ctx, folder);
            if (zui != null) {
                TaskbarPreview.attachHover(zui);
                TaskbarKeys.focusable(zui, displayId);
                return zui;
            }
        }
        try {
            List<Drawable> previews = new ArrayList<>();
            for (Item child : folder.children) {
                if (child.pkg == null || previews.size() >= 4) {
                    continue;
                }
                try {
                    previews.add(ctx.getPackageManager().getApplicationIcon(child.pkg));
                } catch (Throwable missing) {
                    // An app that has been uninstalled since. The folder still opens.
                }
            }
            ImageView view = new ImageView(ctx);
            view.setImageDrawable(new FolderIconDrawable(ctx, previews, size,
                    Theme.of(displayId)));
            view.setContentDescription(folder.label != null ? folder.label : "Folder");
            view.setBackground(iconBackground(ctx, size, displayId));
            view.setOnClickListener(v -> openFolder(ctx, v, folder, displayId));
            // A folder lifts and jiggles under the pointer like the apps beside it; it has no
            // windows, so no preview.
            TaskbarPreview.attachHover(view);
            TaskbarKeys.focusable(view, displayId);
            return view;
        } catch (Throwable t) {
            L.d("taskbar running: could not draw the pinned folder (" + t + ")");
            return null;
        }
    }

    private static void openFolder(Context ctx, View icon, Item folder, int displayId) {
        if (ZuiFolderBar.isZuiFolder(icon)) {
            ZuiFolderBar.openOnBar(icon);
            return;
        }
        try {
            // The same repository the drawer's own folders open with, rather than a second one.
            DrawerFolderWindow.show(ctx, folder, NativeDrawerHooks.repo(ctx), displayId,
                    Ui.dp(ctx, Cfg.iconSizeDp()), null, icon);
        } catch (Throwable t) {
            L.e("taskbar running: could not open the pinned folder", t);
        }
    }

    /**
     * What a pinned icon does under a finger or a mouse.
     *
     * <p>Tap opens it. Hold and let go without moving: its menu. Hold and then move: it comes off
     * the bar and can be dropped somewhere else along it. Right-click: the menu straight away.
     * One gesture, three outcomes, the way a taskbar behaves - and a quick swipe before the hold
     * is still a scroll of the row, because nothing is armed until the hold has happened.
     */
    private static final class PinGesture implements View.OnTouchListener {

        private final Item mPin;
        private final Runnable mMenu;
        private float mDownX;
        private float mDownY;
        private boolean mArmed;
        private Runnable mArm;

        PinGesture(Item pin, Runnable menu) {
            mPin = pin;
            mMenu = menu;
        }

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    TaskbarPreview.pressed(v);
                    if (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                            && (e.getButtonState()
                            & android.view.MotionEvent.BUTTON_SECONDARY) != 0) {
                        mMenu.run();
                        return true;
                    }
                    mDownX = e.getRawX();
                    mDownY = e.getRawY();
                    mArmed = false;
                    Press.down(v);
                    mArm = () -> {
                        mArmed = true;
                        // From here the row must not take the gesture for a scroll.
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        if (!Theme.of(v).retro()) {
                            v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(Motion.SPRING_MS)
                                    .setInterpolator(Motion.SPRING).start();
                        }
                    };
                    v.postDelayed(mArm, android.view.ViewConfiguration.getLongPressTimeout());
                    return false;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    float slop = android.view.ViewConfiguration.get(v.getContext())
                            .getScaledTouchSlop();
                    boolean moved = Math.abs(e.getRawX() - mDownX) > slop
                            || Math.abs(e.getRawY() - mDownY) > slop;
                    if (!moved) {
                        return mArmed;
                    }
                    if (!mArmed) {
                        v.removeCallbacks(mArm);
                        Press.up(v);
                        return false;
                    }
                    mArmed = false;
                    settle(v, e);
                    startDrag(v);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP: {
                    v.removeCallbacks(mArm);
                    if (!mArmed) {
                        Press.up(v);
                        return false;
                    }
                    mArmed = false;
                    settle(v, e);
                    mMenu.run();
                    return true;
                }
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(mArm);
                    mArmed = false;
                    Press.up(v);
                    return false;
                default:
                    return mArmed;
            }
        }

        /**
         * Back to rest, and the view told the touch is over.
         *
         * <p>It saw the press go down and will not see it come up - this listener takes the up -
         * so without the cancel it stays pressed and its ripple never fades.
         */
        private void settle(View v, android.view.MotionEvent e) {
            Press.up(v);
            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e);
            cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
            v.onTouchEvent(cancel);
            cancel.recycle();
        }

        private void startDrag(View v) {
            try {
                DragPayload payload =
                        new DragPayload(mPin,
                                DragPayload.SRC_TASKBAR, null);
                View.DragShadowBuilder card = new View.DragShadowBuilder(v);
                // Local - a pin moves along the bar and nowhere else, so the desktop never sees
                // it - but across ZUX Home's windows where the home screen is behind the tablet's
                // bar, which can take it (ZuiHomeDrop).
                boolean home = ZuiHomeDrop.armForPin(v, mPin, card);
                if (!v.startDragAndDrop(payload.toClip(), card, payload,
                        home ? DragPayload.FLAGS : 0) && home) {
                    ZuiHomeDrop.disarmPin();
                }
            } catch (Throwable t) {
                L.d("taskbar running: could not pick the pin up (" + t + ")");
            }
        }
    }

    /**
     * The press feel on our icons: a quick dip to 90% under the finger, and a spring back when it
     * lifts - the small physical response that makes a tap feel like it landed.
     */
    public static final class Press implements View.OnTouchListener {

        static void down(View v) {
            TaskbarPreview.pressed(v);
            if (Theme.of(v).retro()) {
                // Retro's buttons sink into the bar instead, by their own pressed state.
                return;
            }
            v.animate().cancel();
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(Motion.SHORT - 40)
                    .setInterpolator(Motion.EASE).start();
        }

        static void up(View v) {
            v.animate().cancel();
            v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS)
                    .setInterpolator(Motion.SPRING).start();
        }

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down(v);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    up(v);
                    break;
                default:
                    break;
            }
            // Only watching: the click and the hold menu still happen as before.
            return false;
        }
    }


    /** Glass's ripple, or on a Retro bar a Windows 98 button that shows whether the app is open. */
    private static Drawable iconBackground(Context ctx, int size, int displayId) {
        return Theme.of(displayId).retro() ? Bevel.taskButton(ctx)
                : Ui.ripple(ctx, 0x00000000, size / 2);
    }

    static View iconFor(Context ctx, String key, int size, int displayId) {
        int mark = key.indexOf(RunningTasks.WINDOW_MARK);
        String pkg = mark < 0 ? key : key.substring(0, mark);
        int window = -1;
        if (mark >= 0) {
            try {
                window = Integer.parseInt(key.substring(mark + 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        final int taskId = window;
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable art = pm.getApplicationIcon(pkg);
            ImageView view = new ImageView(ctx);
            view.setImageDrawable(art);
            int inset = Math.max(1, size / 8);
            view.setPadding(inset, inset, inset, inset);
            view.setContentDescription(pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)).toString());
            // What this icon stands for, read back by the marks and by anything else that asks an
            // icon what it is. The launcher's own icons carry an item info here; ours carry this.
            view.setTag(pkg);
            view.setBackground(iconBackground(ctx, size, displayId));
            view.setOnClickListener(v -> {
                // A window icon is that window; the app's own icon, when it has several, is its
                // first - always the same one, whichever is in front.
                int target = taskId >= 0 ? taskId : RunningTasks.firstWindow(pkg, displayId);
                // That window already in front: minimised, as a desktop taskbar does, rather
                // than opened again over itself.
                if (TaskbarApps.minimizeIfFront(ctx, pkg, target, displayId)) {
                    return;
                }
                if (target >= 0 && TaskOverview.bringToFront(target, displayId)) {
                    return;
                }
                if (TaskbarApps.moveFromTablet(ctx, pkg, displayId)) {
                    return;
                }
                try {
                    Intent intent = pm.getLaunchIntentForPackage(pkg);
                    if (intent == null) {
                        return;
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent, QuickTiles.launchOptions(displayId));
                } catch (Throwable t) {
                    L.d("taskbar running: could not open " + pkg + " (" + t + ")");
                }
            });
            // The same menu a pinned icon gives, for the same reason: from here on the bar is
            // one row, and one row should not behave two ways.
            view.setOnLongClickListener(v -> TaskbarApps.showMenu(v, pkg,
                    android.os.Process.myUserHandle(), displayId,
                    taskId >= 0 ? taskId : RunningTasks.firstWindow(pkg, displayId)));
            view.setOnTouchListener(new Press());
            TaskbarPreview.attach(view, pkg, displayId, taskId);
            TaskbarKeys.focusable(view, displayId);
            return view;
        } catch (Throwable t) {
            // An app we cannot draw is an app we leave out.
            return null;
        }
    }
}
