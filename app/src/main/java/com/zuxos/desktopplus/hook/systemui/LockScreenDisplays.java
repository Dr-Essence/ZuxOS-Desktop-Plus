package com.zuxos.desktopplus.hook.systemui;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

/**
 * Read only: what ZUI's SystemUI has for a lock screen on the monitor. The owner asked to unlock
 * from the monitor with the tablet's screen off. Android 16 puts only a clock on another screen
 * ({@code KeyguardDisplayManager}, {@code ConnectedDisplayKeyguardPresentation}); the password
 * entry lives in the shade window, and Android's own way to take that window to another screen
 * is a SystemUI flag ({@code shade_window_goes_around}, with {@code ShadeDisplaysRepository}).
 * ZUI's lock screen code is not in the decompile, so its log line says which of these pieces this
 * firmware has and whether the flag is on, before anything is built on them.
 */
public final class LockScreenDisplays {

    private LockScreenDisplays() {
    }

    public static void trace(ClassLoader loader) {
        try {
            Class<?> flags = Reflect.findClass(SysUiNames.SYSTEMUI_FLAGS, loader);
            String goesAround;
            try {
                goesAround = flags == null ? "no flags class"
                        : String.valueOf(flags.getMethod(SysUiNames.SHADE_WINDOW_GOES_AROUND)
                                .invoke(null));
            } catch (NoSuchMethodException none) {
                goesAround = "no such flag";
            }
            L.i("lock screen: the shade (and the lock screen) moving between screens: "
                    + goesAround + "; its screens' repository "
                    + has(SysUiNames.SHADE_DISPLAYS_REPOSITORY, loader)
                    + ", the other screens' lock screen "
                    + has(SysUiNames.KEYGUARD_DISPLAY_MANAGER, loader)
                    + ", the connected screens' clock "
                    + has(SysUiNames.CONNECTED_DISPLAY_KEYGUARD_PRESENTATION, loader));
        } catch (Throwable t) {
            L.d("lock screen: SystemUI's pieces not read (" + t + ")");
        }
    }

    private static String has(String name, ClassLoader loader) {
        return Reflect.findClass(name, loader) != null ? "there" : "missing";
    }
}
