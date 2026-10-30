package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CommandBarTest {
    /** Three buttons 40, 60 and 20 wide, 10 apart, centered in 200: 140 in all, from 30. */
    private final CommandBar bar = new CommandBar(new double[] {40, 60, 20}, 0, 200, 10);

    @Test
    void itemsAreCenteredWithGaps() {
        assertEquals(30, bar.x(0));
        assertEquals(80, bar.x(1));
        assertEquals(150, bar.x(2));
        assertEquals(60, bar.w(1));
    }

    @Test
    void atFindsTheItemUnderAPoint() {
        assertEquals(0, bar.at(30));
        assertEquals(1, bar.at(139.9));
        assertEquals(2, bar.at(169.9));
    }

    @Test
    void gapsAndOutsideAreNoItem() {
        assertEquals(-1, bar.at(29.9));
        assertEquals(-1, bar.at(75));
        assertEquals(-1, bar.at(170));
    }

    @Test
    void oneItemFromAnOffset() {
        assertEquals(35, new CommandBar(new double[] {50}, 10, 100, 4).x(0));
    }
}
