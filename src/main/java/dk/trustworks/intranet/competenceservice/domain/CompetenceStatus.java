package dk.trustworks.intranet.competenceservice.domain;

/**
 * The status vocabulary, shared by both tracks and the matrix cell.
 *
 * <p>These are the six conditions the concept distinguishes, plus the "awaiting
 * approval" state that exists because a pass is not yet evidence. They are ranked by
 * urgency rather than by progress: a cell is coloured by the most pressing thing wrong
 * with it, not by how far along the person is.
 */
public final class CompetenceStatus {

    private CompetenceStatus() {
    }

    /** Microcourse track status (spec §5.3). */
    public enum Course {
        NOT_PUBLISHED,
        NOT_STARTED,
        RETAKE_REQUIRED,
        OVERDUE,
        COMPLETED
    }

    /** Test track status (spec §5.4). */
    public enum Test {
        NOT_PUBLISHED,
        NOT_PASSED,
        RETAKE_REQUIRED,
        OVERDUE,
        APPROVED,
        AWAITING_APPROVAL
    }

    /**
     * Matrix cell status (spec §5.5) — the two tracks collapsed to one verdict, ranked
     * by urgency. The order of the constants is the ranking.
     */
    public enum Cell {
        NOT_PUBLISHED,
        OVERDUE,
        RETAKE_REQUIRED,
        NOT_STARTED,
        COURSE_COMPLETED,
        AWAITING_APPROVAL,
        APPROVED
    }
}
