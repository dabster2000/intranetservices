package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus.Bucket;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no DB) for the company gate and for the bucket contract
 * every reader of the status depends on.
 */
class ImportCompanyGateTest {

    private static final String AS = "company-as";
    private static final String TECH = "company-tech";
    private static final String USER = "user-a";

    @Test
    void anEmployeeOfThisCompanyAutoMatches() {
        assertEquals(VacationImportRowStatus.AUTO, ImportCompanyGate.verdict(USER, TECH, TECH));
    }

    /**
     * The rule the whole change exists for: payroll moves the available
     * balance on a transfer, so the A/S export's line for someone who is now
     * at Technology is a superseded record. Importing it would overwrite the
     * correct figures — and which file won used to depend on nothing more than
     * the order HR uploaded them in.
     */
    @Test
    void anEmployeeOfAnotherCompanyIsSkipped() {
        assertEquals(VacationImportRowStatus.OTHER_COMPANY, ImportCompanyGate.verdict(USER, AS, TECH));
    }

    /** Never silently dropped — HR must resolve it before the batch can apply. */
    @Test
    void noDeterminableCompanyBlocks() {
        assertEquals(VacationImportRowStatus.UNKNOWN_COMPANY, ImportCompanyGate.verdict(USER, null, TECH));
        assertEquals(VacationImportRowStatus.UNKNOWN_COMPANY, ImportCompanyGate.verdict(USER, "  ", TECH));
    }

    /** No person, no company question — the name matcher's verdict stands. */
    @Test
    void anUnresolvedNameStaysUnmatched() {
        assertEquals(VacationImportRowStatus.UNMATCHED, ImportCompanyGate.verdict(null, TECH, TECH));
        assertEquals(VacationImportRowStatus.UNMATCHED, ImportCompanyGate.verdict("  ", TECH, TECH));
    }

    /**
     * The buckets must partition the enum: every status behaves in exactly one
     * way, and the three sets together cover all of them. This is what lets
     * {@code rowCount = matched + unmatched + skipped} hold by construction and
     * what makes the derived skipped count trustworthy.
     */
    @Test
    void everyStatusBelongsToExactlyOneBucket() {
        Set<VacationImportRowStatus> all = EnumSet.allOf(VacationImportRowStatus.class);

        Set<VacationImportRowStatus> applies = bucket(all, Bucket.APPLIES);
        Set<VacationImportRowStatus> blocks = bucket(all, Bucket.BLOCKS);
        Set<VacationImportRowStatus> skipped = bucket(all, Bucket.SKIPPED);

        assertEquals(all.size(), applies.size() + blocks.size() + skipped.size(),
                "the three buckets must be disjoint and cover every status");
        assertEquals(EnumSet.of(VacationImportRowStatus.AUTO, VacationImportRowStatus.MANUAL), applies,
                "only a resolved employee of this company contributes figures");
        assertEquals(EnumSet.of(VacationImportRowStatus.UNMATCHED, VacationImportRowStatus.UNKNOWN_COMPANY), blocks,
                "a person the system cannot place must stop the apply, not be dropped");
        assertEquals(EnumSet.of(VacationImportRowStatus.IGNORED, VacationImportRowStatus.OTHER_COMPANY), skipped);
    }

    @Test
    void anAutoRowWithoutACompanyIsAPreGateUpload() {
        // verdict() can only reach AUTO via companyAtAsOf.equals(batchCompany),
        // so AUTO-with-no-company is unreachable through this class. A batch
        // uploaded before the gate shipped is the only way to hold one, and
        // applying it would post exactly the stale cross-company baselines the
        // gate exists to stop.
        assertTrue(ImportCompanyGate.isUngatedAutoRow(VacationImportRowStatus.AUTO, null));
        assertTrue(ImportCompanyGate.isUngatedAutoRow(VacationImportRowStatus.AUTO, "  "));
    }

    @Test
    void aGatedOrHumanDecidedRowIsNotTreatedAsPreGate() {
        assertFalse(ImportCompanyGate.isUngatedAutoRow(VacationImportRowStatus.AUTO, TECH),
                "an AUTO row that recorded a company went through the gate");
        // MANUAL is the deliberate blind spot: HR's override legitimately
        // carries no company when the timeline cannot place the person, and it
        // is the one verdict the gate never overrules anyway.
        assertFalse(ImportCompanyGate.isUngatedAutoRow(VacationImportRowStatus.MANUAL, null));
        for (VacationImportRowStatus status : EnumSet.complementOf(EnumSet.of(VacationImportRowStatus.AUTO))) {
            assertFalse(ImportCompanyGate.isUngatedAutoRow(status, null),
                    status + " is not an auto-match and must never read as a pre-gate row");
        }
    }

    @Test
    void everyVerdictThisClassProducesSurvivesThePreGateCheck() {
        // The round-trip that makes the check exact rather than heuristic: no
        // output of verdict() may look like a pre-gate row.
        assertFalse(ImportCompanyGate.isUngatedAutoRow(
                ImportCompanyGate.verdict(USER, TECH, TECH), TECH));
        assertFalse(ImportCompanyGate.isUngatedAutoRow(
                ImportCompanyGate.verdict(USER, AS, TECH), AS));
        assertFalse(ImportCompanyGate.isUngatedAutoRow(
                ImportCompanyGate.verdict(USER, null, TECH), null));
        assertFalse(ImportCompanyGate.isUngatedAutoRow(
                ImportCompanyGate.verdict(null, null, TECH), null));
    }

    private static Set<VacationImportRowStatus> bucket(Set<VacationImportRowStatus> all, Bucket bucket) {
        return all.stream().filter(status -> status.bucket() == bucket).collect(Collectors.toSet());
    }
}
