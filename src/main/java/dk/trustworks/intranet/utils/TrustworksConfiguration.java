package dk.trustworks.intranet.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TrustworksConfiguration {

    private static final double FRIDAY_OFF_HOURS = 2.0;

    public static double getWeekOffHours() {
        return FRIDAY_OFF_HOURS;
    }

    public static double getDayOffHours() {
        return FRIDAY_OFF_HOURS / 5.0;
    }

}
