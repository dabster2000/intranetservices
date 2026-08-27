package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.FactField;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactStates.FactNote;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactStates.Persisted;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactStates.State;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fact state machine (room spec 2026-08-26 §4.3): UNKNOWN → ASKED →
 * STATED → CONFIRMED, with STALE derived at read time from the group's
 * freshness window. Shared by the ledger derivation and the projector —
 * this test is what keeps the two honest.
 */
class RecruitmentFactStatesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 1, 10, 0);
    private static final FactField COMPETITION =
            RecruitmentFactVocabulary.forKey("OTHER_PROCESSES").orElseThrow();
    private static final FactField TIMING =
            RecruitmentFactVocabulary.forKey("EARLIEST_START").orElseThrow();
    private static final FactField NEVER_STALE =
            RecruitmentFactVocabulary.forKey("EXTERNAL_REFERENCE").orElseThrow();

    private static FactNote note(long seq, String field, LocalDateTime at,
                                 boolean asked, boolean confirmed) {
        return new FactNote(seq, field, at, asked, confirmed);
    }

    @Test
    void unknown_isTheAbsentRow() {
        assertEquals(State.UNKNOWN, RecruitmentFactStates.effective(TIMING, null, T0));
    }

    @Test
    void asked_recordsTheSpentQuestion() {
        Map<String, Persisted> folded = RecruitmentFactStates.fold(List.of(
                note(1, "EARLIEST_START", T0, true, false)));
        Persisted persisted = folded.get("EARLIEST_START");
        assertEquals("ASKED", persisted.state());
        assertNull(persisted.lastStatedAt());
        assertEquals(State.ASKED, RecruitmentFactStates.effective(TIMING, persisted, T0));
    }

    @Test
    void stated_thenConfirmed_endsConfirmed() {
        Map<String, Persisted> folded = RecruitmentFactStates.fold(List.of(
                note(1, "EARLIEST_START", T0, false, false),
                note(2, "EARLIEST_START", T0.plusDays(3), false, true)));
        assertEquals("CONFIRMED", folded.get("EARLIEST_START").state());
        assertEquals(State.CONFIRMED,
                RecruitmentFactStates.effective(TIMING, folded.get("EARLIEST_START"),
                        T0.plusYears(1)));
    }

    /**
     * "Asked again, still nothing" must not erase what a previous round DID
     * get — an ASKED note never downgrades an existing value (§4.3).
     */
    @Test
    void askedAfterStated_keepsTheValue() {
        Map<String, Persisted> folded = RecruitmentFactStates.fold(List.of(
                note(1, "EARLIEST_START", T0, false, false),
                note(2, "EARLIEST_START", T0.plusDays(3), true, false)));
        Persisted persisted = folded.get("EARLIEST_START");
        assertEquals("STATED", persisted.state());
        assertEquals(T0, persisted.lastStatedAt());
        assertEquals(2, persisted.lastValueEventSeq(), "the seq still advances — replay guard");
    }

    @Test
    void competitionFacts_goStaleAfterFourteenDays() {
        Persisted stated = new Persisted("STATED", 1, T0);
        assertEquals(State.STATED,
                RecruitmentFactStates.effective(COMPETITION, stated, T0.plusDays(14)));
        assertEquals(State.STALE,
                RecruitmentFactStates.effective(COMPETITION, stated, T0.plusDays(15)));
    }

    @Test
    void timingFacts_goStaleAfterThirtyDays() {
        Persisted stated = new Persisted("STATED", 1, T0);
        assertEquals(State.STATED,
                RecruitmentFactStates.effective(TIMING, stated, T0.plusDays(30)));
        assertEquals(State.STALE,
                RecruitmentFactStates.effective(TIMING, stated, T0.plusDays(31)));
    }

    @Test
    void referencesAndConfirmations_neverGoStale() {
        assertEquals(State.STATED, RecruitmentFactStates.effective(NEVER_STALE,
                new Persisted("STATED", 1, T0), T0.plusYears(5)));
        assertEquals(State.CONFIRMED, RecruitmentFactStates.effective(COMPETITION,
                new Persisted("CONFIRMED", 1, T0), T0.plusYears(5)));
    }

    @Test
    void fold_ignoresUnknownFieldsAndOrdersBySeq() {
        Map<String, Persisted> folded = RecruitmentFactStates.fold(List.of(
                note(5, "EARLIEST_START", T0.plusDays(5), false, false),
                note(2, "NOT_A_FIELD", T0, false, false),
                note(1, "EARLIEST_START", T0, false, true)));
        assertEquals(1, folded.size());
        // seq 5 (unconfirmed) is newer than seq 1 (confirmed) — newest wins.
        assertEquals("STATED", folded.get("EARLIEST_START").state());
        assertEquals(T0.plusDays(5), folded.get("EARLIEST_START").lastStatedAt());
    }

    /** The projector's idempotency root: replay of an already-seen seq is a no-op upstream. */
    @Test
    void apply_matchesFoldForIncrementalUse() {
        FactNote first = note(1, "EARLIEST_START", T0, false, false);
        FactNote second = note(2, "EARLIEST_START", T0.plusDays(1), false, true);
        Persisted incremental = RecruitmentFactStates.apply(
                RecruitmentFactStates.apply(null, first), second);
        Persisted folded = RecruitmentFactStates.fold(List.of(first, second))
                .get("EARLIEST_START");
        assertEquals(folded, incremental);
    }
}
