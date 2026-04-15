package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;

/**
 * Maps a {@link Contract}'s billing attention → Customers v3.1.0 {@code /Contacts}
 * upsert body per SPEC-INV-001 §3.3.2. Pure — no persistence, no API calls.
 *
 * <p>Email hierarchy (§3.3.2): the contract's {@code billingEmail} overrides the
 * client's customer-level email only when the two differ; when they match, the
 * contact is created without an {@code email} so e-conomic falls through to the
 * customer-level default.
 *
 * <p>{@code eInvoiceId} is intentionally left {@code null}: it is NOT derived
 * from the EAN. NemHandel routing lives on the {@link EconomicsCustomerDto}
 * (§6.3) — the contact only tracks invoice/e-invoice recipient flags.
 *
 * SPEC-INV-001 §3.3.2, §6.3.
 */
@ApplicationScoped
public class ContractToEconomicsContactMapper {

    /**
     * Builds an upsert body for the customer contact identified by
     * {@code contract.billingAttention}.
     *
     * @throws IllegalArgumentException when {@code billingAttention} is missing —
     *         callers must skip contact sync for contracts without an attention.
     */
    public EconomicsContactDto toUpsertBody(Contract contract, Client billingClient, int customerNumber) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(billingClient, "billingClient must not be null");

        String attention = contract.getBillingAttention();
        if (attention == null || attention.isBlank()) {
            throw new IllegalArgumentException(
                    "Contract " + contract.getUuid() + " has no billingAttention; contact sync skipped");
        }

        EconomicsContactDto body = new EconomicsContactDto();
        body.setCustomerNumber(customerNumber);
        body.setName(attention);
        body.setReceiveInvoices(true);
        body.setReceiveEInvoices(true);
        body.setEInvoiceId(null);  // §3.3.2: never derived from EAN

        // §3.3.2 email hierarchy: contract email only overrides when it
        // differs from the customer-level billing email. Matching emails fall
        // through to the customer default (omit the contact email).
        String contractEmail = contract.getBillingEmail();
        String clientEmail   = billingClient.getBillingEmail();
        if (contractEmail != null && !contractEmail.isBlank()
                && !Objects.equals(contractEmail, clientEmail)) {
            body.setEmail(contractEmail);
        }
        return body;
    }
}
