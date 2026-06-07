package dk.trustworks.intranet.aggregates.invoice.dto;

import java.math.BigDecimal;

/**
 * One controlling row = one settlement group. target = Σ attributed_amount over the
 * group's phantoms (signed, Decision D1 — the full figure, INCLUDING same-company and
 * unmapped-consultant labour); settled = Σ signed line totals of the group's live internals;
 * outstanding = target - settled. hasCreditNotePhantom flags a negative phantom for human
 * review (Decision D3 proportional-netting marker). needsMapping is reserved (unmapped
 * phantoms are not groups — they surface via the review queue).
 *
 * <p>This {@code target}/{@code outstanding} is intentionally a coarser, D1-exact overview
 * figure and does NOT equal {@code SettlementGroupPreview.totalTarget}/{@code totalDelta},
 * which are the cross-company-only settleable amounts. Do not present the two as reconcilable;
 * the Settle dialog (preview) total is the authoritative amount that will be settled.
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
