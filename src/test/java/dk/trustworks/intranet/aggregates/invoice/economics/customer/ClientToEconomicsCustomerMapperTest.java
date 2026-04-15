package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClientToEconomicsCustomerMapper}. Pure mapper — no CDI,
 * no Panache, no Quarkus boot needed.
 *
 * SPEC-INV-001 §6.3.
 */
class ClientToEconomicsCustomerMapperTest {

    private final ClientToEconomicsCustomerMapper mapper = new ClientToEconomicsCustomerMapper();

    @Test
    void full_mapping_sets_all_spec_fields_for_CLIENT_with_EAN() {
        Client c = new Client();
        c.setUuid("c-uuid");
        c.setName("Banedanmark");
        c.setType(ClientType.CLIENT);
        c.setCvr("18632276");
        c.setEan("5798000893207");
        c.setBillingAddress("Carsten Niebuhrs Gade 43");
        c.setBillingZipcode("1577");
        c.setBillingCity("København V");
        c.setBillingCountry("DK");
        c.setBillingEmail("ap@bane.dk");
        c.setCurrency("DKK");

        EconomicsCustomerDto dto = mapper.toFullUpsertBody(c, /*groupNumber*/1, /*paymentTerm*/5, /*vatZone*/1);

        assertEquals("Banedanmark", dto.getName());
        assertEquals("18632276", dto.getCvrNo());
        assertEquals("5798000893207", dto.getEanLocationNumber());
        assertEquals("ap@bane.dk", dto.getEmail());
        assertEquals("Denmark", dto.getCountry());
        assertEquals("Carsten Niebuhrs Gade 43", dto.getAddress1());
        assertNull(dto.getAddress2());
        assertEquals("1577", dto.getPostCode());
        assertEquals("København V", dto.getCity());
        assertEquals("DKK", dto.getCurrency());
        assertEquals(1, dto.getCustomerGroupNumber());
        assertEquals(5, dto.getPaymentTermId());
        assertEquals(1, dto.getZone());
        // EAN present → nemHandelReceiverType=1, e-invoicing enabled
        assertEquals(1, dto.getNemHandelReceiverType());
        assertFalse(dto.getDefaultDisableEInvoicing());
    }

    @Test
    void client_without_ean_disables_einvoicing_and_leaves_nemhandel_null() {
        Client c = new Client();
        c.setUuid("c");
        c.setName("No EAN ApS");
        c.setCvr("12345678");
        c.setCurrency("DKK");
        c.setType(ClientType.CLIENT);

        EconomicsCustomerDto dto = mapper.toFullUpsertBody(c, 1, 1, 1);

        assertNull(dto.getEanLocationNumber());
        assertNull(dto.getNemHandelReceiverType());
        assertTrue(dto.getDefaultDisableEInvoicing());
    }

    @Test
    void splits_long_address_into_address1_and_address2() {
        Client c = new Client();
        c.setUuid("c");
        c.setName("X");
        c.setBillingAddress("A".repeat(300));
        c.setBillingCountry("DK");
        c.setCurrency("DKK");
        c.setType(ClientType.CLIENT);

        EconomicsCustomerDto dto = mapper.toFullUpsertBody(c, 1, 1, 1);

        assertEquals(255, dto.getAddress1().length());
        assertEquals(45, dto.getAddress2().length());
    }

    @Test
    void maps_country_code_to_english_display_name() {
        Client c = new Client();
        c.setUuid("c");
        c.setName("X");
        c.setBillingCountry("GB");
        c.setCurrency("GBP");
        c.setType(ClientType.CLIENT);

        EconomicsCustomerDto dto = mapper.toFullUpsertBody(c, 1, 1, 1);

        assertEquals("United Kingdom", dto.getCountry());
    }

    @Test
    void blank_billing_fields_are_left_null_not_empty_strings() {
        Client c = new Client();
        c.setUuid("c");
        c.setName("X");
        c.setBillingAddress("   ");
        c.setBillingZipcode("");
        c.setBillingCity("  ");
        c.setBillingEmail("");
        c.setBillingCountry(null);
        c.setEan("");
        c.setCurrency("DKK");
        c.setType(ClientType.CLIENT);

        EconomicsCustomerDto dto = mapper.toFullUpsertBody(c, 1, 1, 1);

        assertNull(dto.getAddress1());
        assertNull(dto.getAddress2());
        assertNull(dto.getPostCode());
        assertNull(dto.getCity());
        assertNull(dto.getEmail());
        assertNull(dto.getCountry());
        assertNull(dto.getEanLocationNumber());
        // EAN blank is treated as no EAN — e-invoicing disabled by default.
        assertTrue(dto.getDefaultDisableEInvoicing());
    }

    @Test
    void minimal_body_sets_only_required_fields() {
        Client c = new Client();
        c.setUuid("c");
        c.setName("X");
        c.setCvr("12345678");
        c.setCurrency("DKK");
        c.setType(ClientType.CLIENT);

        EconomicsCustomerDto dto = mapper.toMinimalCreateBody(c, 2, 5, 1);

        assertEquals("X", dto.getName());
        assertEquals("12345678", dto.getCvrNo());
        assertEquals(2, dto.getCustomerGroupNumber());
        assertEquals(5, dto.getPaymentTermId());
        assertEquals(1, dto.getZone());
        // Full-mapping-only fields should stay null.
        assertNull(dto.getAddress1());
        assertNull(dto.getNemHandelReceiverType());
        assertNull(dto.getDefaultDisableEInvoicing());
    }
}
