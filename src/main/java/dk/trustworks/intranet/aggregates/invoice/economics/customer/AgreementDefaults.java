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

    /**
     * Returns the {@code customerGroupNumber} for the given Trustworks client type.
     *
     * <p><b>A PROSPECT is refused outright.</b> It has never been billed and must not exist
     * in e-conomic: the whole point of the type is that writing down a company somebody had
     * a coffee with is not a bookkeeping act. The two callers both gate on the type before
     * reaching here, so this throw is the backstop that makes "no path can sync one by
     * accident" true rather than merely intended.
     */
    int groupNumberFor(ClientType type) {
        if (type == ClientType.PROSPECT) {
            throw new IllegalArgumentException(
                    "A prospect has never been billed and is not synced to e-conomic — "
                            + "it becomes a customer on its first contract");
        }
        return (type == ClientType.PARTNER) ? partnerGroupNumber : clientGroupNumber;
    }
}
