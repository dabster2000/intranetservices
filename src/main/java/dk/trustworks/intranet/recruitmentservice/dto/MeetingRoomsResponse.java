package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope of {@code GET /recruitment/interviews/rooms} — the bookable
 * meeting rooms from Graph {@code /places/microsoft.graph.room}. Empty
 * when the Graph calendar toggle is off or the lookup fails (the UI hides
 * the room picker and scheduling degrades to free-text location).
 */
public record MeetingRoomsResponse(List<MeetingRoom> rooms, int totalCount) {

    /** One bookable room — org resource data, PII-free. */
    public record MeetingRoom(
            String displayName,
            String emailAddress,
            Integer capacity,
            String building
    ) {
    }
}
