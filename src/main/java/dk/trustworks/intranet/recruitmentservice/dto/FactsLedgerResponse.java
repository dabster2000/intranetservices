package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The hiring-fact ledger for one candidate (Interview Room design spec
 * 2026-08-26 §4.3, {@code GET /recruitment/candidates/{uuid}/facts}):
 * every vocabulary field with its derived state, the newest value where
 * the viewer may read it, and the append-only drift history — the screen
 * a closing conversation reads.
 *
 * @param facts            one entry per vocabulary field, ledger order
 * @param requiredTotal    fields the offer needs (dossier placeholders ∪ defaults)
 * @param requiredGathered required fields in STATED or CONFIRMED
 * @param compTier         whether the viewer reads compensation values
 */
public record FactsLedgerResponse(List<FactEntry> facts,
                                  int requiredTotal,
                                  int requiredGathered,
                                  boolean compTier) {

    /**
     * One field of the ledger.
     *
     * @param field    vocabulary key
     * @param group    COMPENSATION | TIMING | COMPETITION | REFERENCES
     * @param label    human label
     * @param askRole  whose job it is to raise it (guidance, spec §7.1)
     * @param state    UNKNOWN | ASKED | STATED | CONFIRMED | STALE (derived, read-time)
     * @param required the position's contract template needs it (or a default)
     * @param value    newest value; {@code null} when UNKNOWN/ASKED or redacted
     * @param redacted comp field withheld from a non-comp viewer (lock chip)
     * @param statedAt UTC ISO timestamp of the newest value, null when none
     * @param history  append-only drift, oldest first; empty when redacted
     */
    public record FactEntry(String field, String group, String label, String askRole,
                            String state, boolean required, String value, boolean redacted,
                            String statedAt, List<FactHistoryEntry> history) {
    }

    /**
     * One historical statement of a fact — the drift timeline (§4.3).
     *
     * @param eventId       the {@code NOTE_ADDED} behind this line — what a
     *                      redaction addresses, so the client never has to
     *                      guess which statement it is withdrawing
     * @param value         the stated text ({@code null} for an ASKED entry,
     *                      and for a redacted one — a retraction that keeps
     *                      showing the value has not retracted anything)
     * @param occurredAt    UTC ISO timestamp
     * @param interviewUuid provenance, when captured in a room
     * @param outcome       {@code "ASKED"} or null
     * @param confirmed     restated / settled in the offer conversation
     * @param redacted      withdrawn by {@code FACT_REDACTED}; the line stays
     *                      in the history because "this was recorded and then
     *                      taken back" is the audit trail, and hiding it would
     *                      make the drift history lie about its own gaps
     */
    public record FactHistoryEntry(String eventId, String value, String occurredAt,
                                   String interviewUuid, String outcome, boolean confirmed,
                                   boolean redacted) {
    }
}
