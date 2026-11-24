package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** A USB mouse gets the kernel's reset on resume, without turning off a quirk it has. */
public class UsbQuirksTest {

    private static final String READ = "list \n"
            + "dev 2-1.4.2|248a|5b2e|0x0|auto|active|USB Gaming Mouse\n"
            + "dev 1-1.3|2dc8|5200|0x2|on|active|8BitDo Retro Keyboard\n";

    @Test
    public void theRootReadIsParsed() {
        UsbQuirks.State state = UsbQuirks.parse(READ);
        assertEquals("", state.list);
        assertEquals(2, state.devices.size());
        UsbQuirks.Device mouse = state.devices.get(0);
        assertEquals("2-1.4.2", mouse.name);
        assertEquals("248a:5b2e", mouse.id);
        assertEquals(0, mouse.quirks);
        assertEquals("auto", mouse.control);
        assertFalse(mouse.resets());
        assertTrue(state.devices.get(1).resets());
        assertEquals("USB Gaming Mouse (248a:5b2e)", mouse.toString());
    }

    @Test
    public void brokenLinesAreSkipped() {
        UsbQuirks.State state = UsbQuirks.parse("dev |248a|5b2e\nsomething else\nlist (null)");
        assertTrue(state.devices.isEmpty());
        assertEquals("", state.list);
        assertTrue(UsbQuirks.parse(null).devices.isEmpty());
    }

    @Test
    public void theQuirkIsAddedOnlyWhereItIsMissing() {
        List<UsbQuirks.Device> devices = UsbQuirks.parse(READ).devices;
        assertEquals("248a:5b2e:b", UsbQuirks.merged("", devices));
        assertEquals("0bda:8153:k,248a:5b2e:b", UsbQuirks.merged("0bda:8153:k", devices));
    }

    @Test
    public void anEntryAlreadyThereIsLeftAlone() {
        // Its letters toggle the kernel's own quirks: ours added again would turn one off.
        List<UsbQuirks.Device> devices = UsbQuirks.parse(READ).devices;
        assertNull(UsbQuirks.merged("248A:5B2E:b", devices));
        assertNull(UsbQuirks.merged("248a:5b2e:k", devices));
        assertNull(UsbQuirks.merged("", Collections.emptyList()));
    }

    @Test
    public void aDeviceWithoutQuirksGetsTheEntry() {
        List<UsbQuirks.Device> devices = UsbQuirks.parse(
                "dev 3-1|248a|5b2e|0x0|auto|active|Mouse").devices;
        assertEquals("248a:5b2e:b", UsbQuirks.merged(null, devices));
    }

    @Test
    public void unknownQuirksCountAsMissing() {
        assertEquals(-1, UsbQuirks.hex(""));
        assertEquals(-1, UsbQuirks.hex("x"));
        assertEquals(0x402, UsbQuirks.hex("0x402"));
        UsbQuirks.Device device = UsbQuirks.parse("dev 3-1|248a|5b2e||auto|active|M")
                .devices.get(0);
        assertFalse(device.resets());
    }

    @Test
    public void idsAreFourHexDigits() {
        assertEquals("248a:5b2e", UsbQuirks.id(9354, 23342));
        assertEquals("0001:00ff", UsbQuirks.id(1, 255));
    }
}
