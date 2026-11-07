package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.RepeatingReport;

/** One stream's numbers, reported every 10 s. {@link RepeatingReport} says which go in the log everyone reads. */
final class RepeatingLog {
    private final RepeatingReport rule = new RepeatingReport();

    /** @param trouble whether these numbers show something going wrong for the player */
    void report(boolean trouble, String format, Object... arguments) {
        if (rule.show(System.nanoTime(), trouble)) DesktopScreens.LOG.info(format, arguments);
        else DesktopScreens.LOG.debug(format, arguments);
    }
}
