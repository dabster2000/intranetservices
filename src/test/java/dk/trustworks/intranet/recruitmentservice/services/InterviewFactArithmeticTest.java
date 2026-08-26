package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The room's date arithmetic (room spec 2026-08-26 §5.2/§5.4) — the check
 * that flags "1 Feb is not reachable with 3 months' notice" while the
 * candidate is still in the chair. No model involved, mirrored by the
 * frontend's {@code factArithmetic.ts}.
 */
class InterviewFactArithmeticTest {

    private static final LocalDate LATE_AUGUST = LocalDate.of(2026, 8, 26);

    @Test
    void parsesDanishAndEnglishNoticeShorthand() {
        assertEquals(Optional.of(3), InterviewFactArithmetic.parseNoticeMonths("3 mdr opsigelse"));
        assertEquals(Optional.of(3), InterviewFactArithmetic.parseNoticeMonths("3 måneder"));
        assertEquals(Optional.of(1), InterviewFactArithmetic.parseNoticeMonths("1 month"));
        assertEquals(Optional.of(6), InterviewFactArithmetic.parseNoticeMonths("6 mdr til udgangen af en måned"));
        assertEquals(Optional.of(1), InterviewFactArithmetic.parseNoticeMonths("14 dages... 2 uger"));
        assertEquals(Optional.of(3), InterviewFactArithmetic.parseNoticeMonths("3"));
        assertEquals(Optional.empty(), InterviewFactArithmetic.parseNoticeMonths("løbende"));
        assertEquals(Optional.empty(), InterviewFactArithmetic.parseNoticeMonths(null));
    }

    /**
     * The Danish convention: N months to the END of a month — resigning
     * 26 Aug with 3 months' notice runs to 30 Nov, so the earliest start
     * is 1 Dec.
     */
    @Test
    void earliestStart_isFirstOfMonthAfterNoticeRunsOut() {
        assertEquals(LocalDate.of(2026, 12, 1),
                InterviewFactArithmetic.earliestStart(LATE_AUGUST, 3));
        assertEquals(LocalDate.of(2026, 9, 1),
                InterviewFactArithmetic.earliestStart(LATE_AUGUST, 0));
    }

    @Test
    void unreachableStart_isFlaggedWithTheEarliestAlternative() {
        Optional<String> conflict = InterviewFactArithmetic.startConflict(
                LATE_AUGUST, "3 mdr opsigelse", LocalDate.of(2027, 2, 1));
        assertTrue(conflict.isEmpty(), "1 Feb 2027 IS reachable with 3 months from late August");

        conflict = InterviewFactArithmetic.startConflict(
                LATE_AUGUST, "3 mdr opsigelse", LocalDate.of(2026, 10, 1));
        assertTrue(conflict.isPresent(), "1 Oct is inside the notice window");
        assertTrue(conflict.get().contains("2026-12-01"), conflict.get());
    }

    @Test
    void unparseableNoticeOrMissingDate_neverFlags() {
        assertTrue(InterviewFactArithmetic.startConflict(
                LATE_AUGUST, "løbende + gensidig aftale", LocalDate.of(2026, 9, 1)).isEmpty());
        assertTrue(InterviewFactArithmetic.startConflict(
                LATE_AUGUST, "3 mdr", null).isEmpty());
    }
}
