package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.enums.ClientType;

/**
 * Immutable bundle of per-agreement customer-create defaults. Configured per
 * Trustworks company in e-conomic by the accountant (§13.1 prerequisite
 * checklist); reproduced here verbatim by {@link AgreementDefaultsRegistry}
 * until the Phase G2 rollout moves them into {@code integration_keys}.
 *
 * <p>Package-private so both {@link EconomicsCustomerPairingService} and
 * {@link EconomicsCustomerSyncService} can consume the same source of truth.
 *
 * SPEC-INV-001 §6.3, §13.1.
 */
record AgreementDefaults(
        int clientGroupNumber,
        int partnerGroupNumber,
        String currency,
        int vatZoneNumber,
        int paymentTermId) {

    /** Returns the {@code customerGroupNumber} for the given Trustworks client type. */
    int groupNumberFor(ClientType type) {
        return (type == ClientType.PARTNER) ? partnerGroupNumber : clientGroupNumber;
    }
}
