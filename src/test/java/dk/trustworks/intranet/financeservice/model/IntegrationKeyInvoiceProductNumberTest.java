package dk.trustworks.intranet.financeservice.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based verification that {@link IntegrationKey.IntegrationKeyValue}
 * exposes a typed accessor for the {@code invoice-product-number} integration key.
 *
 * Required by Phase G2 customer mapper and Phase H draft-line mapper
 * (SPEC-INV-001 §6.2, §6.4 product number requirement).
 *
 * Runs without booting Quarkus so it works in environments that lack
 * runtime secrets.
 */
class IntegrationKeyInvoiceProductNumberTest {

    @Test
    void integration_key_value_record_exposes_invoiceProductNumber() throws Exception {
        // The record component must exist (and generates an accessor method).
        Method accessor = IntegrationKey.IntegrationKeyValue.class
                .getDeclaredMethod("invoiceProductNumber");
        assertNotNull(accessor,
                "IntegrationKeyValue must expose an invoiceProductNumber() accessor " +
                "for the 'invoice-product-number' key (SPEC-INV-001 §6.4).");
        assertEquals(int.class, accessor.getReturnType(),
                "invoiceProductNumber() must return int — matches existing pattern " +
                "(invoiceJournalNumber, invoiceAccountNumber).");
    }

    @Test
    void integration_key_value_record_preserves_existing_accessors() throws Exception {
        // Guard against accidental regression: all existing accessors stay intact.
        assertNotNull(IntegrationKey.IntegrationKeyValue.class.getDeclaredMethod("url"));
        assertNotNull(IntegrationKey.IntegrationKeyValue.class.getDeclaredMethod("appSecretToken"));
        assertNotNull(IntegrationKey.IntegrationKeyValue.class.getDeclaredMethod("agreementGrantToken"));
        assertNotNull(IntegrationKey.IntegrationKeyValue.class.getDeclaredMethod("expenseJournalNumber"));
        assertNotNull(IntegrationKey.IntegrationKeyValue.class.getDeclaredMethod("invoiceJournalNumber"));
        assertNotNull(IntegrationKey.IntegrationKeyValue.class.getDeclaredMethod("invoiceAccountNumber"));
        assertNotNull(IntegrationKey.IntegrationKeyValue.class.getDeclaredMethod("internalJournalNumber"));
    }
}
