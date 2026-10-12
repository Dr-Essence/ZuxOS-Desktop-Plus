package com.zuxos.desktopplus.core;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny, failure-tolerant reflection helpers.
 *
 * <p>Everything here returns null / false instead of throwing: we are poking at an OEM
 * launcher whose internals we cannot see from the outside, so "not found" is a normal
 * outcome that the caller is expected to handle.
 */
public final class Reflect {

    private Reflect() {
    }

    public static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object call(Object target, String method, Object... args) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(method) || m.getParameterCount() != args.length) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    public static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                // Try the superclass.
            }
        }
        return null;
    }

    /** Resource entry name of a view id, e.g. {@code workspace}, or null. */
    public static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return null;
        }
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Depth-first search for views whose id entry name equals one of {@code names}.
     *
     * <p>The bar's layout asks this several times a pass, so each name is turned into an id once,
     * in the root's own package, and the tree is walked comparing numbers. A name that package does
     * not have - an id of another package - is looked for by name as before.
     */
    public static List<View> findByIdNames(View root, String... names) {
        List<View> out = new ArrayList<>();
        int[] ids = idsOf(root, names);
        if (ids != null) {
            collectIds(root, out, ids);
        } else {
            collect(root, out, names);
        }
        return out;
    }

    /** Package and name to id, as resolved once: ids do not change in a running process. */
    private static final java.util.Map<String, Integer> IDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The ids of {@code names} in the root's package; null when any one is not there. */
    private static int[] idsOf(View root, String[] names) {
        try {
            String pkg = root.getContext().getPackageName();
            int[] ids = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                String key = pkg + "/" + names[i];
                Integer id = IDS.get(key);
                if (id == null) {
                    id = root.getResources().getIdentifier(names[i], "id", pkg);
                    IDS.put(key, id);
                }
                if (id == 0) {
                    return null;
                }
                ids[i] = id;
            }
            return ids;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void collectIds(View v, List<View> out, int[] ids) {
        int id = v.getId();
        if (id != View.NO_ID) {
            for (int want : ids) {
                if (id == want) {
                    out.add(v);
                    break;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectIds(g.getChildAt(i), out, ids);
            }
        }
    }

    private static void collect(View v, List<View> out, String[] names) {
        String id = idName(v);
        if (id != null) {
            for (String n : names) {
                if (id.equalsIgnoreCase(n)) {
                    out.add(v);
                    break;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out, names);
            }
        }
    }

    /** Depth-first search for views whose class name contains one of {@code fragments}. */
    public static List<View> findByClassFragments(View root, String... fragments) {
        List<View> out = new ArrayList<>();
        collectByClass(root, out, fragments);
        return out;
    }

    private static void collectByClass(View v, List<View> out, String[] fragments) {
        String cls = v.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        for (String f : fragments) {
            if (cls.contains(f.toLowerCase(java.util.Locale.ROOT))) {
                out.add(v);
                break;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectByClass(g.getChildAt(i), out, fragments);
            }
        }
    }
}
