package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Result of the room's atomic land (room spec 2026-08-26 §5.3).
 *
 * @param scorecardUuid the submitted scorecard; null for kinds that take none
 * @param factsRecorded how many NOTE_ADDED fact events were appended
 * @param draftDeleted  whether a draft row existed and was removed
 */
public record RoomLandResponse(String scorecardUuid, int factsRecorded, boolean draftDeleted) {
}
