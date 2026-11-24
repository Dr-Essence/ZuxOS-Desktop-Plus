package com.zuxos.desktopplus.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Su;
import com.zuxos.desktopplus.logic.UsbQuirks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * USB mice reset when the tablet wakes, instead of left dead until they are replugged.
 *
 * <p>The owner's mouse stopped after the screens went off and on, and after a minute idle - its
 * {@code autosuspend_delay_ms} - and only a replug brought it back. Android had it right all
 * along: enabled, its screen on, the focus there, dispatch on; the kernel called it awake. It
 * sent nothing. That is a device that cannot resume, and Linux's own answer for one is the quirk
 * {@code USB_QUIRK_RESET_RESUME}: reset on wake, never put to sleep while its driver needs it to
 * wake the tablet. So, through root, each USB mouse gets that quirk in the kernel's list
 * ({@code usbcore.quirks}, read at enumeration), its port is turned off and on once so the
 * kernel enumerates it again with it, and it is kept out of idle sleep ({@code power/control}).
 * The decisions are {@link UsbQuirks}'s.
 *
 * <p>It runs when ZUX Home starts and when an input device is added - no polling - and each
 * device is replugged at most once per process, so a write that does not take never loops. The
 * first wakes of each run write whether the tablet slept and what woke it, with each mouse's
 * state, so a mouse that still dies says what came before. Bluetooth mice match no USB device and
 * are left alone. Without root, or with the switch off, nothing is done.
 */
public final class UsbMice {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Input devices come in pairs and bursts as one is enumerated: one look after they settle. */
    private static final long SETTLE_MS = 1000L;
    /** A root request already in flight drops this one: asked once more, after this. */
    private static final long RETRY_MS = 5000L;
    /** How many wakes of each run are written down. */
    private static final int WAKES_WRITTEN = 5;
    /** A USB device's name under {@code /sys/bus/usb/devices}. */
    private static final Pattern NAME = Pattern.compile("[0-9]+-[0-9.]+");
    /** What the kernel's quirk list may hold. */
    private static final Pattern LIST = Pattern.compile("[0-9A-Za-z:,]*");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{4}:[0-9a-f]{4}");

    private static boolean sStarted;
    private static Context sCtx;
    /** The devices replugged this run, by name. Main thread only. */
    private static final Set<String> REPLUGGED = new HashSet<>();
    /** The mice's ids last looked at, for the wake lines. Main thread only. */
    private static Set<String> sIds = new LinkedHashSet<>();
    private static boolean sRetried;
    private static int sWakes;
    /** The kernel's count of the tablet's sleeps at the last wake; -1 before the first. */
    private static long sSlept = -1;

    private UsbMice() {
    }

    /** Started with the launcher; again is a no-op. */
    public static synchronized void start(Context ctx) {
        if (sStarted || ctx == null) {
            return;
        }
        sStarted = true;
        try {
            sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            InputManager input = sCtx.getSystemService(InputManager.class);
            if (input == null) {
                L.w("mice: no input manager, USB mice left as they are");
                return;
            }
            input.registerInputDeviceListener(new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    soon();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                }
            }, MAIN);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            sCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    woke();
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED);
            soon();
        } catch (Throwable t) {
            L.w("mice: not watched, USB mice left as they are (" + t + ")");
        }
    }

    private static void soon() {
        MAIN.removeCallbacks(LOOK);
        MAIN.postDelayed(LOOK, SETTLE_MS);
    }

    private static final Runnable LOOK = UsbMice::look;

    /** The USB mice as root sees them, and what they need. */
    private static void look() {
        try {
            if (!Cfg.usbMiceReset() || !Cfg.useRoot()) {
                if (L.once("UsbMice.off")) {
                    L.i("mice: USB mice left as they are (" + (Cfg.usbMiceReset()
                            ? "root is off" : "switched off") + ")");
                }
                return;
            }
            Set<String> ids = mice();
            sIds = ids;
            if (ids.isEmpty()) {
                return;
            }
            Su.read((outcome, text) -> MAIN.post(() -> read(outcome, text)), state(ids));
        } catch (Throwable t) {
            L.w("mice: not looked at (" + t + ")");
        }
    }

    /** Every external mouse's {@code vendor:product} that Android has now. */
    private static Set<String> mice() {
        Set<String> ids = new LinkedHashSet<>();
        InputManager input = sCtx.getSystemService(InputManager.class);
        for (int id : input.getInputDeviceIds()) {
            InputDevice device = input.getInputDevice(id);
            if (device != null && device.isExternal() && !device.isVirtual()
                    && device.supportsSource(InputDevice.SOURCE_MOUSE)
                    && device.getVendorId() != 0) {
                ids.add(UsbQuirks.id(device.getVendorId(), device.getProductId()));
            }
        }
        return ids;
    }

    /** Root's account of the kernel's quirk list and of each USB device with one of the ids. */
    private static String state(Set<String> ids) {
        return "echo \"list $(cat /sys/module/usbcore/parameters/quirks 2>/dev/null)\"\n"
                + "for d in /sys/bus/usb/devices/*; do\n"
                + "  [ -f \"$d/idVendor\" ] || continue\n"
                + "  v=$(cat \"$d/idVendor\"); p=$(cat \"$d/idProduct\")\n"
                + "  case \" " + String.join(" ", ids) + " \" in *\" $v:$p \"*) ;; *) continue;;"
                + " esac\n"
                + "  pd=0; [ -f \"$d/port/disable\" ] && pd=1\n"
                + "  echo \"dev ${d##*/}|$v|$p|$(cat \"$d/quirks\" 2>/dev/null)"
                + "|$(cat \"$d/power/control\" 2>/dev/null)"
                + "|$(cat \"$d/power/runtime_status\" 2>/dev/null)|$pd"
                + "|$(cat \"$d/product\" 2>/dev/null)\"\n"
                + "done\n"
                + "true";
    }

    private static void read(Su.Outcome outcome, String text) {
        try {
            if (outcome == Su.Outcome.BUSY) {
                if (!sRetried) {
                    sRetried = true;
                    MAIN.postDelayed(LOOK, RETRY_MS);
                }
                return;
            }
            sRetried = false;
            if (!outcome.ok()) {
                if (L.once("UsbMice.readFailed")) {
                    L.w("mice: root's account of the USB devices not read (" + outcome + ")");
                }
                return;
            }
            UsbQuirks.State state = UsbQuirks.parse(text);
            if (state.devices.isEmpty()) {
                if (L.once("UsbMice.none")) {
                    L.i("mice: none of " + sIds + " is a USB device - left as it is");
                }
                return;
            }
            String list = UsbQuirks.merged(state.list, state.devices);
            List<UsbQuirks.Device> replug = UsbQuirks.toReplug(state.devices, REPLUGGED);
            List<UsbQuirks.Device> awake = new ArrayList<>();
            for (UsbQuirks.Device device : state.devices) {
                if (!"on".equals(device.control)) {
                    awake.add(device);
                }
            }
            if (list == null && replug.isEmpty() && awake.isEmpty()) {
                for (UsbQuirks.Device device : state.devices) {
                    if (L.once("UsbMice.has." + device.name + "." + device.quirks)) {
                        L.i("mice: " + device + " is " + (device.resets()
                                ? "reset on wake" : "without the reset (quirks 0x"
                                + Integer.toHexString(device.quirks) + ")")
                                + ", kept awake when idle");
                    }
                }
                return;
            }
            write(list, replug, awake, state.devices);
        } catch (Throwable t) {
            L.w("mice: not set (" + t + ")");
        }
    }

    /** The kernel's list, idle sleep off, and the replugs that make the kernel take the list. */
    private static void write(String list, List<UsbQuirks.Device> replug,
            List<UsbQuirks.Device> awake, List<UsbQuirks.Device> all) {
        StringBuilder sh = new StringBuilder();
        if (list != null) {
            if (!LIST.matcher(list).matches()) {
                L.w("mice: the kernel's quirk list looks odd, not written: " + list);
                return;
            }
            sh.append("echo '").append(list)
                    .append("' > /sys/module/usbcore/parameters/quirks\n");
        }
        for (UsbQuirks.Device device : awake) {
            if (NAME.matcher(device.name).matches()) {
                sh.append("echo on > /sys/bus/usb/devices/").append(device.name)
                        .append("/power/control\n");
            }
        }
        List<UsbQuirks.Device> unplugged = new ArrayList<>();
        for (UsbQuirks.Device device : replug) {
            if (NAME.matcher(device.name).matches() && ID.matcher(device.id).matches()) {
                REPLUGGED.add(device.name);
                unplugged.add(device);
                sh.append("echo 1 > /sys/bus/usb/devices/").append(device.name)
                        .append("/port/disable\n");
            }
        }
        if (!unplugged.isEmpty()) {
            sh.append("sleep 0.5\n");
            for (UsbQuirks.Device device : unplugged) {
                // Twice if need be: a port left off is a mouse gone until it is replugged.
                String port = "/sys/bus/usb/devices/" + device.name + "/port/disable";
                sh.append("echo 0 > ").append(port).append(" || { sleep 0.5; echo 0 > ")
                        .append(port).append("; }\n");
            }
        }
        sh.append("true");
        Su.run(outcome -> MAIN.post(() -> written(outcome, list, unplugged, all)),
                sh.toString());
    }

    private static void written(Su.Outcome outcome, String list,
            List<UsbQuirks.Device> unplugged, List<UsbQuirks.Device> all) {
        if (!outcome.ok()) {
            L.w("mice: the reset on wake not set (" + outcome + ")");
            return;
        }
        for (UsbQuirks.Device device : all) {
            boolean again = unplugged.contains(device);
            String how;
            if (device.resets()) {
                how = "is reset on wake, kept awake when idle";
            } else if (again) {
                how = "is reset on wake instead of resumed, never put to sleep idle - plugged"
                        + " out and in at its port once to take it";
            } else if (!device.port) {
                how = "is kept awake when idle; the reset on wake takes at its next replug"
                        + " (its port cannot be turned off here)";
            } else {
                how = "is kept awake when idle";
            }
            L.i("mice: " + device + " " + how);
        }
        if (list != null) {
            L.i("mice: the kernel's quirk list is now " + list);
        }
    }

    /** The first wakes of the run: whether the tablet slept, what woke it, each mouse's state. */
    private static void woke() {
        try {
            if (sWakes >= WAKES_WRITTEN || sIds.isEmpty() || !Cfg.usbMiceReset()
                    || !Cfg.useRoot()) {
                return;
            }
            sWakes++;
            String script = "echo \"slept $(cat /sys/power/suspend_stats/success 2>/dev/null)\"\n"
                    + "echo \"woken $(head -3 /sys/kernel/wakeup_reasons/last_resume_reason"
                    + " 2>/dev/null | tr '\\n' ' ')\"\n" + state(sIds);
            Su.read((outcome, text) -> MAIN.post(() -> wakeRead(outcome, text)), script);
        } catch (Throwable t) {
            L.d("mice: wake not written (" + t + ")");
        }
    }

    private static void wakeRead(Su.Outcome outcome, String text) {
        if (!outcome.ok() || text == null) {
            return;
        }
        long slept = -1;
        String woken = "";
        for (String line : text.split("\n")) {
            if (line.startsWith("slept ")) {
                try {
                    slept = Long.parseLong(line.substring(6).trim());
                } catch (NumberFormatException ignored) {
                    slept = -1;
                }
            } else if (line.startsWith("woken")) {
                woken = line.substring(5).trim();
            }
        }
        StringBuilder sb = new StringBuilder("mice after a wake: ");
        if (slept < 0) {
            sb.append("the tablet's sleeps not counted here");
        } else if (sSlept < 0) {
            sb.append("the tablet has slept ").append(slept).append(" times");
        } else {
            sb.append("the tablet slept ").append(slept - sSlept).append(" times since the last");
        }
        sSlept = slept;
        sb.append(woken.isEmpty() ? "" : ", woken by " + woken);
        for (UsbQuirks.Device device : UsbQuirks.parse(text).devices) {
            sb.append("; ").append(device).append(' ').append(device.state).append(", quirks 0x")
                    .append(Integer.toHexString(Math.max(device.quirks, 0)))
                    .append(", ").append(device.control);
        }
        L.i(sb.toString());
    }
}
