package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.FactField;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.FactGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the fact vocabulary (room spec 2026-08-26 §4.2). Three definitions
 * of this list exist — this class, the BFF route's {@code NOTE_FIELDS} and
 * the TS union in {@code lib/types/recruitment.ts} — and each side pins
 * the SAME literal list, so a drift on either side fails a contract test
 * there. Do not edit one without the others.
 */
class RecruitmentFactVocabularyTest {

    /** The pinned wire-format list, ledger order — the cross-repo contract. */
    private static final List<String> CONTRACT = List.of(
            "SALARY_EXPECTATION", "SALARY_COMPONENTS", "CURRENT_PACKAGE",
            "EARLIEST_START", "PREFERRED_START", "HARD_DATES",
            "OTHER_PROCESSES", "DECISION_DRIVERS", "DECISION_DATE",
            "INTERNAL_REFERENCE", "EXTERNAL_REFERENCE", "REFERENCE_TAKEN");

    @Test
    void vocabulary_matchesTheCrossRepoContractExactly() {
        assertEquals(CONTRACT, List.copyOf(RecruitmentFactVocabulary.keys()),
                "the vocabulary must equal the pinned contract list, in order — "
                        + "the BFF NOTE_FIELDS and the TS union pin the same list");
    }

    @Test
    void freshnessWindows_areTheSpecNumbers() {
        // Competition 14 · Timing 30 · Compensation 60 · References never (§4.3).
        assertEquals(14, FactGroup.COMPETITION.freshnessDays());
        assertEquals(30, FactGroup.TIMING.freshnessDays());
        assertEquals(60, FactGroup.COMPENSATION.freshnessDays());
        assertNull(FactGroup.REFERENCES.freshnessDays());
    }

    @Test
    void compensationGroup_isExactlyTheCompScopedSet() {
        Set<String> compScoped = Set.of("SALARY_EXPECTATION", "SALARY_COMPONENTS",
                "CURRENT_PACKAGE");
        for (String key : RecruitmentFactVocabulary.keys()) {
            assertEquals(compScoped.contains(key), RecruitmentFactVocabulary.isCompScoped(key),
                    key);
        }
    }

    @Test
    void competitionGroup_isExactlyTheNotificationExcludedSet() {
        Set<String> competition = Set.of("OTHER_PROCESSES", "DECISION_DRIVERS", "DECISION_DATE");
        for (String key : RecruitmentFactVocabulary.keys()) {
            assertEquals(competition.contains(key),
                    RecruitmentFactVocabulary.isCompetitionScoped(key), key);
        }
    }

    /**
     * The deliberately-absent categories (§4.2): family, health, partner,
     * pregnancy, politics, religion, age. The absence IS the design — a
     * field would convert an offhand remark into structured, queryable,
     * discriminatory data. This test is the tripwire for "it might be
     * useful": adding one of these words to a key fails loudly.
     */
    @Test
    void forbiddenCategories_stayAbsentPermanently() {
        List<String> forbiddenSubstrings = List.of("FAMILY", "HEALTH", "PARTNER", "SPOUSE",
                "PREGNAN", "CHILD", "POLITIC", "RELIGIO", "MARITAL");
        for (String key : RecruitmentFactVocabulary.keys()) {
            for (String category : forbiddenSubstrings) {
                assertFalse(key.contains(category),
                        key + " must never exist in the vocabulary (spec §4.2)");
            }
            // AGE as a whole token only — CURRENT_PACKAGE is fine.
            for (String token : key.split("_")) {
                assertFalse("AGE".equals(token),
                        key + " must never exist in the vocabulary (spec §4.2)");
            }
        }
    }

    @Test
    void unknownKeys_areRejected() {
        assertFalse(RecruitmentFactVocabulary.isKnown("SOME_OTHER_FIELD"));
        assertFalse(RecruitmentFactVocabulary.isKnown(null));
        assertTrue(RecruitmentFactVocabulary.isKnown("EARLIEST_START"));
        // Retired 2026-08-27 — the whole PRACTICALITIES group and the notice
        // period. Old NOTE_ADDED events carrying these keys stay in the event
        // stream; the vocabulary no longer derives ledger rows from them.
        assertFalse(RecruitmentFactVocabulary.isKnown("NOTICE_PERIOD"));
        assertFalse(RecruitmentFactVocabulary.isKnown("LOCATION_CONSTRAINTS"));
        assertFalse(RecruitmentFactVocabulary.isKnown("WORK_PERMIT"));
    }

    @Test
    void placeholderMatching_derivesRequiredness() {
        FactField earliestStart = RecruitmentFactVocabulary.forKey("EARLIEST_START").orElseThrow();
        // "required if the position's contract template needs it" (§4.3) —
        // matching is a case-insensitive substring over the aliases.
        assertTrue(RecruitmentFactVocabulary.requiredByPlaceholder(earliestStart, "START_DATE"));
        assertTrue(RecruitmentFactVocabulary.requiredByPlaceholder(earliestStart, "start_date"));
        assertFalse(RecruitmentFactVocabulary.requiredByPlaceholder(earliestStart, "MONTHLY_SALARY"));

        FactField salary = RecruitmentFactVocabulary.forKey("SALARY_EXPECTATION").orElseThrow();
        assertTrue(RecruitmentFactVocabulary.requiredByPlaceholder(salary, "MONTHLY_SALARY"));
        assertTrue(salary.defaultRequired(),
                "the offer conversation cannot start without the salary expectation");
    }
}
