package com.zuxos.desktopplus.hook.system;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Threads;
import com.zuxos.desktopplus.hook.Targets;

/**
 * Inside the system: what the launcher may not do itself, done for it - for now, sending a window
 * to the back (minimise), which needs a permission the launcher is not given.
 *
 * <p>Only the launchers this module runs in, the shell and root may ask; the request names a task
 * and nothing else. Its thread also reads the system side's switches again when the settings
 * change, for those that must not be read where they are used ({@link BarClearance},
 * {@link GameTipOff}, {@link LaunchPromptOff}, {@link BarWithDrawer}).
 */
public final class SystemBridge {

    public static final String ACTION_MINIMIZE = "com.zuxos.desktopplus.action.MINIMIZE";
    public static final String ACTION_CLOSE_TASK = "com.zuxos.desktopplus.action.CLOSE_TASK";
    public static final String EXTRA_TASK = "task";

    private static boolean sInstalled;

    private SystemBridge() {
    }

    public static synchronized void install() {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        HandlerThread thread = Threads.guarded(new HandlerThread("zux-bridge"));
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        // After boot, once the system has a context to register with.
        handler.postDelayed(new Runnable() {
            int tries;

            @Override
            public void run() {
                Context ctx = systemContext();
                if (ctx == null) {
                    if (++tries < 30) {
                        handler.postDelayed(this, 5000L);
                    }
                    return;
                }
                try {
                    IntentFilter filter = new IntentFilter(ACTION_MINIMIZE);
                    filter.addAction(ACTION_CLOSE_TASK);
                    // The settings app saying a switch changed: the system side's switches that
                    // are read off their hot paths are read again here, on this thread.
                    filter.addAction(Const.ACTION_SETTINGS_CHANGED);
                    ctx.registerReceiver(new Receiver(ctx), filter, null, handler,
                            Context.RECEIVER_EXPORTED);
                    L.i("system bridge: minimise is available to the launcher");
                } catch (Throwable t) {
                    L.e("system bridge: could not listen", t);
                }
            }
        }, 15000L);
    }

    private static final class Receiver extends BroadcastReceiver {
        private final Context mCtx;

        Receiver(Context ctx) {
            mCtx = ctx;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (Const.ACTION_SETTINGS_CHANGED.equals(intent.getAction())) {
                    // Anyone may ask; all it does is read our own switches again.
                    Cfg.reload();
                    BarClearance.reread();
                    GameTipOff.reread();
                    LaunchPromptOff.reread();
                    BarWithDrawer.reread();
                    return;
                }
                if (!allowed(getSentFromPackage(), getSentFromUid())) {
                    L.w("system bridge: refused a request from " + getSentFromPackage());
                    return;
                }
                int taskId = intent.getIntExtra(EXTRA_TASK, -1);
                if (taskId < 0) {
                    return;
                }
                if (ACTION_CLOSE_TASK.equals(intent.getAction())) {
                    closeTask(taskId);
                } else {
                    minimize(taskId);
                }
            } catch (Throwable t) {
                L.e("system bridge: request failed", t);
            }
        }

        private boolean allowed(String pkg, int uid) {
            if (uid == 0 || uid == 2000 || uid == 1000) {
                return true;
            }
            return pkg != null && Targets.isCandidate(pkg);
        }

        /** One window of an app closed, the others left running - what a launcher may not do. */
        private void closeTask(int taskId) throws Exception {
            Object atm = Class.forName(SystemNames.ACTIVITY_TASK_MANAGER)
                    .getMethod(SystemNames.GET_SERVICE).invoke(null);
            long identity = Binder.clearCallingIdentity();
            try {
                Object removed = atm.getClass().getMethod(SystemNames.REMOVE_TASK, int.class)
                        .invoke(atm, taskId);
                L.i("system bridge: closed task " + taskId + " (" + removed + ")");
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void minimize(int taskId) throws Exception {
            ActivityManager am = (ActivityManager) mCtx.getSystemService(
                    Context.ACTIVITY_SERVICE);
            Object token = null;
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
                if (task.taskId == taskId) {
                    token = Reflect.field(task, SystemNames.TOKEN);
                }
            }
            if (token == null) {
                L.i("system bridge: no task " + taskId + " to minimise");
                return;
            }
            Class<?> wctClass = Class.forName(SystemNames.WINDOW_CONTAINER_TRANSACTION);
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod(SystemNames.REORDER,
                    Class.forName(SystemNames.WINDOW_CONTAINER_TOKEN), boolean.class)
                    .invoke(wct, token, false);
            Class<?> organizer = Class.forName(SystemNames.WINDOW_ORGANIZER);
            long identity = Binder.clearCallingIdentity();
            try {
                organizer.getMethod(SystemNames.APPLY_TRANSACTION, wctClass)
                        .invoke(organizer.getConstructor().newInstance(), wct);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            L.i("system bridge: minimised task " + taskId);
        }
    }

    static Context systemContext() {
        try {
            Class<?> thread = Class.forName(SystemNames.ACTIVITY_THREAD);
            Object current = thread.getMethod(SystemNames.CURRENT_ACTIVITY_THREAD).invoke(null);
            return current == null ? null
                    : (Context) thread.getMethod(SystemNames.GET_SYSTEM_CONTEXT).invoke(current);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ZUX Home told something by the system, as the user in front. Never from under the window
     * manager's lock: a broadcast takes the activity manager's, which comes before it.
     */
    static void toZuxHome(Intent intent) {
        Context ctx = systemContext();
        if (ctx != null) {
            ctx.sendBroadcastAsUser(intent.setPackage(SystemNames.ZUX_HOME)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), currentUser());
        }
    }

    /**
     * The user in front ({@code UserHandle.CURRENT}, not in the public SDK the module is built
     * with); this process's own if it cannot be read.
     */
    private static UserHandle currentUser() {
        try {
            Object current = UserHandle.class.getField(SystemNames.CURRENT).get(null);
            if (current instanceof UserHandle) {
                return (UserHandle) current;
            }
        } catch (Throwable t) {
            // This process's own below.
        }
        return Process.myUserHandle();
    }
}
