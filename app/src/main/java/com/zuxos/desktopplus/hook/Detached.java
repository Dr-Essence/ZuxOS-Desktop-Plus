package com.zuxos.desktopplus.hook;

import android.view.View;

import java.util.Map;

/**
 * What a view's window held for it, let go once the view leaves that window.
 *
 * <p>A weak map lets go of a key only when nothing else holds it, and a value that holds its own
 * key - a listener on the view, an icon made in the context, ZUI's helper for the list - keeps it
 * for good. ZUI makes windows afresh (the taskbar's drawer on every opening, a bar on every
 * rebuild), so each one would stay in memory with all its views. Main thread.
 */
public final class Detached {

    private Detached() {
    }

    /** Runs {@code gone} once, when {@code watched} is next taken off its window. */
    public static void then(View watched, Runnable gone) {
        watched.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                gone.run();
            }
        });
    }

    /** Drops {@code key} from {@code map} when {@code watched} is next taken off its window. */
    public static void forget(View watched, Map<?, ?> map, Object key) {
        then(watched, () -> map.remove(key));
    }
}
