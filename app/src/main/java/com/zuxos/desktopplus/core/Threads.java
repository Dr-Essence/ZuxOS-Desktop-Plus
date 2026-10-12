package com.zuxos.desktopplus.core;

import java.util.concurrent.ThreadFactory;

/**
 * The threads of our background pools: named, daemon, and never the end of the process.
 *
 * <p>A task that throws on a pool's thread ends that thread, and Android's default for a thread
 * ending that way is to end the whole process - ZUX Home, the bars, the desktop. Every pool of
 * ours makes its threads here, so a task that throws is written to the log and the pool goes on
 * with a new thread, whatever the task forgot to catch.
 */
public final class Threads {

    private static final Thread.UncaughtExceptionHandler LOG = (thread, error) ->
            L.e("thread " + thread.getName() + ": a task failed, the thread carries on", error);

    private Threads() {
    }

    /** Threads for a pool, by name. */
    public static ThreadFactory named(String name) {
        return named(name, Thread.NORM_PRIORITY);
    }

    /** The same, at a priority of their own. */
    public static ThreadFactory named(String name, int priority) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(priority);
            t.setUncaughtExceptionHandler(LOG);
            return t;
        };
    }
}
