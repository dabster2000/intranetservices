package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Optional body for {@code POST /recruitment/interviews/{uuid}/room/prep} —
 * the interviewer's notes as they stand right now (room spec 2026-08-26 §9,
 * flagged).
 * <p>
 * The prep pack was built once, before the sitting, from the CV and the form
 * answers. Sending the current notes lets the room re-run it <em>during</em>
 * the interview, so the questions it returns follow up on what has actually
 * been said. The re-run is the interviewer pressing the same control again —
 * there is deliberately no polling and no stream: a pack costs an OpenAI
 * round-trip, and a human decides when the conversation has moved enough to
 * be worth one.
 * <p>
 * No body, or no notes, is the ordinary pre-interview pack — the endpoint
 * behaves exactly as it did before this field existed.
 * <p>
 * The line shape is {@link RoomTidyRequest.TidyLine} on purpose, not a copy of
 * it: the room already assembles its notepad in that shape for Tidy, and the
 * two AI paths must see one representation of a note line, not two.
 *
 * @param notes the notepad lines, oldest first; the service keeps the newest
 *              within its own budget and ignores the rest
 */
public record RoomPrepRequest(List<RoomTidyRequest.TidyLine> notes) {
}
