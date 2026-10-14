package com.zuxos.desktopplus.hook.home;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Point;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.logic.GridMath;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The home screen keeps its look when its grid changes: widgets stay as large on screen, and on
 * a finer grid the icons keep their places, spread over the new cells.
 *
 * <p>ZUI moves the home screen's items into a new grid itself ({@code GridSizeMigrationTask},
 * one column or row a step, {@code MultiStepMigrationTask.migrate(from, to)}), keeping every
 * item's cells: on a grid twice as fine a widget became half its size and was cut (the clock,
 * owner's video 22:02), and the icons bunched up in a corner. Just before ZUI's step, in its
 * transaction, the items on the home screen are written into the new grid:
 * <ul>
 *   <li>a grid finer in a direction: every item's place in it scales (whole cells, rounded down,
 *       so no two items overlap - {@link GridMath}), and a widget's size with it;</li>
 *   <li>a grid coarser in a direction: a widget the module had made larger goes back to the size
 *       it had on its own grid; places are left to ZUI's own step.</li>
 * </ul>
 * A widget's size on the grid it was made for is kept, by item, so going back is exact.
 */
public final class GridMigration {

    private static final String PREFS = "zux_desktop_plus_grid";
    private static final String TABLE = "favorites";
    /** Launcher3's home screen container and its widget item types. */
    private static final int DESKTOP = -100;
    private static final int APPWIDGET = 4;
    private static final int CUSTOM_APPWIDGET = 5;

    private GridMigration() {
    }

    public static void install(ClassLoader loader) {
        try {
            if (!Cfg.enabled() || !Cfg.gridFit()) {
                return;
            }
            Class<?> task = Reflect.findClass(
                    "com.zui.launcher.model.GridSizeMigrationTask$MultiStepMigrationTask", loader);
            if (task == null) {
                L.w("grid migration: ZUI's grid move not found - items keep their cells");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(task, "migrate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 2 && param.args[0] instanceof Point
                            && param.args[1] instanceof Point) {
                        move(param.thisObject, (Point) param.args[0], (Point) param.args[1]);
                    }
                }
            }).size();
            L.i("grid migration: the home screen keeps its look when its grid changes x"
                    + hooked);
        } catch (Throwable t) {
            L.w("grid migration: not installed (" + t + ")");
        }
    }

    private static void move(Object task, Point from, Point to) {
        if (from.equals(to) || from.x <= 0 || from.y <= 0 || to.x <= 0 || to.y <= 0) {
            return;
        }
        try {
            Field dbField = Mirror.fieldOfType(task.getClass(), SQLiteDatabase.class);
            Field ctxField = Mirror.fieldOfType(task.getClass(), Context.class);
            SQLiteDatabase db = dbField == null ? null : (SQLiteDatabase) Mirror.get(dbField, task);
            Context ctx = ctxField == null ? null : (Context) Mirror.get(ctxField, task);
            if (db == null || ctx == null) {
                return;
            }
            SharedPreferences sizes = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor edit = sizes.edit();
            int moved = 0;
            try (Cursor c = db.query(TABLE,
                    new String[]{"_id", "itemType", "cellX", "cellY", "spanX", "spanY"},
                    "container = " + DESKTOP, null, null, null, null)) {
                while (c.moveToNext()) {
                    int id = c.getInt(0);
                    int type = c.getInt(1);
                    int x = c.getInt(2);
                    int y = c.getInt(3);
                    int sx = c.getInt(4);
                    int sy = c.getInt(5);
                    boolean widget = type == APPWIDGET || type == CUSTOM_APPWIDGET;
                    int[] base = widget ? base(sizes, id) : null;
                    int nx = x;
                    int ny = y;
                    int nsx = sx;
                    int nsy = sy;
                    if (to.x > from.x) {
                        nx = GridMath.start(x, from.x, to.x);
                        if (widget) {
                            nsx = GridMath.span(x, sx, from.x, to.x);
                        }
                    } else if (to.x < from.x && base != null) {
                        nsx = Math.min(sx, GridMath.widgetSpan(base[0], base[2], to.x));
                    }
                    if (to.y > from.y) {
                        ny = GridMath.start(y, from.y, to.y);
                        if (widget) {
                            nsy = GridMath.span(y, sy, from.y, to.y);
                        }
                    } else if (to.y < from.y && base != null) {
                        nsy = Math.min(sy, GridMath.widgetSpan(base[1], base[3], to.y));
                    }
                    if (widget && base == null && (nsx != sx || nsy != sy)) {
                        // The size it was made at, to come back to.
                        edit.putString("w" + id, sx + "," + sy + "," + from.x + "," + from.y);
                    } else if (widget && base != null && to.x == base[2] && to.y == base[3]) {
                        nsx = base[0];
                        nsy = base[1];
                        edit.remove("w" + id);
                    }
                    if (nx == x && ny == y && nsx == sx && nsy == sy) {
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put("cellX", nx);
                    values.put("cellY", ny);
                    values.put("spanX", nsx);
                    values.put("spanY", nsy);
                    db.update(TABLE, values, "_id = " + id, null);
                    moved++;
                }
            }
            edit.apply();
            L.i("grid migration: " + moved + " home items written from " + from.x + "x" + from.y
                    + " to " + to.x + "x" + to.y + " before ZUI's own move");
        } catch (Throwable t) {
            L.w("grid migration: items left to ZUI's own move (" + t + ")");
        }
    }

    /** The widget's span and grid it was made at, when the module resized it; else null. */
    private static int[] base(SharedPreferences sizes, int id) {
        String kept = sizes.getString("w" + id, null);
        if (kept == null) {
            return null;
        }
        String[] parts = kept.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
