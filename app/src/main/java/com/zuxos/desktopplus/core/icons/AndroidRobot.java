package com.zuxos.desktopplus.core.icons;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
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
 * eyes shut, z's drifting up, antennae sagging a little more each second until they hang limp,
 * and a minute on a thought bubble with a dream in it - until the next press.
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
        mBubble.setColor(BUBBLE);
        mBubble.setStyle(Paint.Style.FILL);
        mBubbleEdge.setColor(BUBBLE_EDGE);
        mBubbleEdge.setStyle(Paint.Style.STROKE);
        mBubbleEdge.setStrokeWidth(0.3f);
        mDream.setStyle(Paint.Style.FILL);
        mDreamLine.setStyle(Paint.Style.STROKE);
        mDreamLine.setStrokeCap(Paint.Cap.ROUND);
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

        // Antennae, angled out from the top of the dome; asleep, they droop outward, and go on
        // sagging a little more each second until they hang limp, bent by their own weight.
        long now = android.os.SystemClock.uptimeMillis();
        float limp = mSleep > 0f ? limp(now) : 0f;
        float droop = mSleep * (DOZE_DEG + (LIMP_DEG - DOZE_DEG) * limp);
        float curl = mSleep * limp * CURL_DEG;
        drawAntenna(canvas, -1f, droop, curl);
        drawAntenna(canvas, 1f, droop, curl);
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
            long dream = now - mAsleepSince - DREAM_AFTER_MS;
            // The z's give way to the dream as its bubble comes up.
            float zs = dream <= 0 ? 1f : 1f - Math.min(1f, dream / (float) POP_MS);
            if (zs > 0f) {
                drawZs(canvas, zs);
            }
            if (dream > 0) {
                drawDream(canvas, dream);
            }
        }
        canvas.restoreToCount(saved);
        scheduleNext();
    }

    /** Where an antenna's base is, on the dome's top. */
    private static final float ANTENNA_Y = 9.6f;
    /** How long an antenna is, base to tip. */
    private static final float ANTENNA = 4.39f;
    /** The left antenna's direction awake: up and out, 120 degrees round from the right. */
    private static final double ANTENNA_UP = Math.toRadians(-120.0);

    /**
     * One antenna, its tip turned {@code droop} degrees outward from upright, and its root
     * {@code curl} degrees less: limp, it leaves the head still pointing out and bends down
     * toward its tip, as a thing sags under its own weight - curving away from the dome, never
     * into it.
     *
     * @param side -1 for the left one, 1 for the right
     */
    private void drawAntenna(Canvas canvas, float side, float droop, float curl) {
        double tip = ANTENNA_UP - Math.toRadians(droop);
        double root = ANTENNA_UP - Math.toRadians(droop - curl);
        float x = 12f + side * 4.8f;
        float tipX = x - side * (float) Math.cos(tip) * ANTENNA;
        float tipY = ANTENNA_Y + (float) Math.sin(tip) * ANTENNA;
        float bendX = x - side * (float) Math.cos(root) * ANTENNA * 0.55f;
        float bendY = ANTENNA_Y + (float) Math.sin(root) * ANTENNA * 0.55f;
        mAntenna.reset();
        mAntenna.moveTo(x, ANTENNA_Y);
        mAntenna.quadTo(bendX, bendY, tipX, tipY);
        canvas.drawPath(mAntenna, mLine);
    }

    // --- Asleep ------------------------------------------------------------------------------

    /** How long nothing is pressed before it falls asleep. */
    private static final long IDLE_MS = 30_000L;
    /** One z's way up, from the head to gone. */
    private static final long Z_MS = 2400L;
    /** How long it takes to fall asleep: slow, as eyes get heavy. */
    private static final long DOZE_MS = 1200L;
    /** How far the antennae turn outward as it falls asleep, in degrees. */
    private static final float DOZE_DEG = 35f;
    /** How far their tips have turned once they hang limp: out, and down past level. */
    private static final float LIMP_DEG = 80f;
    /** How much less a limp antenna's root has turned than its tip: the bend of the sag. */
    private static final float CURL_DEG = 18f;
    /** The antennae sag once a second... */
    private static final long SAG_EVERY_MS = 1000L;
    /** ...each sag a little less than the last, by this much... */
    private static final float SAG_KEEP = 0.8f;
    /** ...until, after this many, they can sag no more. */
    private static final int SAGS = 12;
    /** One sag: a drop with a small bob at its end, as a limp thing settles. */
    private static final long SAG_MS = 650L;
    private static final android.animation.TimeInterpolator SAG = Motion.spring(0.55f);
    private final Path mAntenna = new Path();

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
     * How limp the antennae are, 0 to 1: once asleep they sag a step each second, each step
     * smaller than the last, until the last one leaves them hanging.
     */
    private float limp(long now) {
        long t = now - mAsleepSince - DOZE_MS;
        if (t <= 0) {
            return 0f;
        }
        int step = (int) (t / SAG_EVERY_MS);
        if (step >= SAGS) {
            return 1f;
        }
        float from = sagged(step);
        float to = sagged(step + 1);
        float f = Math.min(1f, (t - step * SAG_EVERY_MS) / (float) SAG_MS);
        return from + (to - from) * SAG.getInterpolation(f);
    }

    /** How limp after {@code steps} sags: most of the way early, the last sags only small. */
    private static float sagged(int steps) {
        return (float) ((1.0 - Math.pow(SAG_KEEP, steps)) / (1.0 - Math.pow(SAG_KEEP, SAGS)));
    }

    /** Whether the antennae are mid-sag: the few frames a second that move. */
    private boolean sagging(long now) {
        long t = now - mAsleepSince - DOZE_MS;
        return t > 0 && t < SAGS * SAG_EVERY_MS && t % SAG_EVERY_MS < SAG_MS;
    }

    /**
     * Three z's drifting up and to the right from above the dome, each a third of the way
     * behind the last: growing as they rise, fading in and then out.
     *
     * @param fade 1, down to 0 as the dream's bubble takes their place
     */
    private void drawZs(Canvas canvas, float fade) {
        long t = android.os.SystemClock.uptimeMillis() - mAsleepSince;
        // The robot's own alpha (the bar fading it) on the z's too.
        int alpha = mLine.getAlpha();
        for (int i = 0; i < 3; i++) {
            float p = ((t + i * Z_MS / 3f) % Z_MS) / Z_MS;
            float size = (0.9f + 1.3f * p) * mSleep;
            float x = 17.5f + 4.5f * p;
            float y = 9.5f - 9f * p;
            // In over the first fifth, out over the last two.
            float in = p < 0.2f ? p / 0.2f : p > 0.6f ? (1f - p) / 0.4f : 1f;
            mZ.setAlpha(Math.round(alpha * in * fade * mSleep));
            canvas.drawLine(x - size, y - size, x + size, y - size, mZ);
            canvas.drawLine(x + size, y - size, x - size, y + size, mZ);
            canvas.drawLine(x - size, y + size, x + size, y + size, mZ);
        }
    }

    // --- Dreaming ----------------------------------------------------------------------------

    /** How long it sleeps before it dreams. */
    private static final long DREAM_AFTER_MS = 60_000L;
    /** One puff of the thought bubble coming up, on a bouncy spring. */
    private static final long POP_MS = 560L;
    private static final android.animation.TimeInterpolator POP = Motion.spring(0.6f);
    /** The bubble's two small puffs and its cloud come up one after the other, this far apart. */
    private static final long POP_GAP_MS = 170L;
    /** The dream fades in once the cloud is up, over this long. */
    private static final long DREAM_IN_MS = 420L;
    /** One sheep's run across the dream, over the fence. */
    private static final long SHEEP_MS = 2800L;
    /** The sheep's size against the shapes it is drawn from, so it reads on a small button. */
    private static final float SHEEP_SIZE = 1.35f;
    /** The twinkles' slow pulse. */
    private static final long TWINKLE_MS = 1700L;
    /** The bubble: as white as a thought, edged in the z's purple. */
    private static final int BUBBLE = 0xF5FFFFFF;
    private static final int BUBBLE_EDGE = 0x66B388FF;
    /** The sheep: fleece a pale lavender, so it shows on the white; face and legs dark. */
    private static final int FLEECE = 0xFFE4D8FF;
    private static final int FACE = 0xFF4A4458;
    /** The cloud the dream is in, on the grid: puffs on a rounded base, above the head. */
    private static final Path CLOUD = cloud();
    /** Where the cloud grows from: its lower right, where the small puffs lead up to it. */
    private static final float CLOUD_PIVOT_X = 17.0f;
    private static final float CLOUD_PIVOT_Y = 5.6f;
    private final Paint mBubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBubbleEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDream = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDreamLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static Path cloud() {
        Path cloud = new Path();
        cloud.addRoundRect(new RectF(5.6f, 2.0f, 17.4f, 6.2f), 2.1f, 2.1f, Path.Direction.CW);
        Path puff = new Path();
        float[][] puffs = {{8.0f, 2.4f, 1.9f}, {11.4f, 1.8f, 2.3f}, {14.8f, 2.5f, 1.8f},
                {6.6f, 4.4f, 1.6f}, {16.4f, 4.4f, 1.6f}};
        for (float[] p : puffs) {
            puff.reset();
            puff.addCircle(p[0], p[1], p[2], Path.Direction.CW);
            cloud.op(puff, Path.Op.UNION);
        }
        return cloud;
    }

    /**
     * The thought bubble and its dream, {@code ms} into it: a small puff, a bigger one and then
     * the cloud pop up from the head on a spring, one after the other; the dream fades in inside
     * it - an electric sheep hopping a fence, again and again, under two slow twinkles.
     */
    private void drawDream(Canvas canvas, long ms) {
        int alpha = mLine.getAlpha();
        float small = pop(ms, 0L) * mSleep;
        float mid = pop(ms, POP_GAP_MS) * mSleep;
        float big = pop(ms, 2 * POP_GAP_MS) * mSleep;
        mBubble.setAlpha(Math.round((BUBBLE >>> 24) * alpha / 255f));
        mBubbleEdge.setAlpha(Math.round((BUBBLE_EDGE >>> 24) * alpha / 255f));
        drawPuff(canvas, 17.4f, 8.4f, 0.42f * small);
        drawPuff(canvas, 18.9f, 7.0f, 0.72f * mid);
        if (big <= 0f) {
            return;
        }
        int saved = canvas.save();
        canvas.scale(big, big, CLOUD_PIVOT_X, CLOUD_PIVOT_Y);
        canvas.drawPath(CLOUD, mBubble);
        canvas.drawPath(CLOUD, mBubbleEdge);
        long scene = ms - 2 * POP_GAP_MS - POP_MS / 2;
        float in = scene <= 0 ? 0f : Math.min(1f, scene / (float) DREAM_IN_MS);
        if (in > 0f) {
            canvas.clipPath(CLOUD);
            drawScene(canvas, scene, Math.round(alpha * in * mSleep));
        }
        canvas.restoreToCount(saved);
    }

    /** How far up one of the bubble's parts is, {@code delay} after the first: 0, then 1. */
    private static float pop(long ms, long delay) {
        long t = ms - delay;
        return t <= 0 ? 0f : POP.getInterpolation(Math.min(1f, t / (float) POP_MS));
    }

    private void drawPuff(Canvas canvas, float x, float y, float r) {
        if (r > 0f) {
            canvas.drawCircle(x, y, r, mBubble);
            canvas.drawCircle(x, y, r, mBubbleEdge);
        }
    }

    /** The dream, inside the cloud: the ground, the fence, the twinkles and the sheep. */
    private void drawScene(Canvas canvas, long ms, int alpha) {
        // The ground, faint, and the fence in the robot's own green.
        mDreamLine.setStrokeWidth(0.2f);
        mDreamLine.setColor(Z_PURPLE);
        mDreamLine.setAlpha(alpha / 4);
        canvas.drawLine(4.8f, 5.5f, 18.2f, 5.5f, mDreamLine);
        mDreamLine.setStrokeWidth(0.3f);
        mDreamLine.setColor(GREEN);
        mDreamLine.setAlpha(alpha * 4 / 5);
        canvas.drawLine(10.95f, 4.1f, 10.95f, 5.5f, mDreamLine);
        canvas.drawLine(11.85f, 4.1f, 11.85f, 5.5f, mDreamLine);
        canvas.drawLine(10.5f, 4.45f, 12.3f, 4.45f, mDreamLine);
        canvas.drawLine(10.5f, 5.0f, 12.3f, 5.0f, mDreamLine);
        // Two twinkles, out of step, slowly brightening and dimming.
        mDreamLine.setStrokeWidth(0.22f);
        mDreamLine.setColor(Z_PURPLE);
        drawTwinkle(canvas, 7.3f, 2.3f, ms, 0f, alpha);
        drawTwinkle(canvas, 15.4f, 2.0f, ms, 0.5f, alpha);
        drawSheep(canvas, ms, alpha);
    }

    private void drawTwinkle(Canvas canvas, float x, float y, long ms, float phase, int alpha) {
        double turn = 2.0 * Math.PI * ((ms / (double) TWINKLE_MS) + phase);
        float glow = 0.5f + 0.5f * (float) Math.sin(turn);
        float r = 0.2f + 0.16f * glow;
        mDreamLine.setAlpha(Math.round(alpha * (0.3f + 0.7f * glow)));
        canvas.drawLine(x - r, y, x + r, y, mDreamLine);
        canvas.drawLine(x, y - r, x, y + r, mDreamLine);
    }

    /**
     * The sheep: trotting in from the cloud's left edge with a small bob, leaping the fence on an
     * arc - nose up rising, nose down falling, legs tucked - and trotting out at the right, where
     * the next one is already coming.
     */
    private void drawSheep(Canvas canvas, long ms, int alpha) {
        float p = (ms % SHEEP_MS) / (float) SHEEP_MS;
        float x = 4.0f + 15.0f * p;
        // Over the fence between 9.4 and 13.4, peaking 1.5 up; standing, its hooves on the
        // ground at 5.5.
        float u = (x - 9.4f) / 4.0f;
        boolean air = u > 0f && u < 1f;
        float y = 5.5f - 0.95f * SHEEP_SIZE;
        float tilt = 0f;
        if (air) {
            y -= 1.5f * 4f * u * (1f - u);
            tilt = (0.5f - u) * 28f;
        } else {
            y -= 0.15f * Math.abs((float) Math.sin(p * Math.PI * 10.0));
        }
        int saved = canvas.save();
        canvas.translate(x, y);
        canvas.rotate(-tilt);
        canvas.scale(SHEEP_SIZE, SHEEP_SIZE);
        float leg = air ? 0.3f : 0.6f;
        mDreamLine.setStrokeWidth(0.28f);
        mDreamLine.setColor(FACE);
        mDreamLine.setAlpha(alpha);
        canvas.drawLine(-0.45f, 0.35f, -0.45f, 0.35f + leg, mDreamLine);
        canvas.drawLine(0.4f, 0.35f, 0.4f, 0.35f + leg, mDreamLine);
        mDream.setColor(FLEECE);
        mDream.setAlpha(alpha);
        canvas.drawCircle(-0.45f, 0f, 0.5f, mDream);
        canvas.drawCircle(0f, -0.18f, 0.55f, mDream);
        canvas.drawCircle(0.42f, 0f, 0.5f, mDream);
        canvas.drawCircle(0f, 0.15f, 0.55f, mDream);
        mDream.setColor(FACE);
        mDream.setAlpha(alpha);
        mSheepHead.set(0.74f, -0.27f, 1.16f, 0.07f);
        canvas.drawOval(mSheepHead, mDream);
        canvas.restoreToCount(saved);
    }

    private final RectF mSheepHead = new RectF();

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
        // Asleep, the z's drift and the dream plays on - 30 frames a second is plenty for
        // things that slow - but every frame while an antenna sags or the bubble pops up, and
        // while falling asleep or waking; awake, nothing until the next blink.
        long dream = now - mAsleepSince - DREAM_AFTER_MS;
        boolean quick = sagging(now) || (dream >= 0 && dream < 2 * POP_GAP_MS + POP_MS);
        scheduleSelf(mTick, mSleep >= 1f ? now + (quick ? 16L : 33L)
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
