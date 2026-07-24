package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope of {@code GET /recruitment/interviews/rooms} — the bookable
 * meeting rooms from Graph {@code /places/microsoft.graph.room}. Empty
 * when the Graph calendar toggle is off or the lookup fails (the UI hides
 * the room picker and scheduling degrades to free-text location).
 */
public record MeetingRoomsResponse(List<MeetingRoom> rooms, int totalCount) {

    /** One bookable room — org resource data, PII-free. {@code available}
     * is set only when the caller asked for a time window ({@code start}
     * query param): {@code true}/{@code false} from the Graph free/busy
     * lookup, {@code null} when no window was given or the lookup failed
     * (callers should then show the room rather than hide it). */
    public record MeetingRoom(
            String displayName,
            String emailAddress,
            Integer capacity,
            String building,
            Boolean available
    ) {
    }
}
