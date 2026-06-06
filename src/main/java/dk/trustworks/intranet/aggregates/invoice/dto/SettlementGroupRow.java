package dk.trustworks.intranet.aggregates.invoice.dto;

import java.math.BigDecimal;

/**
 * One controlling row = one settlement group. target = Σ attributed_amount over the
 * group's phantoms (signed); settled = Σ signed line totals of the group's live internals;
 * outstanding = target - settled. hasCreditNotePhantom flags a negative phantom for human
 * review (Decision D3 proportional-netting marker). needsMapping is reserved (unmapped
 * phantoms are not groups — they surface via the review queue).
 */
public record SettlementGroupRow(
        SettlementGroupKey key,
        String clientName,
        String debtorCompanyName,
        BigDecimal target,
        BigDecimal settled,
        BigDecimal outstanding,
        int phantomCount,
        int issuedInternalCount,
        boolean needsMapping,
        boolean hasCreditNotePhantom
) {}
