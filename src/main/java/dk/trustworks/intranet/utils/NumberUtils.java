package dk.trustworks.intranet.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class NumberUtils {

    public static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();
        if(Double.isInfinite(value) || Double.isNaN(value)) value = 0.0;
        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

}
