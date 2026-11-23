package com.zuxos.desktopplus.hook.systemui;

import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;

/**
 * ZUI's shell model of the title bars ({@code DesktopModeWindowDecorViewModel}): each window's
 * title bar, and the task organizer the shell changes windows through. Found through ZUI's own
 * watcher of its title-bar settings, which holds it.
 */
final class ShellModel {

    private ShellModel() {
    }

    /** The model, or null where ZUI has not made it yet. */
    static Object get(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> observer = loader == null ? null
                : Reflect.findClass(SysUiNames.OV_CAPTION_BAR_OBSERVER, loader);
        if (observer == null) {
            return null;
        }
        Field instance = observer.getDeclaredField(SysUiNames.M_INSTANCE);
        instance.setAccessible(true);
        return Reflect.field(instance.get(null), SysUiNames.M_DESKTOP_MODE_WINDOW_DECOR_VIEW_MODEL);
    }
}
