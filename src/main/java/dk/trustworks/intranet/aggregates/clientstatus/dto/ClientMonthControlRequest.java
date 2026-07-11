package dk.trustworks.intranet.aggregates.clientstatus.dto;

/**
 * Request body for upserting the controlling state of one client-month cell. A dedicated DTO — the
 * {@code ClientMonthControl} entity is never bound to the request to avoid mass-assignment.
 *
 * <p>Semantics: {@code approved == null} leaves the approval unchanged; {@code true} approves (and
 * snapshots the current values); {@code false} clears the approval. {@code note == null} leaves the
 * note unchanged; {@code ""} clears it; any other value sets it. At least one of the two must be
 * non-null.</p>
 */
public record ClientMonthControlRequest(
        String clientUuid,
        String monthKey,        // "YYYYMM"
        Boolean approved,       // nullable: null=unchanged, true=approve, false=unapprove
        String note             // nullable: null=unchanged, ""=clear, else=set
) {}
