package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Consolidated, delta-based settlement of cross-company PHANTOM labour.
 * Built up across phases: Phase 2 — group-key derivation only (inert);
 * Phase 3 — backfill; Phase 4 — listSettlementGroups + previewGroup (read);
 * Phase 5 — settleGroup (write). See the master plan's Shared contracts.
 */
@ApplicationScoped
public class PhantomSettlementService {

    private static final Logger log = Logger.getLogger(PhantomSettlementService.class);

    /**
     * Settlement-group key for a phantom: (billing client, receiving/debtor company,
     * year, month). Returns null when the phantom is unmapped (no billing client) —
     * such phantoms cannot form a group and are handled by the review queue.
     */
    public SettlementGroupKey groupKeyOf(Invoice phantom) {
        if (phantom == null) return null;
        String companyUuid = (phantom.getCompany() != null) ? phantom.getCompany().getUuid() : null;
        return SettlementGroupKey.from(phantom.getBillingClientUuid(), companyUuid,
                phantom.getYear(), phantom.getMonth());
    }
}
