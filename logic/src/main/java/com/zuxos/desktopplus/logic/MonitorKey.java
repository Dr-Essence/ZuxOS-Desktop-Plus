package com.zuxos.desktopplus.logic;

/**
 * A monitor's key for its own desktop layout file (#22), from its display's unique id
 * ({@code Display.getUniqueId()}, such as {@code local:4619827259835644672}).
 */
public final class MonitorKey {

    private static final int MAX = 80;

    private MonitorKey() {
    }

    /** Filename-safe: letters, digits, dot, dash and underscore only; null for no id. */
    public static String of(String uniqueId) {
        if (uniqueId == null || uniqueId.isEmpty()) {
            return null;
        }
        String key = uniqueId.replaceAll("[^A-Za-z0-9._-]", "_");
        return key.length() > MAX ? key.substring(0, MAX) : key;
    }
}
