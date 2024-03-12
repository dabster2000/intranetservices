package dk.trustworks.intranet.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;

public class NumberUtils {


    public static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();
        if (Double.isInfinite(value) || Double.isNaN(value)) value = 0.0;
        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    public static NumberFormat getCurrencyInstance() {
        return NumberFormat.getCurrencyInstance();
    }

    public static String formatCurrency(double d) {
        return NumberFormat.getCurrencyInstance().format(d);
    }

    public static String formatCurrency(double d, String currency) {
        if ("DKK".equals(currency)) return formatCurrency(d);
        return NumberFormat.getCurrencyInstance(Locale.GERMANY).format(d);
    }

    public static double parseDouble(String d) {
        try {
            return NumberFormat.getInstance().parse(d).doubleValue();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return 0.0;
    }

    public static String formatDouble(double d) {
        NumberFormat numberInstance = NumberFormat.getNumberInstance();
        numberInstance.setMinimumFractionDigits(2);
        numberInstance.setMaximumFractionDigits(2);
        return numberInstance.format(d);
    }

    public static int convertDoubleToInt(double d) {
        return Double.valueOf(d).intValue();
    }

    public static NumberFormat getDoubleInstance() {
        return NumberFormat.getNumberInstance();
    }

    /**
     * Calculate the percentage increase from 'initial' to 'finalValue'.
     *
     * @param initial    The initial value.
     * @param finalValue The final value.
     * @return The percentage increase, or -1.0 if 'initial' is 0.
     */
    public static double calculatePercentageIncrease(double initial, double finalValue) {
        if (initial == 0 && finalValue == 0) {
            return 0.0;
        }
        if (initial == 0) {
            return 100.0;
        }
        if (finalValue == 0) {
            return -100.0;
        }
        double increase = finalValue - initial;
        return round(increase / initial, 2) * 100;
    }
}