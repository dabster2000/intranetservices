package dk.trustworks.intranet.aggregates.invoice.dto;

import java.util.Set;

/**
 * Request body for the human-initiated settle endpoint (Decision D4). Identifies
 * one settlement group by its {@link SettlementGroupKey} components, the issuer
 * companies to settle (issuer-grain partial settle), and whether the emitted
 * documents should stop at QUEUED ({@code queue=true}) or be force-finalized.
 *
 * <p>{@code queue} is a nullable {@link Boolean} whose <b>safe default is TRUE</b>:
 * an absent field (or an explicit null) normalizes to QUEUED — emit the documents
 * but do NOT post to e-conomic; finalization happens only behind an explicit
 * Force-create. This keeps the documented contract default and the fail-safe
 * behaviour identical: an omitted field can never trigger an immediate, irreversible
 * e-conomic voucher post. Pass {@code queue=false} to force-finalize on settle.
 */
public record SettlementSettleRequest(String billingClientUuid, String debtorCompanyUuid,
                                      int year, int month, Set<String> issuerCompanyUuids, Boolean queue) {

    public SettlementSettleRequest {
        if (queue == null) queue = Boolean.TRUE;   // safe default: QUEUED, no e-conomic post
    }
}
