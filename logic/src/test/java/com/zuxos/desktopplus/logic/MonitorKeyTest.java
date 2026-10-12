package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** A monitor's layout file name from its display's unique id. */
public class MonitorKeyTest {

    @Test
    public void localIdBecomesASafeName() {
        assertEquals("local_4619827259835644672", MonitorKey.of("local:4619827259835644672"));
    }

    @Test
    public void onlySafeCharactersStay() {
        assertEquals("virtual_com.x_a-b_c", MonitorKey.of("virtual:com.x/a-b c"));
    }

    @Test
    public void noIdIsNoKey() {
        assertNull(MonitorKey.of(null));
        assertNull(MonitorKey.of(""));
    }

    @Test
    public void longIdsAreCut() {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            id.append('a');
        }
        assertEquals(80, MonitorKey.of(id.toString()).length());
    }
}
