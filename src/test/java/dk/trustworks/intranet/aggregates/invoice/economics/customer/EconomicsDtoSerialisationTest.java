package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against a regression of the 2026-04-15 POST /Customers bug where
 * {@code EconomicsCustomerDto} serialised unset fields as JSON {@code null},
 * which e-conomic rejects with
 * {@code HTTP 400 "Invalid value provided" on Boolean properties}.
 *
 * <p>Both DTOs must have {@code @JsonInclude(NON_NULL)} so unset fields are
 * omitted from the wire payload entirely.
 */
class EconomicsDtoSerialisationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void customer_dto_strips_null_fields_from_post_body() throws Exception {
        EconomicsCustomerDto c = new EconomicsCustomerDto();
        c.setCustomerNumber(49979517);
        c.setName("MARIUS PEDERSEN A/S");
        c.setCvrNo("49979517");
        c.setCustomerGroupNumber(1);
        c.setZone(1);
        c.setCurrency("DKK");
        c.setPaymentTermId(2);
        // access / objectVersion / addresses / nemHandelReceiverType /
        // defaultDisableEInvoicing intentionally unset.

        String json = mapper.writeValueAsString(c);

        assertFalse(json.contains(":null"),
                "EconomicsCustomerDto must not serialise JSON nulls. Got: " + json);
        assertFalse(json.contains("\"access\""),
                "Unset Boolean 'access' must be omitted, not serialised as null.");
        assertFalse(json.contains("\"defaultDisableEInvoicing\""),
                "Unset Boolean 'defaultDisableEInvoicing' must be omitted.");
        assertTrue(json.contains("\"customerNumber\":49979517"));
        assertTrue(json.contains("\"customerGroupNumber\":1"));
    }

    @Test
    void contact_dto_strips_null_fields_from_post_body() throws Exception {
        EconomicsContactDto ct = new EconomicsContactDto();
        ct.setCustomerNumber(3);
        ct.setName("Test Contact");
        ct.setEmail("probe@trustworks.dk");
        // All other fields (including Booleans) intentionally unset.

        String json = mapper.writeValueAsString(ct);

        assertFalse(json.contains(":null"),
                "EconomicsContactDto must not serialise JSON nulls. Got: " + json);
        assertTrue(json.contains("\"customerNumber\":3"));
        assertTrue(json.contains("\"email\":\"probe@trustworks.dk\""));
    }
}
