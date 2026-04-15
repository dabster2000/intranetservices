package dk.trustworks.intranet.aggregates.invoice.economics;

import java.util.Arrays;

/**
 * Mirrors the e-conomic `paymentTermsType` enum used on payment terms in the
 * legacy REST API (`GET /payment-terms`). The {@link #economicsValue()} string
 * is what e-conomic expects in JSON. {@link #requiresPaymentDays()} drives
 * validation in the admin mapping form: only NET and INVOICE_MONTH carry a
 * day count.
 *
 * SPEC-INV-001 §5.3, §7.1.
 */
public enum PaymentTermsType {
    NET           ("net",          true),
    INVOICE_MONTH ("invoiceMonth", true),
    DUE_DATE      ("dueDate",      false),
    PAID_IN_CASH  ("paidInCash",   false),
    PREPAID       ("prepaid",      false),
    CREDITCARD    ("creditcard",   false);

    private final String economicsValue;
    private final boolean requiresPaymentDays;

    PaymentTermsType(String economicsValue, boolean requiresPaymentDays) {
        this.economicsValue = economicsValue;
        this.requiresPaymentDays = requiresPaymentDays;
    }

    public String economicsValue() { return economicsValue; }
    public boolean requiresPaymentDays() { return requiresPaymentDays; }

    public static PaymentTermsType fromEconomicsValue(String value) {
        return Arrays.stream(values())
                .filter(v -> v.economicsValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment terms type: " + value));
    }
}
