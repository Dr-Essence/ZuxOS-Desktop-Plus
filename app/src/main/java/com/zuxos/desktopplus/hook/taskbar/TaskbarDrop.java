package com.zuxos.desktopplus.hook.taskbar;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.DragPayload;
import com.zuxos.desktopplus.hook.Windows;
import com.zuxos.desktopplus.logic.PinList;
import com.zuxos.desktopplus.model.Item;

/**
 * Drop an app on the taskbar to pin it there: one of ours, or one dragged out of ZUI's taskbar
 * drawer ({@link ZuiBarDrop}).
 *
 * <p>The strip that catches the drop is ours and spans the bar. It is not the launcher's drag
 * layer: that view belongs to the launcher and may have a drag listener of its own, and a view
 * only has one - taking it would be breaking the launcher's own drag and drop to add ours.
 *
 * <p>Nothing here can be touched. It has no background and no listeners but the drag one, so every
 * press goes through it to the icons underneath; the only time you know it is there is when
 * something is being dragged over it.
 */
public final class TaskbarDrop {

    private TaskbarDrop() {
    }

    /** Puts the strip in, or takes it out when nothing may be pinned any more. */
    static void apply(ViewGroup dragLayer) {
        try {
            DropStrip strip = stripIn(dragLayer);
            if (!Cfg.enabled()) {
                if (strip != null) {
                    dragLayer.removeView(strip);
                }
                return;
            }
            if (strip == null) {
                add(dragLayer);
            } else {
                strip.place();
            }
        } catch (Throwable t) {
            L.d("taskbar drop: not installed (" + t + ")");
        }
    }

    /**
     * Where the bar on this display catches drops, on screen - the strip, over the bar's row -
     * or null while it is not showing.
     */
    public static RectF barOnScreen(int display) {
        DropStrip strip = stripOn(display);
        if (strip == null) {
            return null;
        }
        int[] at = new int[2];
        strip.getLocationOnScreen(at);
        return new RectF(at[0], at[1], at[0] + strip.getWidth(), at[1] + strip.getHeight());
    }

    /**
     * A drag over the bar from another window, at {@code screenX} - the line where it would land
     * - or gone from it, with a negative x.
     */
    public static void hintAt(int display, float screenX) {
        hintAt(display, screenX, null);
    }

    /**
     * The same, with the dragged {@code icon} drawn at the line: a drag of ZUX Home's own, whose
     * picture is drawn in ZUX Home's window, under the bar's.
     */
    public static void hintAt(int display, float screenX, Drawable icon) {
        DropStrip strip = stripOn(display);
        if (strip != null) {
            strip.hint(screenX < 0 ? -1f : screenX - strip.screenLeft(), icon);
        }
    }

    /** An app let go over the bar from another window, at {@code screenX}: pinned there. */
    public static boolean pinAt(int display, Item item, float screenX) {
        DropStrip strip = stripOn(display);
        return strip != null && strip.pin(item, screenX - strip.screenLeft());
    }

    /**
     * Why the bar on this display would not take {@code item}, in the words a drop there says it
     * with; null when it would (a pin already there moves).
     */
    static String refusal(int display, Item item) {
        DropStrip strip = stripOn(display);
        return strip == null ? "The taskbar is not showing" : strip.refusal(item);
    }

    /** A new layout pass on this display's bar: its touch region is worked out again. */
    static void relayoutBar(int display) {
        DropStrip strip = stripOn(display);
        if (strip != null && strip.getParent() instanceof View) {
            ((View) strip.getParent()).requestLayout();
        }
    }

    private static DropStrip stripOn(int display) {
        for (View root : Windows.roots()) {
            ViewGroup dragLayer = TaskbarTray.dragLayerOf(root);
            DropStrip strip = dragLayer == null ? null : stripIn(dragLayer);
            if (strip != null && strip.isShown() && strip.getWidth() > 0
                    && TaskbarTray.displayIdOf(strip) == display) {
                return strip;
            }
        }
        return null;
    }

    private static DropStrip stripIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            if (dragLayer.getChildAt(i) instanceof DropStrip) {
                return (DropStrip) dragLayer.getChildAt(i);
            }
        }
        return null;
    }

    private static void add(ViewGroup dragLayer) {
        View reference = TaskbarTray.rowReference(dragLayer);
        ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            return;
        }
        DropStrip strip = new DropStrip(dragLayer, reference);
        ((FrameLayout.LayoutParams) lp).width = ViewGroup.LayoutParams.MATCH_PARENT;
        // Appended, the way the tray is, rather than pushed in at the front of the launcher's own
        // children. Being on top costs nothing here: the strip is not clickable, so a touch goes
        // straight past it to whatever is underneath, and only a drag ever stops at it.
        dragLayer.addView(strip, lp);
        strip.place();
        L.i("taskbar drop: an app dropped on the bar is pinned to it");
    }

    /** The strip itself: a drag target, and a line showing where the app would land. */
    private static final class DropStrip extends View {

        private final ViewGroup mDragLayer;
        private final View mReference;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mWhere = -1f;
        /** The dragged icon, drawn at the line for a drag from another window; or null. */
        private Drawable mIcon;

        DropStrip(ViewGroup dragLayer, View reference) {
            super(dragLayer.getContext());
            mDragLayer = dragLayer;
            mReference = reference;
            setClickable(false);
            setFocusable(false);
            setOnDragListener(this::onDrag);
        }

        void place() {
            // The bar's row as it is now: the one found when the strip went in may have been the
            // keys' row, standing in while ZUI's icons were hidden for its drawer.
            View reference = TaskbarTray.rowReference(mDragLayer);
            if (reference == null) {
                reference = mReference;
            }
            ViewGroup.LayoutParams raw = getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams) || reference == null
                    || reference.getHeight() <= 0) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (!TaskbarTray.onRow(lp, reference, reference.getHeight(), Gravity.START)) {
                return;
            }
            setLayoutParams(lp);
        }

        private boolean onDrag(View v, DragEvent event) {
            Item zui = ZuiBarDrop.itemFor(event, TaskbarTray.displayIdOf(this));
            if (zui != null) {
                return onZuiDrag(event, zui);
            }
            if (!DragPayload.isOurs(event)) {
                return false;
            }
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION: {
                    DragPayload moving = movingPin(event);
                    if (moving != null) {
                        // A pin along the bar: the row opens a gap, so no line is needed.
                        TaskbarRunning.previewMove(mDragLayer, moving.item.key(),
                                where(event.getX()));
                        return true;
                    }
                    mWhere = event.getX();
                    mIcon = null;
                    invalidate();
                    return true;
                }
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    mWhere = -1f;
                    invalidate();
                    if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                        ZuiHomeDrop.pinEnded(event);
                    }
                    if (movingPin(event) != null && (event.getAction()
                            == DragEvent.ACTION_DRAG_EXITED || !event.getResult())) {
                        TaskbarRunning.clearPreview(mDragLayer);
                    }
                    return true;
                case DragEvent.ACTION_DROP:
                    mWhere = -1f;
                    invalidate();
                    return pin(DragPayload.of(event), event.getX());
                default:
                    return true;
            }
        }

        /**
         * ZUI's own drawer drag ({@link ZuiBarDrop}), let go here: pinned as one of ours is.
         * Anything not let go here was the shell's, as before.
         */
        private boolean onZuiDrag(DragEvent event, Item item) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_LOCATION:
                    mWhere = event.getX();
                    mIcon = null;
                    invalidate();
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    mWhere = -1f;
                    invalidate();
                    return true;
                case DragEvent.ACTION_DROP:
                    mWhere = -1f;
                    invalidate();
                    if (pin(item, event.getX())) {
                        ZuiBarDrop.pinned();
                        return true;
                    }
                    return false;
                case DragEvent.ACTION_DRAG_ENDED:
                    mWhere = -1f;
                    invalidate();
                    ZuiBarDrop.ended(event.getResult());
                    return true;
                default:
                    return true;
            }
        }

        void hint(float x, Drawable icon) {
            mWhere = x;
            mIcon = x < 0 ? null : icon;
            invalidate();
        }

        int screenLeft() {
            int[] at = new int[2];
            getLocationOnScreen(at);
            return at[0];
        }

        private boolean pin(DragPayload payload, float x) {
            return payload != null && pin(payload.item, x);
        }

        /** Why this would not be pinned here; null when it would (a pin moves along). */
        String refusal(Item item) {
            if (item == null) {
                return "Only apps and folders can be pinned here";
            }
            if (PinList.holds(TaskbarPins.pins(getContext()), item.key())) {
                return null;
            }
            if (item.pkg == null && item.type != Item.TYPE_FOLDER) {
                // A widget, or a shortcut with nothing but an intent. A folder is fine - it opens
                // rather than launches, and the bar knows how to do that.
                return "Only apps and folders can be pinned here";
            }
            if (item.pkg != null && TaskbarRunning.alreadyInTheBar(mDragLayer, item.pkg)) {
                // Said rather than done: pinning it would leave two of the same icon side by
                // side, and a drop that quietly does nothing looks like a drop that missed.
                return "That app is already in the taskbar";
            }
            return null;
        }

        private boolean pin(Item item, float x) {
            if (item == null) {
                return false;
            }
            if (PinList.holds(TaskbarPins.pins(getContext()), item.key())) {
                // Already here: a pin dragged along the bar, or the same app dropped again.
                // Either way it moves to where it was let go.
                TaskbarPins.move(getContext(), item.key(), where(x));
                TaskbarRunning.apply(mDragLayer);
                // Dropped back where it was, the row is not rebuilt and the preview is still on
                // it - the pin itself faded out. Either way the row ends at rest.
                TaskbarRunning.clearPreview(mDragLayer);
                return true;
            }
            String no = refusal(item);
            if (no != null) {
                TaskbarMenu.toast(getContext(), no);
                return false;
            }
            TaskbarPins.pin(getContext(), item, where(x));
            TaskbarRunning.apply(mDragLayer);
            return true;
        }

        /** The payload when this drag is a pin being moved along the bar, else null. */
        private DragPayload movingPin(DragEvent event) {
            Object local = event.getLocalState();
            if (local instanceof DragPayload
                    && ((DragPayload) local).source == DragPayload.SRC_TASKBAR
                    && ((DragPayload) local).item != null) {
                return (DragPayload) local;
            }
            return null;
        }

        /** Which place in the row a drop at this x belongs to. */
        private int where(float x) {
            ViewGroup row = TaskbarRunning.rowOf(mDragLayer);
            if (row == null) {
                return PinList.AT_THE_END;
            }
            // Asked for, not read off the row: it lives in a scroller now, so its own left says
            // nothing about where its icons are on screen.
            int rowLeft = TaskbarRunning.rowLeft(mDragLayer);
            int index = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (x > rowLeft + child.getLeft() + child.getWidth() / 2f) {
                    index = i + 1;
                }
            }
            return index;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mWhere < 0) {
                return;
            }
            // Where it would land, drawn only while something is actually over the bar.
            mPaint.setColor(Tone.lightOnDark(getContext()) ? 0x66FFFFFF : 0x33000000);
            float width = Ui.dp(getContext(), 3);
            float inset = Ui.dp(getContext(), 8);
            RectF line = new RectF(mWhere - width / 2f, inset,
                    mWhere + width / 2f, getHeight() - inset);
            canvas.drawRoundRect(line, width / 2f, width / 2f, mPaint);
            if (mIcon != null) {
                // Beside the line, where the app will sit.
                int size = Math.round(getHeight() - inset * 2);
                int left = Math.round(mWhere + width + inset / 2f);
                int top = Math.round(inset);
                mIcon.setBounds(left, top, left + size, top + size);
                mIcon.setAlpha(230);
                mIcon.draw(canvas);
            }
        }
    }
}
