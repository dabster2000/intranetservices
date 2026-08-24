package dk.trustworks.intranet.vacationservice.model.enums;

/**
 * The review verdict on one Danløn line.
 *
 * <p>Six constants, three behaviours. Rather than let every reader enumerate
 * the constants it cares about — which is how a newly added status silently
 * ends up on the wrong side of a filter — each status declares its
 * {@link Bucket} here, once, and readers ask {@link #bucket()}.</p>
 */
public enum VacationImportRowStatus {

    /** The matcher resolved the name to exactly one employee of this company. */
    AUTO,

    /** HR pointed the line at an employee by hand. Overrides every gate below. */
    MANUAL,

    /** No employee — or more than one — carries that Danløn name. */
    UNMATCHED,

    /** HR said "not one of ours". Contributes nothing and blocks nothing. */
    IGNORED,

    /**
     * The employee exists but the userstatus timeline puts them at a different
     * company on the batch's as-of date. When someone transfers between the
     * Trustworks companies, payroll moves the <em>available</em> balance and
     * drops the used amount, so the receiving company's {@code Optjent dage}
     * already contains the old company's remainder — the old company's file
     * still lists the person, but with a historical, now-superseded record.
     * Importing it would overwrite the correct figures with stale ones.
     */
    OTHER_COMPANY,

    /**
     * The employee exists but has no userstatus at or before the as-of date,
     * so nothing says which company holds their balance. Never silently
     * dropped: this blocks the apply until HR resolves it, because guessing is
     * exactly the failure mode the company gate exists to remove.
     */
    UNKNOWN_COMPANY;

    /** What a status does to the apply. */
    public enum Bucket {
        /** Contributes its figures to a baseline. */
        APPLIES,
        /** Refuses the apply until HR resolves it. */
        BLOCKS,
        /** Contributes nothing, but does not stand in the way. */
        SKIPPED
    }

    /**
     * @return the behaviour this status implies.
     *
     * <p>The switch is deliberately exhaustive with no {@code default}: adding
     * a seventh constant is then a compile error here instead of a row that
     * quietly slips past the apply gate and into a baseline.</p>
     */
    public Bucket bucket() {
        return switch (this) {
            case AUTO, MANUAL -> Bucket.APPLIES;
            case UNMATCHED, UNKNOWN_COMPANY -> Bucket.BLOCKS;
            case OTHER_COMPANY, IGNORED -> Bucket.SKIPPED;
        };
    }
}
