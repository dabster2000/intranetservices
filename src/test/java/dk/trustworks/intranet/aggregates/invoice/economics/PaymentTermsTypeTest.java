package dk.trustworks.intranet.aggregates.invoice.economics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentTermsTypeTest {

    @Test
    void each_enum_value_maps_to_economics_payment_terms_type_string() {
        assertEquals("net",          PaymentTermsType.NET.economicsValue());
        assertEquals("invoiceMonth", PaymentTermsType.INVOICE_MONTH.economicsValue());
        assertEquals("dueDate",      PaymentTermsType.DUE_DATE.economicsValue());
        assertEquals("paidInCash",   PaymentTermsType.PAID_IN_CASH.economicsValue());
        assertEquals("prepaid",      PaymentTermsType.PREPAID.economicsValue());
        assertEquals("creditcard",   PaymentTermsType.CREDITCARD.economicsValue());
    }

    @Test
    void requires_payment_days_only_for_net_and_invoice_month() {
        assertTrue(PaymentTermsType.NET.requiresPaymentDays());
        assertTrue(PaymentTermsType.INVOICE_MONTH.requiresPaymentDays());
        assertFalse(PaymentTermsType.DUE_DATE.requiresPaymentDays());
        assertFalse(PaymentTermsType.PAID_IN_CASH.requiresPaymentDays());
        assertFalse(PaymentTermsType.PREPAID.requiresPaymentDays());
        assertFalse(PaymentTermsType.CREDITCARD.requiresPaymentDays());
    }

    @Test
    void from_economics_value_is_case_insensitive_and_round_trips() {
        assertEquals(PaymentTermsType.NET,           PaymentTermsType.fromEconomicsValue("net"));
        assertEquals(PaymentTermsType.INVOICE_MONTH, PaymentTermsType.fromEconomicsValue("InvoiceMonth"));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentTermsType.fromEconomicsValue("unknown"));
    }
}
