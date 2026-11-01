package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RecentTest {
    private final Recent<String> recent = new Recent<String>(15);

    @Test
    void nothingBeforeTheFirstPut() {
        assertNull(recent.get(0));
    }

    @Test
    void aValueIsKeptForItsAge() {
        recent.put("menus", 100);
        assertEquals("menus", recent.get(100));
        assertEquals("menus", recent.get(114));
    }

    @Test
    void aValueAsOldAsItsAgeIsGone() {
        recent.put("menus", 100);
        assertNull(recent.get(115));
    }

    @Test
    void aNewValueStartsItsOwnAge() {
        recent.put("old", 100);
        recent.put("new", 110);
        assertEquals("new", recent.get(124));
    }

    @Test
    void forgetDropsIt() {
        recent.put("menus", 100);
        recent.forget();
        assertNull(recent.get(101));
    }
}
