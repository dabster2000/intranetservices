package dk.trustworks.intranet.invoiceservice.utils;

import java.text.NumberFormat;

public class StringUtils {

    public static String convertInvoiceNumberToString(int number) {
        return NumberFormat.getNumberInstance()
                .format(number)
                .replace(",", "-")
                .replace(".", "-");
    }

}
