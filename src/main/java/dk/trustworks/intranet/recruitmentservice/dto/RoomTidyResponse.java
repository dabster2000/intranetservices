package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Tidy's output (room spec 2026-08-26 §9): per-subject prose assembled
 * from the interviewer's own lines, plus — when the alignment flag is on —
 * observations about evidence that reads as a different subject. The
 * alignment check NEVER proposes a score and cannot change one.
 *
 * @param subjects       prose per subject that had lines; never a subject
 *                       that had none
 * @param alignmentNotes rubric observations ("your CULTURE evidence reads
 *                       as FAGLIGHED"), empty when the flag is off
 */
public record RoomTidyResponse(List<TidySubject> subjects, List<String> alignmentNotes) {

    /** @param subjectCode the subject; @param prose the tidied text */
    public record TidySubject(String subjectCode, String prose) {
    }
}
