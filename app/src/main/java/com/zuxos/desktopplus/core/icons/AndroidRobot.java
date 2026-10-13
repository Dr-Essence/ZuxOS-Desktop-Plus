package com.zuxos.desktopplus.core.icons;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import com.zuxos.desktopplus.core.motion.Motion;

/**
 * The Android head, as a start button: the green half-dome with its antennae and eyes - the mark
 * Android itself uses since 2019 - with no body, so it reads as a logo rather than as one more
 * app on the bar. It blinks every few seconds, opens its eyes wide while the drawer is up and
 * narrows them while recents is, and falls asleep when nothing has been pressed for a while -
 * eyes shut, antennae drooping, z's drifting up - until the next press.
 *
 * <p>Built from shapes on a 24-unit grid rather than shipped as an image - the module has no
 * resources of its own inside the launcher's process - and scaled to whatever bounds it is
 * given, centred, keeping its proportions.
 */
public final class AndroidRobot extends Drawable {

    /** Android's own green. */
    public static final int GREEN = 0xFF3DDC84;
    /** The sleep's z's. */
    private static final int Z_PURPLE = 0xFFB388FF;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEye = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The z's of its sleep: a soft purple, so the robot itself stays green (owner). */
    private final Paint mZ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mScale = 1f;
    private float mLeft;
    private float mTop;

    /** How long one blink takes, shut and open again. */
    private static final long BLINK_MS = 220L;
    /** The pause between the two halves of a double blink. */
    private static final long DOUBLE_GAP_MS = 90L;
    /** Whether the blink under way is followed straight away by a second. */
    private boolean mDouble;
    private final java.util.Random mRandom = new java.util.Random();
    /** When the next blink starts, on the uptime clock {@link #scheduleSelf} uses; 0 = not set. */
    private long mBlinkAt;
    private final Runnable mTick = this::invalidateSelf;

    public AndroidRobot() {
        mFill.setColor(GREEN);
        mFill.setStyle(Paint.Style.FILL);
        mLine.setColor(GREEN);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mEye.setColor(0xFFFFFFFF);
        mEye.setStyle(Paint.Style.FILL);
        mZ.setColor(Z_PURPLE);
        mZ.setStyle(Paint.Style.STROKE);
        mZ.setStrokeCap(Paint.Cap.ROUND);
        mZ.setStrokeJoin(Paint.Join.ROUND);
        mZ.setStrokeWidth(0.9f);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        float size = Math.min(bounds.width(), bounds.height());
        mScale = size / 24f;
        mLeft = bounds.left + (bounds.width() - size) / 2f;
        mTop = bounds.top + (bounds.height() - size) / 2f;
    }

    @Override
    public void draw(Canvas canvas) {
        int saved = canvas.save();
        canvas.translate(mLeft, mTop);
        canvas.scale(mScale, mScale);
        // The scale is on the canvas, so the antennae are stroked in grid units.
        mLine.setStrokeWidth(1.4f);

        // Antennae, angled out from the top of the dome; asleep, they droop outward.
        float droop = mSleep * 1.6f;
        canvas.drawLine(7.2f, 9.6f, 5.0f - droop * 0.6f, 5.8f + droop, mLine);
        canvas.drawLine(16.8f, 9.6f, 19.0f + droop * 0.6f, 5.8f + droop, mLine);
        // The dome: the top half of a disc, flat side down, filling the width and centred on
        // the grid so the logo sits in the middle of the button.
        canvas.drawArc(new RectF(1.5f, 7.7f, 22.5f, 28.7f), 180f, 180f, true, mFill);
        // Eyes, which blink - grow while the drawer is open, shrink while recents is.
        // Asleep, the lids close to a line.
        float open = Math.max(0.08f, openness() * (1f - mSleep));
        float r = 1.25f * Math.max(0.2f, mLook >= 0f
                ? 1f + (WIDE_SCALE - 1f) * mLook : 1f + (1f - SMALL_SCALE) * mLook);
        canvas.drawOval(new RectF(8.0f - r, 13.8f - r * open, 8.0f + r, 13.8f + r * open), mEye);
        canvas.drawOval(new RectF(16.0f - r, 13.8f - r * open, 16.0f + r, 13.8f + r * open),
                mEye);

        if (mSleep > 0f) {
            drawZs(canvas);
        }
        canvas.restoreToCount(saved);
        scheduleNext();
    }

    // --- Asleep ------------------------------------------------------------------------------

    /** How long nothing is pressed before it falls asleep. */
    private static final long IDLE_MS = 30_000L;
    /** One z's way up, from the head to gone. */
    private static final long Z_MS = 2400L;
    /** How long it takes to fall asleep: slow, as eyes get heavy. */
    private static final long DOZE_MS = 1200L;
    /** 0 awake, 1 fast asleep. */
    private float mSleep;
    private android.animation.ValueAnimator mSleepAnim;
    /** When it started sleeping, for the z's. */
    private long mAsleepSince;
    private boolean mSleeps = true;
    private final Runnable mDoze = this::fallAsleep;

    /** Whether it falls asleep at all (the module's own switch). */
    public void setSleeps(boolean sleeps) {
        mSleeps = sleeps;
        if (!sleeps) {
            wake();
            unscheduleSelf(mDoze);
        } else {
            poke();
        }
    }

    /**
     * Something was pressed: awake now if it was asleep, and its 30 seconds begin again. Cheap
     * enough for every press.
     */
    public void poke() {
        wake();
        if (mSleeps && getCallback() != null && isVisible()) {
            unscheduleSelf(mDoze);
            scheduleSelf(mDoze, android.os.SystemClock.uptimeMillis() + IDLE_MS);
        }
    }

    private void fallAsleep() {
        if (!mSleeps || mSleep >= 1f || mLookTarget != EYES_NORMAL) {
            // A drawer or recents is up: it is watching, not idle.
            return;
        }
        mAsleepSince = android.os.SystemClock.uptimeMillis();
        animateSleep(1f, DOZE_MS, Motion.EASE);
    }

    /** Eyes open at once on the module's spring, the z's gone with them. */
    private void wake() {
        if (mSleep > 0f || (mSleepAnim != null && mSleepAnim.isRunning())) {
            animateSleep(0f, Motion.SPRING_MS, Motion.SPRING);
        }
    }

    private void animateSleep(float to, long ms, android.animation.TimeInterpolator curve) {
        if (mSleepAnim != null) {
            mSleepAnim.cancel();
        }
        mSleepAnim = android.animation.ValueAnimator.ofFloat(mSleep, to);
        mSleepAnim.setDuration(ms);
        mSleepAnim.setInterpolator(curve);
        mSleepAnim.addUpdateListener(a -> {
            mSleep = Math.max(0f, Math.min(1f, (float) a.getAnimatedValue()));
            invalidateSelf();
        });
        mSleepAnim.start();
    }

    /**
     * Three z's drifting up and to the right from above the dome, each a third of the way
     * behind the last: growing as they rise, fading in and then out.
     */
    private void drawZs(Canvas canvas) {
        long t = android.os.SystemClock.uptimeMillis() - mAsleepSince;
        // The robot's own alpha (the bar fading it) on the z's too.
        int alpha = mLine.getAlpha();
        for (int i = 0; i < 3; i++) {
            float p = ((t + i * Z_MS / 3f) % Z_MS) / Z_MS;
            float size = (0.9f + 1.3f * p) * mSleep;
            float x = 17.5f + 4.5f * p;
            float y = 9.5f - 9f * p;
            // In over the first fifth, out over the last two.
            float fade = p < 0.2f ? p / 0.2f : p > 0.6f ? (1f - p) / 0.4f : 1f;
            mZ.setAlpha(Math.round(alpha * fade * mSleep));
            canvas.drawLine(x - size, y - size, x + size, y - size, mZ);
            canvas.drawLine(x + size, y - size, x - size, y + size, mZ);
            canvas.drawLine(x - size, y + size, x + size, y + size, mZ);
        }
    }

    /**
     * How open the eyes are, 1 to nearly shut.
     *
     * <p>Lids close quickly and open a little slower, on eased curves - the way an eye actually
     * blinks - rather than the straight V a linear blink makes. About one blink in five is a
     * double blink, which is what keeps it reading as alive rather than as a timer.
     */
    private float openness() {
        long now = android.os.SystemClock.uptimeMillis();
        if (mBlinkAt == 0) {
            mBlinkAt = now + nextGap();
        }
        if (now < mBlinkAt) {
            return 1f;
        }
        float t = (now - mBlinkAt) / (float) BLINK_MS;
        if (t >= 1f) {
            if (mDouble) {
                mDouble = false;
                mBlinkAt = now + DOUBLE_GAP_MS;
            } else {
                mDouble = mRandom.nextInt(5) == 0;
                mBlinkAt = now + nextGap();
            }
            return 1f;
        }
        // Closing takes the first 40% of the blink, opening the rest.
        float shut;
        if (t < 0.4f) {
            float x = t / 0.4f;
            shut = x * x * (3f - 2f * x);
        } else {
            float x = (t - 0.4f) / 0.6f;
            shut = 1f - x * x * (3f - 2f * x);
        }
        return Math.max(0.1f, 1f - shut);
    }

    /** Eyes as they always are. */
    public static final int EYES_NORMAL = 0;
    /** Eyes wide: the drawer is open. */
    public static final int EYES_WIDE = 1;
    /** Eyes narrowed: recents is up. */
    public static final int EYES_SMALL = -1;

    /** How much bigger the eyes are while the drawer is open. */
    private static final float WIDE_SCALE = 1.45f;
    /** How much smaller the eyes are while recents is up. */
    private static final float SMALL_SCALE = 0.6f;
    /** 1 = wide, 0 = normal, -1 = small; overshoots a touch on the way, which is the spring. */
    private float mLook;
    private int mLookTarget = EYES_NORMAL;
    private android.animation.ValueAnimator mLookAnim;

    /**
     * Eyes wide while the app drawer is open, narrowed while recents is up, and back to normal
     * when either goes.
     *
     * <p>On the module's spring, from wherever they are now, so opening and closing quickly in a
     * row turns the motion round smoothly instead of jumping.
     *
     * @param look {@link #EYES_NORMAL}, {@link #EYES_WIDE} or {@link #EYES_SMALL}
     */
    public void setEyes(int look) {
        if (look != EYES_NORMAL) {
            poke();
        }
        if (look == mLookTarget) {
            return;
        }
        mLookTarget = look;
        if (mLookAnim != null) {
            mLookAnim.cancel();
        }
        mLookAnim = android.animation.ValueAnimator.ofFloat(mLook, look);
        mLookAnim.setDuration(Motion.SPRING_MS);
        mLookAnim.setInterpolator(Motion.SPRING);
        mLookAnim.addUpdateListener(a -> {
            mLook = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        mLookAnim.start();
    }

    /**
     * Eyes as wide as the drawer is open, 0 closed to 1 open, set by the drawer on every frame it
     * moves - no spring of their own, so they are exactly where the drawer is, wherever a finger
     * holds it. Narrowed for recents, they stay so until the drawer opens past closed.
     */
    public void setDrawerOpen(float open) {
        float look = Math.max(0f, Math.min(1f, open));
        if (look > 0f && mSleep > 0f) {
            poke();
        }
        if (mLookTarget == EYES_SMALL && look <= 0f) {
            return;
        }
        if (mLookAnim != null) {
            mLookAnim.cancel();
            mLookAnim = null;
        }
        // Where the next spring starts from and whether it is needed, as setEyes reads it.
        mLookTarget = look >= 0.5f ? EYES_WIDE : EYES_NORMAL;
        if (look != mLook) {
            mLook = look;
            invalidateSelf();
        }
    }

    /** Somewhere between 3.5 and 6.5 seconds: a fixed rhythm looks like a machine. */
    private long nextGap() {
        return 3500L + mRandom.nextInt(3000);
    }

    /**
     * Asks to be drawn again when something will have changed: every frame during a blink, and
     * otherwise not until the next one starts. Only while visible and attached to a view, so a
     * hidden bar has nothing ticking.
     */
    private void scheduleNext() {
        if (getCallback() == null || !isVisible()) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        unscheduleSelf(mTick);
        // Asleep, the z's drift on - 30 frames a second is plenty for something that slow -
        // and while falling asleep or waking, every frame; awake, nothing until the next blink.
        scheduleSelf(mTick, mSleep >= 1f ? now + 33L
                : mSleep > 0f || now >= mBlinkAt ? now + 16L : mBlinkAt);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) {
            unscheduleSelf(mTick);
            unscheduleSelf(mDoze);
        } else if (changed) {
            invalidateSelf();
            poke();
        }
        return changed;
    }

    @Override
    public void setAlpha(int alpha) {
        mFill.setAlpha(alpha);
        mLine.setAlpha(alpha);
        mEye.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        // Deliberately ignored: the launcher tints its button's icon to match the bar, and the
        // robot is green whatever the bar is.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
