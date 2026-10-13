package com.zuxos.desktopplus.hook;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.view.View;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Our actions as ZUI's own shortcuts, for ZUI's popups to lay out, style and animate.
 *
 * <p>Each is a {@code RemoteActionShortcut} - the shortcut Launcher3 has for actions from outside
 * it, such as Digital Wellbeing's - carrying a {@link RemoteAction} with our title and icon. A tap
 * on one of ours runs our action instead of sending its intent, and closes the popup as ZUI's own
 * shortcuts do ({@code dismissTaskMenuView}).
 */
public final class ZuiShortcuts {

    private static final String REMOTE = "com.android.launcher3.popup.RemoteActionShortcut";

    /** Our actions, by the {@link RemoteAction} each of our shortcuts carries. Main thread only. */
    private static final Map<RemoteAction, Runnable> OURS = new WeakHashMap<>();

    private static Constructor<?> sMake;
    private static Field sAction;
    private static PendingIntent sNothing;
    private static boolean sInstalled;

    private ZuiShortcuts() {
    }

    /** Hooks the tap of ZUI's {@code RemoteActionShortcut}; without it none of ours are made. */
    public static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> remote = Reflect.findClass(REMOTE, loader);
            if (remote == null) {
                L.w("zui shortcuts: ZUI's RemoteActionShortcut not found, our actions stay out of "
                        + "its popups");
                return;
            }
            Constructor<?> make = null;
            for (Constructor<?> c : remote.getConstructors()) {
                if (c.getParameterCount() == 4 && c.getParameterTypes()[0] == RemoteAction.class) {
                    make = c;
                }
            }
            for (Field f : remote.getDeclaredFields()) {
                if (f.getType() == RemoteAction.class) {
                    f.setAccessible(true);
                    sAction = f;
                }
            }
            if (make == null || sAction == null) {
                L.w("zui shortcuts: ZUI's RemoteActionShortcut is not as expected");
                return;
            }
            int clicks = XposedBridge.hookAllMethods(remote, "onClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!OURS.isEmpty() && tapped(param.thisObject)) {
                        param.setResult(null);
                    }
                }
            }).size();
            // Only once the tap is ours: a shortcut of ours that sent its intent would do nothing.
            sMake = clicks > 0 ? make : null;
            L.i("zui shortcuts: our actions as ZUI's own shortcuts x" + clicks);
        } catch (Throwable t) {
            L.w("zui shortcuts: not installed (" + t + ")");
        }
    }

    /** Whether shortcuts of ours can be made. */
    public static boolean ready() {
        return sMake != null;
    }

    /**
     * One of ours, as ZUI's shortcut: {@code target} is the popup's activity context,
     * {@code info} the item it is for (may be null), {@code view} what the popup stands on.
     */
    public static Object make(Context ctx, CharSequence title, Icon icon, Runnable action,
            Object target, Object info, View view) throws Exception {
        CharSequence shown = ZuiLook.label(ctx, title.toString());
        RemoteAction remote = new RemoteAction(icon, shown, shown, nothing(ctx));
        OURS.put(remote, action);
        return sMake.newInstance(remote, target, info, view);
    }

    /** A drawable as the icon a shortcut carries, {@code size} pixels square. */
    public static Icon iconOf(Drawable drawable, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        if (drawable != null) {
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
        }
        return Icon.createWithBitmap(bitmap);
    }

    /** Whether a shortcut of ZUI's popup is one of ours. */
    public static boolean isOurs(Object shortcut) {
        try {
            return sAction != null && sAction.getDeclaringClass().isInstance(shortcut)
                    && OURS.containsKey((RemoteAction) sAction.get(shortcut));
        } catch (Throwable t) {
            return false;
        }
    }

    /** One of ours tapped: ZUI's popup closes as for its own shortcuts, then the action runs. */
    private static boolean tapped(Object shortcut) {
        try {
            Runnable action = OURS.get((RemoteAction) sAction.get(shortcut));
            if (action == null) {
                return false;
            }
            shortcut.getClass().getMethod("dismissTaskMenuView").invoke(shortcut);
            action.run();
            return true;
        } catch (Throwable t) {
            L.d("zui shortcuts: tap not handled (" + t + ")");
            return false;
        }
    }

    /**
     * The intent a {@link RemoteAction} must carry. Never sent: a tap on ours runs our action
     * instead. One for all of them, made once.
     */
    private static PendingIntent nothing(Context ctx) {
        if (sNothing == null) {
            sNothing = PendingIntent.getBroadcast(ctx, 0,
                    new Intent("com.zuxos.desktopplus.ZUI_ROW").setPackage(ctx.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE);
        }
        return sNothing;
    }
}
