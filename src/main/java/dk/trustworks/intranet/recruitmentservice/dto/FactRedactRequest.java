package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Withdraw one recorded fact (change request 2026-08-28).
 *
 * <p>Just the id of the {@code NOTE_ADDED} being taken back. Deliberately no
 * reason field: a free-text reason on a retraction is somewhere a real
 * candidate's name ends up, in a row whose whole purpose is that a statement
 * about them should stop being visible. Who withdrew it and when is on the
 * event; why is a conversation, not a column.
 *
 * @param eventId the fact-bearing {@code NOTE_ADDED} to withdraw
 */
public record FactRedactRequest(String eventId) {
}
