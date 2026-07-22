package dk.trustworks.intranet.recruitmentservice.model.enums;

import java.util.List;

/**
 * The canonical, stable stage vocabulary for recruitment pipelines (ATS
 * spec §4.1/§4.2). A position's {@code stage_set} is an <em>ordered
 * subset</em> of this catalog — reporting never has to interpret free-text
 * stage names (an explicit Airtable lesson).
 * <p>
 * Declaration order IS the canonical pipeline order; stage-set validation
 * ({@code RecruitmentPositionDefaults#validateStageSet}) relies on
 * {@link #ordinal()}. Append new stages in pipeline position, never
 * reorder or rename emitted values.
 */
public enum RecruitmentStage {

    SCREENING,
    INTERVIEW_1,
    INTERVIEW_2,
    /** Extra round used by the partner track (and senior hires). */
    INTERVIEW_3,
    OFFER,
    HIRED;

    /** Stage codes every position must include: entry, offer and exit. */
    public static final List<RecruitmentStage> MANDATORY = List.of(SCREENING, OFFER, HIRED);
}
