package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus;

/**
 * The verdict on one matched Danløn line: does this company's file get to
 * state this person's balance?
 *
 * <p>Before the gate existed, which company's figures won was decided further
 * downstream by {@code VacationBalanceEngine}, which per (user, ferieår) keeps
 * the batch with the highest effective date and breaks ties on
 * {@code created_at}. Correctness therefore depended on HR uploading the
 * current employer's file last. It happened to hold; upload the A/S file after
 * the Cyber file one month and a group of people silently revert to stale A/S
 * figures with no error anywhere.</p>
 */
public final class ImportCompanyGate {

    private ImportCompanyGate() {
    }

    /**
     * @param resolvedUseruuid the employee the name matcher landed on, or null
     * @param companyAtAsOf    the company the userstatus timeline puts them at
     *                         on the batch's as-of date, or null when the
     *                         timeline cannot say
     * @param batchCompanyuuid the company whose file this is
     *
     * <p>The comparison is company identity and nothing else — never the
     * status or the consultant type. A leaver whose last status is
     * {@code TERMINATED} at Technology still belongs to Technology and must
     * still import: that is the older, still-provisioned employment record of
     * a within-company rehire, and gating on "is active" would drop it while
     * leaving the person's second line in place.</p>
     */
    public static VacationImportRowStatus verdict(String resolvedUseruuid, String companyAtAsOf,
                                                  String batchCompanyuuid) {
        if (resolvedUseruuid == null || resolvedUseruuid.isBlank()) return VacationImportRowStatus.UNMATCHED;
        if (companyAtAsOf == null || companyAtAsOf.isBlank()) return VacationImportRowStatus.UNKNOWN_COMPANY;
        return companyAtAsOf.equals(batchCompanyuuid)
                ? VacationImportRowStatus.AUTO
                : VacationImportRowStatus.OTHER_COMPANY;
    }

    /**
     * True for a row that was auto-matched without ever passing this gate.
     *
     * <p>{@link #verdict} reaches AUTO only through
     * {@code companyAtAsOf.equals(batchCompanyuuid)}, which cannot hold for a
     * null or blank company. So AUTO without a recorded company is not a state
     * this class can produce — it can only have been written before the gate
     * existed, by an upload that never consulted an employment record.</p>
     *
     * <p>Deliberately not extended to MANUAL: that legitimately carries no
     * company when the timeline cannot place the person, and it is an explicit
     * human decision the gate never overrules in the first place.</p>
     */
    public static boolean isUngatedAutoRow(VacationImportRowStatus status, String companyAtAsOf) {
        return status == VacationImportRowStatus.AUTO && (companyAtAsOf == null || companyAtAsOf.isBlank());
    }
}
