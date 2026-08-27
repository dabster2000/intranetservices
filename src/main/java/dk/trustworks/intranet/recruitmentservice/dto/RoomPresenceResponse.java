package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Who is in the room, and how many lines each has — NEVER text (room spec
 * 2026-08-26 §5.2: "Presence, not sharing"; the blind rule is preserved
 * exactly). A contract test asserts no prose can ride here: the DTO has
 * no field that could carry it.
 *
 * @param entries one row per co-interviewer with a draft
 */
public record RoomPresenceResponse(List<PresenceEntry> entries) {

    /**
     * @param authorUuid the interviewer
     * @param name       display name
     * @param lineCount  how many lines their draft holds
     * @param lastSeenAt UTC of their last autosave
     * @param active     touched within the presence window (~60 s)
     */
    public record PresenceEntry(String authorUuid, String name, int lineCount,
                                String lastSeenAt, boolean active) {
    }
}
