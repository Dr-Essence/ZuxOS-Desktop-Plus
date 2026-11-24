package com.zuxos.desktopplus.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A USB mouse that is reset when the tablet wakes, instead of resumed. A mouse that cannot resume
 * comes back from a sleep - the tablet's, or its own after a minute idle - looking awake to the
 * kernel and sending nothing, until it is unplugged. Linux's own answer for such a device is its
 * quirk {@code USB_QUIRK_RESET_RESUME}: reset on resume, and never put to sleep while its driver
 * needs it to wake the tablet. The kernel's list {@code usbcore.quirks} takes it at run time, as
 * {@code vendor:product:b}, and applies it when the device is next enumerated.
 *
 * <p>This reads root's account of the devices and decides what to write: the list with the quirk
 * added for each device without it. Each letter in the list toggles a quirk the kernel may
 * already give the device, so an entry is added only for a device whose own quirks lack it, and
 * an entry already there for a device is left as it is.
 */
public final class UsbQuirks {

    /** {@code USB_QUIRK_RESET_RESUME}, as a device's {@code quirks} file shows it. */
    public static final int RESET_RESUME = 0x2;
    /** {@code usbcore.quirks}' letter for it. */
    public static final String RESET_RESUME_LETTER = "b";

    private UsbQuirks() {
    }

    /** One USB device, as the root read writes it. */
    public static final class Device {
        /** Its name under {@code /sys/bus/usb/devices}, such as {@code 2-1.4.2}. */
        public final String name;
        /** {@code vendor:product}, four lower-case hex digits each. */
        public final String id;
        /** Its quirks; -1 when the kernel does not say. */
        public final int quirks;
        public final String control;
        public final String state;
        public final String product;

        public Device(String name, String id, int quirks, String control, String state,
                String product) {
            this.name = name;
            this.id = id;
            this.quirks = quirks;
            this.control = control;
            this.state = state;
            this.product = product;
        }

        /** Whether the kernel already resets it on resume. */
        public boolean resets() {
            return quirks >= 0 && (quirks & RESET_RESUME) != 0;
        }

        @Override
        public String toString() {
            return (product.isEmpty() ? name : product) + " (" + id + ")";
        }
    }

    /** The root read: the kernel's list and the devices. */
    public static final class State {
        public final String list;
        public final List<Device> devices;

        State(String list, List<Device> devices) {
            this.list = list;
            this.devices = devices;
        }
    }

    /** The {@code vendor:product} of an input device's ids. */
    public static String id(int vendor, int product) {
        return String.format(Locale.ROOT, "%04x:%04x", vendor & 0xffff, product & 0xffff);
    }

    /**
     * Reads the root read's lines: {@code list <usbcore.quirks>}, then for each device
     * {@code dev name|vendor|product|quirks|control|state|product name}.
     */
    public static State parse(String text) {
        String list = "";
        List<Device> devices = new ArrayList<>();
        if (text == null) {
            return new State(list, devices);
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.equals("list") || line.startsWith("list ")) {
                list = line.substring(4).trim();
                if (list.equals("(null)")) {
                    list = "";
                }
            } else if (line.startsWith("dev ")) {
                String[] f = line.substring(4).split("\\|", -1);
                if (f.length < 7 || f[0].isEmpty() || f[1].isEmpty() || f[2].isEmpty()) {
                    continue;
                }
                devices.add(new Device(f[0].trim(),
                        (f[1].trim() + ":" + f[2].trim()).toLowerCase(Locale.ROOT),
                        hex(f[3]), f[4].trim(), f[5].trim(), f[6].trim()));
            }
        }
        return new State(list, devices);
    }

    /**
     * The kernel's list with the quirk added for each device that lacks it and has no entry yet;
     * null when nothing is to be added.
     */
    public static String merged(String list, Collection<Device> devices) {
        Set<String> listed = new LinkedHashSet<>();
        List<String> entries = new ArrayList<>();
        if (list != null) {
            for (String raw : list.split(",")) {
                String entry = raw.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                entries.add(entry);
                int second = entry.indexOf(':', entry.indexOf(':') + 1);
                listed.add((second < 0 ? entry : entry.substring(0, second))
                        .toLowerCase(Locale.ROOT));
            }
        }
        boolean added = false;
        for (Device device : devices == null ? Collections.<Device>emptyList() : devices) {
            if (device.resets() || listed.contains(device.id)) {
                continue;
            }
            listed.add(device.id);
            entries.add(device.id + ":" + RESET_RESUME_LETTER);
            added = true;
        }
        return added ? String.join(",", entries) : null;
    }

    /** A {@code quirks} file's number ({@code 0x2}); -1 when there is none. */
    static int hex(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("0x")) {
            t = t.substring(2);
        }
        if (t.isEmpty()) {
            return -1;
        }
        try {
            return (int) Long.parseLong(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
