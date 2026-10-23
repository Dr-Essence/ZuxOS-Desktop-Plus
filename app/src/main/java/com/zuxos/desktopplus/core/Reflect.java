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
        Method m = methodOf(target.getClass(), method, args.length);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field f = fieldOf(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * What a class's lookups found, by class and name, "nothing" included. A lookup walks the class
     * chain and costs a thrown exception per class without the field (or every method of every
     * class), and some are asked for every drawer cell on every finger move: dozens of exceptions a
     * frame made drags spend 30-60 ms in input.
     */
    private static final java.util.Map<Class<?>, java.util.Map<String, Object>> FOUND =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    /** The first field named {@code name} up the class chain, accessible; null when none. */
    private static Field fieldOf(Class<?> cls, String name) {
        java.util.Map<String, Object> known = FOUND.computeIfAbsent(cls,
                c -> new java.util.concurrent.ConcurrentHashMap<>());
        Object found = known.get(name);
        if (found == null) {
            found = NONE;
            for (Class<?> c = cls; c != null && found == NONE; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    found = f;
                } catch (Throwable ignored) {
                    // Try the superclass - once: what is found, or not, is kept.
                }
            }
            known.put(name, found);
        }
        return found instanceof Field ? (Field) found : null;
    }

    /**
     * The first method named {@code name} taking {@code count} arguments up the class chain,
     * accessible; null when none.
     */
    private static Method methodOf(Class<?> cls, String name, int count) {
        java.util.Map<String, Object> known = FOUND.computeIfAbsent(cls,
                c -> new java.util.concurrent.ConcurrentHashMap<>());
        String key = name + "(" + count;
        Object found = known.get(key);
        if (found == null) {
            found = NONE;
            for (Class<?> c = cls; c != null && found == NONE; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == count) {
                        try {
                            m.setAccessible(true);
                        } catch (Throwable t) {
                            // Invoked as it is.
                        }
                        found = m;
                        break;
                    }
                }
            }
            known.put(key, found);
        }
        return found instanceof Method ? (Method) found : null;
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
