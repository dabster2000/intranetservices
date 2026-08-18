package dk.trustworks.intranet.aggregates.finance.health;

import dk.trustworks.intranet.aggregates.finance.health.SalaryGLAnomalyCheck.Anomaly;
import dk.trustworks.intranet.aggregates.finance.health.SalaryGLAnomalyCheck.GapKind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free regression tests for the salary-GL anomaly detector.
 *
 * <p>Locks in the two properties that made the FY25/26 year-end invisible
 * (verified against production 2026-08-17):
 * <ul>
 *   <li>The GL aggregate must separate BOOKED from BOOKED+DRAFT and test the
 *       BOOKED figure. Without the {@code postingstatus} predicate, A/S 2026-06
 *       and both subsidiaries' 2026-04/05/06 scored 0.97–1.0 of intended off a
 *       pure draft journal and were never flagged — seven company-months.</li>
 *   <li>The aggregate must be signed, not {@code ABS}, so a payroll correction
 *       reduces the total instead of inflating it.</li>
 * </ul>
 */
class SalaryGLAnomalyCheckTest {

    private static final double THRESHOLD = 0.5;

    // ── SQL shape ────────────────────────────────────────────────────────────

    @Test
    void detectSql_testsBookedOnly_andStillReportsTheDraft() {
        String sql = SalaryGLAnomalyCheck.DETECT_SQL;
        assertTrue(sql.contains("SUM(CASE WHEN fd.postingstatus = 'BOOKED' THEN fd.amount ELSE 0 END) AS gl_salary"),
                "gl_salary must count BOOKED rows only — a draft is a warning, not a pass");
        assertTrue(sql.contains("SUM(fd.amount) AS gl_salary_with_draft"),
                "gl_salary_with_draft must carry every posting status so the gap can be classified");
        assertTrue(sql.contains("COALESCE(g.gl_salary, 0) < SUM(fsm.salary_sum) * :threshold"),
                "the anomaly test must run on the BOOKED figure, not on booked+draft");
    }

    @Test
    void detectSql_neverUsesAbs() {
        assertFalse(SalaryGLAnomalyCheck.DETECT_SQL.contains("ABS("),
                "GL salary must be summed signed — ABS turns a payroll correction into extra cost");
    }

    @Test
    void lookbackDefault_coversAFullYear() throws Exception {
        // A 3-month window run in August 2026 saw May/Jun/Jul only, so the unposted
        // April subsidiary payroll fell outside it and was never examined again.
        Field f = SalaryGLAnomalyCheck.class.getDeclaredField("lookbackMonths");
        String defaultValue = f.getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class)
                .defaultValue();
        assertEquals("12", defaultValue,
                "lookback must span a full year so an open fiscal year stays covered end to end");
    }

    // ── gap classification ───────────────────────────────────────────────────

    @Test
    void draftedButUnposted_isDistinguishedFromAbsent() {
        // TWT 2026-06 as it stands in production: nothing booked, a full draft journal.
        Anomaly drafted = new Anomaly("TWT", 2026, 6, 0.0, 1_517_622.0, 1_564_701.0);
        assertEquals(GapKind.DRAFTED_NOT_POSTED, drafted.kind(THRESHOLD));
        assertEquals(1_517_622.0, drafted.draftSalary(), 0.001);

        // TWT 2026-07: nothing booked and nothing drafted either.
        Anomaly absent = new Anomaly("TWT", 2026, 7, 0.0, 0.0, 1_622_252.0);
        assertEquals(GapKind.ABSENT, absent.kind(THRESHOLD));
        assertEquals(0.0, absent.draftSalary(), 0.001);
    }

    @Test
    void aDraftTooSmallToCloseTheGap_countsAsAbsent() {
        Anomaly barelyDrafted = new Anomaly("TWC", 2026, 5, 0.0, 100_000.0, 852_653.0);
        assertEquals(GapKind.ABSENT, barelyDrafted.kind(THRESHOLD),
                "a draft that would not have satisfied the check is not 'just needs posting'");
    }

    @Test
    void gapAndCoverage_areMeasuredOnBookedOnly() {
        Anomaly a = new Anomaly("TW", 2026, 6, 0.0, 5_703_417.0, 5_894_316.0);
        assertEquals(5_894_316.0, a.gapDkk(), 0.001,
                "the gap is what is missing from the BOOKED ledger, draft notwithstanding");
        assertEquals(0.0, a.coveragePct(), 0.001);
    }

    @Test
    void coverage_isZeroRatherThanNaN_whenNoSalaryIsIntended() {
        assertEquals(0.0, new Anomaly("TW", 2026, 6, 0.0, 0.0, 0.0).coveragePct(), 0.001);
    }

    // ── alert message ────────────────────────────────────────────────────────

    @Test
    void alertMessage_namesTheActionForEachKind() {
        String msg = SalaryGLAnomalyCheck.formatAlertMessage(List.of(
                new Anomaly("TWT", 2026, 6, 0.0, 1_517_622.0, 1_564_701.0),
                new Anomaly("TWT", 2026, 7, 0.0, 0.0, 1_622_252.0)), 12, THRESHOLD);

        assertTrue(msg.contains("drafted, not posted"), "a drafted month must say so");
        assertTrue(msg.contains("absent entirely"), "an absent month must say so");
        assertTrue(msg.contains("draft=1517622"), "the drafted amount tells accounting what to post");
        assertTrue(msg.contains("BOOKED only"), "the reader must know the threshold applies to booked salary");
    }

    @Test
    void alertMessage_countsEveryCell() {
        String msg = SalaryGLAnomalyCheck.formatAlertMessage(List.of(
                new Anomaly("TW", 2026, 6, 0.0, 5_703_417.0, 5_894_316.0),
                new Anomaly("TWT", 2026, 6, 0.0, 1_517_622.0, 1_564_701.0),
                new Anomaly("TWC", 2026, 6, 0.0, 718_350.0, 733_057.0)), 12, THRESHOLD);
        assertTrue(msg.contains("3 (tenant × month) cell(s)"), msg);
    }
}
