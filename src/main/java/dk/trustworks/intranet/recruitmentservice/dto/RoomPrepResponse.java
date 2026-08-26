package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The AI prep pack (room spec 2026-08-26 §9, flagged): catalogue probes
 * specialised to THIS candidate from the CV, answers and prior rounds.
 * Questions, never conclusions — the service validates that every entry
 * ends in a question mark and drops anything else.
 *
 * @param probes specialised probes per subject code
 */
public record RoomPrepResponse(List<PrepSubject> probes) {

    /** @param subjectCode the subject; @param questions the specialised probes */
    public record PrepSubject(String subjectCode, List<String> questions) {
    }
}
