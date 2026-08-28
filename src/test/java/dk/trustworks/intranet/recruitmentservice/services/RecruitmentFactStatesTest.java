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

    // ---- Retraction (change request 2026-08-28) --------------------------
    //
    // Redaction is expressed as a re-fold over the SURVIVING notes — the
    // ledger filters them out before folding and the projector re-folds that
    // one (candidate, field) from scratch, because an incremental `apply`
    // has no inverse and withdrawing the newest statement has to move the
    // state BACKWARDS. These lock the semantics both of them depend on.

    @Test
    void redactingTheNewestValue_fallsBackToTheOneBeforeIt() {
        List<FactNote> surviving = List.of(
                note(1, "EARLIEST_START", T0, false, false));
        // seq 2 ("1 September", say) was withdrawn and is simply not here.

        Map<String, Persisted> folded = RecruitmentFactStates.fold(surviving);

        Persisted persisted = folded.get("EARLIEST_START");
        assertEquals("STATED", persisted.state());
        assertEquals(1, persisted.lastValueEventSeq());
        assertEquals(State.STATED, RecruitmentFactStates.effective(TIMING, persisted, T0));
    }

    @Test
    void redactingEveryStatement_leavesNoEntryAtAll() {
        // Not "an entry that says nothing" — no entry, which is what makes
        // the projector delete the row and the field read UNKNOWN again.
        Map<String, Persisted> folded = RecruitmentFactStates.fold(List.of());

        assertNull(folded.get("EARLIEST_START"));
        assertEquals(State.UNKNOWN,
                RecruitmentFactStates.effective(TIMING, folded.get("EARLIEST_START"), T0));
    }

    @Test
    void redactingTheValueUnderAnAskedNote_leavesTheSpentQuestion() {
        // "Asked, nothing usable" at seq 2 kept an earlier value alive; once
        // that value is withdrawn only the spent question survives, and it
        // must not be reported as a value the offer can rely on.
        Map<String, Persisted> withValue = RecruitmentFactStates.fold(List.of(
                note(1, "EARLIEST_START", T0, false, false),
                note(2, "EARLIEST_START", T0.plusHours(1), true, false)));
        assertEquals("STATED", withValue.get("EARLIEST_START").state());

        Map<String, Persisted> afterRedaction = RecruitmentFactStates.fold(List.of(
                note(2, "EARLIEST_START", T0.plusHours(1), true, false)));

        Persisted persisted = afterRedaction.get("EARLIEST_START");
        assertEquals("ASKED", persisted.state());
        assertNull(persisted.lastStatedAt());
        assertEquals(State.ASKED, RecruitmentFactStates.effective(TIMING, persisted, T0));
    }

    @Test
    void redactingAConfirmation_fallsBackToTheUnconfirmedValue() {
        // The offer relies on CONFIRMED, so a withdrawn confirmation must
        // not leave the field still reading settled.
        Map<String, Persisted> folded = RecruitmentFactStates.fold(List.of(
                note(1, "EARLIEST_START", T0, false, false)));

        assertEquals("STATED", folded.get("EARLIEST_START").state());
    }

    @Test
    void refoldIsPure_soReplayingARedactionChangesNothing() {
        // The projector's re-fold is deliberately not seq-guarded (a guard
        // would refuse to move the row backwards), so idempotence has to
        // come from the fold itself.
        List<FactNote> surviving = List.of(
                note(1, "EARLIEST_START", T0, false, false),
                note(3, "EARLIEST_START", T0.plusDays(1), false, true));

        assertEquals(RecruitmentFactStates.fold(surviving),
                RecruitmentFactStates.fold(surviving));
    }
}
