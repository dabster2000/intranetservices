package dk.trustworks.intranet.recruitmentservice.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body for {@code PUT /recruitment/interviews/{uuid}/notes} — the room's
 * draft autosave (room spec 2026-08-26 §6.2). The whole line array rides
 * on every save; {@code clientRevision} is monotonic and client-assigned:
 * a value LOWER than stored answers 409 and the room offers to reload
 * (last-write-wins guard, deliberately no merge — decision 7 rules out
 * the machinery that would make one sensible). Equal-revision re-PUTs are
 * accepted idempotently and double as the presence heartbeat.
 *
 * @param lines          INoteLine[] as JSON — stored verbatim, whole
 * @param clientRevision monotonic guard value
 */
public record RoomDraftRequest(JsonNode lines, long clientRevision) {
}
