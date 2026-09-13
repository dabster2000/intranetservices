package dk.trustworks.intranet.aggregates.crm.note.dto;

import dk.trustworks.intranet.aggregates.crm.note.services.ClientNoteService;

/**
 * The body of {@code POST /client-notes} — everything a caller is allowed to decide.
 *
 * <p>Deliberately narrow. The author and the timestamp are derived server-side from the
 * request context and are absent here on purpose, so no caller can put a colleague's name
 * on a line they never wrote (the mass-assignment rule; the same posture as
 * {@code AccountSignalRequest}). {@code ClientNoteResourceRoutingTest} asserts the exact
 * component set, so a field cannot be added to this record without somebody deciding to.
 *
 * <p>There is no update body because there is no update: a note can be removed but never
 * rewritten, and {@code DELETE /client-notes/&#123;uuid&#125;} needs no body at all.
 *
 * @param clientUuid the account the note is on; required, and must resolve to a real client
 * @param text       the one line as typed; required, at most
 *                   {@value ClientNoteService#MAX_NOTE_CHARS} characters
 */
public record ClientNoteRequest(String clientUuid, String text) {
}
