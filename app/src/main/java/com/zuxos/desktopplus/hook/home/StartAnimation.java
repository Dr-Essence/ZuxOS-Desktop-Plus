package com.zuxos.desktopplus.hook.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Storage;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.AndroidRobot;
import com.zuxos.desktopplus.core.icons.PixelIcons;
import com.zuxos.desktopplus.core.motion.FrameRate;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.hook.Overlays;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Desktop Mode+ coming up on the monitor (#8): the Android robot springs in, opens its eyes wide
 * and the name rises under it, then all of it fades away over the desktop - a second and a half.
 *
 * <p>Once per connect. It plays the first time a desktop attaches to a display in this process,
 * and again on that display only after it was removed: a replug, not the activity being made
 * again. Not after the launcher restarted itself for a changed setting ({@link #skipNext}).
 *
 * <p>A window of its own over the desktop, which is live under it the whole time; it takes no
 * touch and no focus. In Retro nothing moves: the pixel robot and the name on the classic teal,
 * for a second, then gone.
 */
public final class StartAnimation {

    /** Written just before the launcher ends itself for a setting; read at the next attach. */
    private static final String SKIP_FILE = "start_animation_skip";
    private static final long SKIP_FRESH_MS = 30_000L;
    private static final long HOLD_MS = 1000L;
    private static final long RETRO_MS = 1000L;
    /** Windows 98's desktop. */
    private static final int RETRO_DESKTOP = 0xFF008080;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Displays it has played on since they were last added. */
    private static final Set<Integer> PLAYED = new HashSet<>();
    private static boolean sSkipRead;

    private static View sRoot;
    private static WindowManager sWm;

    private StartAnimation() {
    }

    /** The display went away: the next desktop on it is a new connect. */
    public static void forget(int displayId) {
        PLAYED.remove(displayId);
    }

    /** The launcher is about to restart itself for a setting: no animation as it comes back. */
    static void skipNext(Context ctx) {
        try {
            Storage.write(Storage.file(ctx, SKIP_FILE), Long.toString(System.currentTimeMillis()));
        } catch (Throwable ignored) {
            // At worst it plays once more.
        }
    }

    /** Main thread, as the monitor's desktop attaches. */
    public static void onAttached(Activity activity, int displayId) {
        try {
            if (!PLAYED.add(displayId) || skipped(activity) || !Cfg.startAnimation()) {
                return;
            }
            show(activity, displayId);
        } catch (Throwable t) {
            L.d("start animation: not shown (" + t + ")");
        }
    }

    /** Whether the launcher has just restarted itself; read once per process. */
    private static boolean skipped(Context ctx) {
        if (sSkipRead) {
            return false;
        }
        sSkipRead = true;
        File marker = Storage.file(ctx, SKIP_FILE);
        String raw = Storage.read(marker);
        if (raw == null) {
            return false;
        }
        if (!marker.delete()) {
            L.d("start animation: marker not removed");
        }
        try {
            return System.currentTimeMillis() - Long.parseLong(raw.trim()) < SKIP_FRESH_MS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void show(Activity activity, int displayId) {
        dismiss();
        Context ctx = Overlays.windowContext(activity);
        if (ctx.getDisplay() == null || ctx.getDisplay().getDisplayId() != displayId) {
            // Only on the monitor itself: the fallback context would open it on the tablet.
            return;
        }
        Theme theme = Theme.of(displayId);
        boolean retro = theme.retro();
        boolean moves = theme.animates();

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(retro ? RETRO_DESKTOP : 0xE6000000);
        LinearLayout mark = new LinearLayout(ctx);
        mark.setOrientation(LinearLayout.VERTICAL);
        mark.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView robot = new ImageView(ctx);
        Drawable face = retro ? PixelIcons.robot() : new AndroidRobot();
        robot.setImageDrawable(face);
        int size = Ui.dp(ctx, retro ? 96 : 120);
        mark.addView(robot, new LinearLayout.LayoutParams(size, size));
        TextView name = new TextView(ctx);
        name.setText("Desktop Mode+");
        name.setTextColor(retro ? Color.WHITE : 0xFFF2F2F2);
        name.setTextSize(retro ? 22 : 26);
        name.setLetterSpacing(retro ? 0f : 0.02f);
        name.setTypeface(theme.font(ctx));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.dp(ctx, 14);
        mark.addView(name, nlp);
        root.addView(mark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        WindowManager wm = Overlays.windowManager(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(0);
        lp.setTitle("ZuxOS Desktop Plus start");
        FrameRate.forWindow(lp, wm.getDefaultDisplay());
        FrameRate.forView(root);
        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            L.d("start animation: no window (" + t + ")");
            return;
        }
        sRoot = root;
        sWm = wm;
        L.i("start animation on display " + displayId + (retro ? " (Retro)" : ""));

        if (!moves) {
            MAIN.postDelayed(StartAnimation::dismiss, RETRO_MS);
            return;
        }
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Motion.SHORT).setInterpolator(Motion.EASE).start();
        robot.setScaleX(0.6f);
        robot.setScaleY(0.6f);
        robot.setAlpha(0f);
        robot.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Motion.SPRING_MS)
                .setInterpolator(Motion.spring(0.7f))
                .withEndAction(() -> ((AndroidRobot) face).setEyes(AndroidRobot.EYES_WIDE))
                .start();
        name.setAlpha(0f);
        name.setTranslationY(Ui.dp(ctx, 16));
        name.animate().alpha(1f).translationY(0f).setStartDelay(Motion.SHORT)
                .setDuration(Motion.IOS_MS).setInterpolator(Motion.IOS).start();
        MAIN.postDelayed(() -> {
            if (sRoot != root) {
                return;
            }
            ((AndroidRobot) face).setEyes(AndroidRobot.EYES_NORMAL);
            mark.animate().scaleX(1.04f).scaleY(1.04f).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.EXIT).start();
            root.animate().alpha(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EXIT)
                    .withEndAction(StartAnimation::dismiss).start();
        }, HOLD_MS + Motion.SHORT);
    }

    /** Gone now: at its end, or with the monitor's desktop. */
    public static void dismiss() {
        View root = sRoot;
        WindowManager wm = sWm;
        sRoot = null;
        sWm = null;
        if (root == null || wm == null) {
            return;
        }
        root.animate().cancel();
        try {
            wm.removeViewImmediate(root);
        } catch (Throwable ignored) {
            // Already gone with its display.
        }
    }
}
