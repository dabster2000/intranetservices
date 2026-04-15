package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ContractToEconomicsContactMapper}.
 *
 * SPEC-INV-001 §3.3.2.
 */
class ContractToEconomicsContactMapperTest {

    private final ContractToEconomicsContactMapper mapper = new ContractToEconomicsContactMapper();

    @Test
    void maps_billing_attention_and_uses_contract_email_when_different_from_client_email() {
        Client billing = new Client();
        billing.setUuid("c-uuid");
        billing.setBillingEmail("general@x.dk");
        Contract contract = new Contract();
        contract.setBillingAttention("Thomas Vinther");
        contract.setBillingEmail("thomas@x.dk");

        EconomicsContactDto dto = mapper.toUpsertBody(contract, billing, /*customerNumber*/101);

        assertEquals(101, dto.getCustomerNumber());
        assertEquals("Thomas Vinther", dto.getName());
        assertEquals("thomas@x.dk", dto.getEmail());       // contract email overrides
        assertTrue(dto.getReceiveInvoices());
        assertTrue(dto.getReceiveEInvoices());
        assertNull(dto.getEInvoiceId());                    // spec: not derived from EAN
    }

    @Test
    void omits_email_when_contract_email_equals_client_email() {
        Client billing = new Client();
        billing.setUuid("c");
        billing.setBillingEmail("same@x.dk");
        Contract contract = new Contract();
        contract.setBillingAttention("X");
        contract.setBillingEmail("same@x.dk");

        EconomicsContactDto dto = mapper.toUpsertBody(contract, billing, 1);

        assertNull(dto.getEmail());  // falls through to customer default
    }

    @Test
    void omits_email_when_contract_email_is_null() {
        Client billing = new Client();
        billing.setUuid("c");
        billing.setBillingEmail("customer@x.dk");
        Contract contract = new Contract();
        contract.setBillingAttention("X");
        contract.setBillingEmail(null);

        EconomicsContactDto dto = mapper.toUpsertBody(contract, billing, 1);

        assertNull(dto.getEmail());
    }

    @Test
    void throws_when_billing_attention_missing() {
        Client billing = new Client();
        billing.setUuid("c");
        Contract contract = new Contract();
        contract.setBillingAttention(null);

        assertThrows(IllegalArgumentException.class,
                () -> mapper.toUpsertBody(contract, billing, 1));
    }

    @Test
    void throws_when_billing_attention_blank() {
        Client billing = new Client();
        billing.setUuid("c");
        Contract contract = new Contract();
        contract.setBillingAttention("   ");

        assertThrows(IllegalArgumentException.class,
                () -> mapper.toUpsertBody(contract, billing, 1));
    }

    @Test
    void sets_contract_email_when_client_email_null() {
        Client billing = new Client();
        billing.setUuid("c");
        billing.setBillingEmail(null);
        Contract contract = new Contract();
        contract.setBillingAttention("A");
        contract.setBillingEmail("a@x.dk");

        EconomicsContactDto dto = mapper.toUpsertBody(contract, billing, 1);

        assertEquals("a@x.dk", dto.getEmail());
    }
}
