package com.zuxos.desktopplus.hook.sidebar;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Looper;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUI's sidebar no longer freezes on every drag when ZUI's AI service is not on the tablet.
 *
 * <p>The sidebar listens to every drag on the screen ({@code FloatingOnDragListener.onDrag}) and,
 * on its main thread, asks ZUI's AI service which apps can take the dragged thing
 * ({@code TransferService.updateAIList} → {@code TransferUtils.getAIIntentList} →
 * {@code AIServiceHelper.getInteractTypeForSE}), waiting on a latch with no time limit. With the
 * AI service ({@code com.zui.ai.aiservice}) removed, no answer ever comes: after five seconds
 * Android says the sidebar is not responding (six times in the owner's log of 13-14 Oct, every
 * one this stack). Where the service is not installed, or is disabled, that question is not asked
 * at all; the list it would fill holds the AI service's apps, and there are none. With the service
 * there, nothing changes.
 */
public final class SidebarAiWait {

    private static final String SERVICE = "com.zui.ai.aiservice";
    private static final String TRANSFER = "com.zui.freeform.sidebar.transfer.TransferService";
    private static final String HELPER = "com.zui.ai.aiservice.sdk.AIServiceHelper";

    /** Whether ZUI's AI service is missing, once known; null before the first drag. */
    private static Boolean sMissing;
    private static boolean sSaid;

    private SidebarAiWait() {
    }

    public static void install(ClassLoader loader) {
        try {
            int hooked = 0;
            Class<?> transfer = Reflect.findClass(TRANSFER, loader);
            if (transfer != null) {
                for (Method m : transfer.getDeclaredMethods()) {
                    if (m.getName().equals("updateAIList") && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (missing(param.thisObject)) {
                                    param.setResult(null);
                                    say("the sidebar's AI app list");
                                }
                            }
                        });
                        hooked++;
                    }
                }
            }
            if (hooked == 0) {
                hooked += hookHelper(loader);
            }
            if (hooked == 0) {
                L.w("sidebar: ZUI's wait for its AI service not found - left as it is");
                return;
            }
            L.i("sidebar: no wait for a missing AI service on drags x" + hooked);
        } catch (Throwable t) {
            L.w("sidebar: not installed (" + t + ")");
        }
    }

    /**
     * Where the sidebar's own step is not found: the question itself answers at once on the main
     * thread, with nothing, while the service is missing.
     */
    private static int hookHelper(ClassLoader loader) {
        Class<?> helper = Reflect.findClass(HELPER, loader);
        if (helper == null) {
            return 0;
        }
        int hooked = 0;
        for (Method m : helper.getDeclaredMethods()) {
            if (!m.getName().equals("getInteractTypeForSE")) {
                continue;
            }
            Object nothing = nothing(m.getReturnType());
            if (nothing == UNKNOWN) {
                L.w("sidebar: ZUI's AI question returns " + m.getReturnType().getName()
                        + " - left as it is");
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Looper.myLooper() == Looper.getMainLooper() && missing(null)) {
                        param.setResult(nothing);
                        say("ZUI's AI question");
                    }
                }
            });
            hooked++;
        }
        return hooked;
    }

    private static final Object UNKNOWN = new Object();

    /** The answer meaning "none" for a return type; {@link #UNKNOWN} where there is no such. */
    private static Object nothing(Class<?> type) {
        if (type == void.class) {
            return null;
        } else if (type == boolean.class) {
            return Boolean.FALSE;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        } else if (type == String.class) {
            return "";
        } else if (type == List.class) {
            return Collections.emptyList();
        } else if (type == Map.class) {
            return Collections.emptyMap();
        } else if (type == Set.class) {
            return Collections.emptySet();
        }
        return UNKNOWN;
    }

    /** Whether ZUI's AI service is missing or disabled; asked of the system once per process. */
    private static boolean missing(Object context) {
        Boolean known = sMissing;
        if (known != null) {
            return known;
        }
        try {
            Context ctx = context instanceof Context ? (Context) context
                    : AppCtx.get();
            if (ctx == null) {
                return false;
            }
            boolean missing;
            try {
                ApplicationInfo info = ctx.getPackageManager().getApplicationInfo(SERVICE, 0);
                missing = !info.enabled;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                missing = true;
            }
            sMissing = missing;
            L.i("sidebar: ZUI's AI service " + (missing ? "is not on the tablet - the sidebar"
                    + " no longer waits for it on each drag" : "is there - left as it is"));
            return missing;
        } catch (Throwable t) {
            L.d("sidebar: the AI service not looked up (" + t + ")");
            return false;
        }
    }

    private static void say(String what) {
        if (!sSaid) {
            sSaid = true;
            L.i("sidebar: " + what + " skipped - ZUI's AI service is missing");
        }
    }
}
