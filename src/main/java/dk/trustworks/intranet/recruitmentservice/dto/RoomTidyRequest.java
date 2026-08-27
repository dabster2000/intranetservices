package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Body for {@code POST /recruitment/interviews/{uuid}/notes/tidy} — the
 * room's Tidy pass at land (room spec 2026-08-26 §9, flagged). Shorthand
 * becomes prose per subject; verbatim lines survive untouched; the model
 * may not write into a subject that has no lines — the service enforces
 * that server-side regardless of what the model returns.
 *
 * @param lines the tagged lines to tidy
 */
public record RoomTidyRequest(List<TidyLine> lines) {

    /**
     * @param id          stable line id
     * @param text        the interviewer's words
     * @param subjectCode scorecard subject the line is tagged to; null = loose
     * @param verbatim    the candidate's own words — passes through Tidy untouched
     */
    public record TidyLine(String id, String text, String subjectCode, boolean verbatim) {
    }
}
