package com.zuxos.desktopplus.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BooleanSupplier;

/**
 * Which of the module's hooks found what they look for in this process, for the probe and the
 * settings app's health screen (#13).
 *
 * <p>ZUI renames its internals with each update; a hook that finds nothing is the first sign.
 * Every install that counts its hooks reports here as well as to the log, so one probe lists them
 * all side by side, and the launcher hands the same list to the settings app
 * ({@code hook/HealthReport}).
 */
public final class Health {

    /** What was hooked, by name, sorted. */
    private static final Map<String, Entry> HOOKS = new ConcurrentSkipListMap<>();
    /** Goes up on every report, so the list is only sent on when it changed. */
    private static volatile int sVersion;

    private Health() {
    }

    /** One feature's hooks: how many went in, and its switch when it has one. */
    public static final class Entry {
        public final int count;
        private final BooleanSupplier mOn;

        Entry(int count, BooleanSupplier on) {
            this.count = count;
            mOn = on;
        }

        /** Whether its switch is on now; a feature without one is always on. */
        public boolean on() {
            try {
                return mOn == null || mOn.getAsBoolean();
            } catch (Throwable t) {
                return true;
            }
        }
    }

    /** {@code count} hooks went in for {@code what}. */
    public static void hooked(String what, int count) {
        hooked(what, count, null);
    }

    /** The same, for a feature with its own switch ({@code on}, read when the list is shown). */
    public static void hooked(String what, int count, BooleanSupplier on) {
        HOOKS.put(what, new Entry(count, on));
        sVersion++;
    }

    public static int version() {
        return sVersion;
    }

    /** Each feature as "name", "x3" / "not found", and "on" / "off". */
    public static List<String[]> rows() {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, Entry> e : HOOKS.entrySet()) {
            Entry entry = e.getValue();
            rows.add(new String[]{e.getKey(), entry.count > 0 ? "x" + entry.count : "not found",
                    entry.on() ? "on" : "off"});
        }
        return rows;
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder("\nhooks in this process\n");
        if (HOOKS.isEmpty()) {
            return sb.append("  (none reported yet)\n").toString();
        }
        for (String[] row : rows()) {
            sb.append("  ").append(row[1].equals("not found") ? "!! " : "   ")
                    .append(row[0]).append(": ").append(row[1])
                    .append(row[2].equals("off") ? " (switched off)" : "").append('\n');
        }
        return sb.toString();
    }
}
