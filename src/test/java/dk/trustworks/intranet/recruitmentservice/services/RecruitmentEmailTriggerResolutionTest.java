package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Which letter answers a pipeline moment — {@link
 * RecruitmentEmailService#findFirstActiveByTrigger} and its read-side
 * mirror {@link RecruitmentEmailService#effectiveTrigger}.
 * <p>
 * Panache statics are mocked over a small in-memory table, so no database
 * is required. The fake refuses any query string it does not recognise:
 * the whole value of these tests rests on the two SQL clauses being the
 * ones the service actually issues, so a reworded query must fail here
 * rather than quietly agree with itself.
 */
class RecruitmentEmailTriggerResolutionTest {

    /** The two queries {@code findFirstActiveByTrigger} is allowed to issue. */
    private static final String ASSIGNED_QUERY = "triggerKey = ?1 and active = true";
    private static final String LEGACY_QUERY = "templateKey = ?1 and triggerKey is null and active = true";

    /**
     * And the two {@code findByTriggerIncludingInactive} issues — the same
     * precedence without the {@code active} clause, because the plan reports
     * a switched-off letter as its own outcome.
     */
    private static final String PLAN_ASSIGNED_QUERY = "triggerKey = ?1";
    private static final String PLAN_LEGACY_QUERY = "templateKey = ?1 and triggerKey is null";

    private final RecruitmentEmailService service = new RecruitmentEmailService();

    @Test
    void twoLettersCanSwapMoments_andEachAnswersItsNewOne() {
        // The point of the column, and the one shape TA actually reaches for:
        // the offer letter and the pooling letter change places. Neither key
        // is renamed, so every EMAIL_SENT event ever recorded still joins.
        RecruitmentEmailTemplate wasPooling = template("POOLED_NOT_NOW", "STAGE_OFFER", true);
        RecruitmentEmailTemplate wasOffer = template("STAGE_OFFER", "POOLED", true);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(wasOffer, wasPooling)) {
            assertSame(wasPooling, service.findFirstActiveByTrigger(List.of("STAGE_OFFER")));
            assertSame(wasOffer, service.findFirstActiveByTrigger(List.of("POOLED")));
        }
    }

    @Test
    void explicitAssignment_winsOverTheLetterSittingOnItsOwnKey() {
        // Rung order, asserted directly. The write-time collision rule keeps
        // this pair out of the table, but only the write path enforces it —
        // the unique index constrains trigger_key alone, so a seed migration
        // or a hand-edited row can still produce it, and then the deliberate
        // assignment is the one that must win.
        RecruitmentEmailTemplate assigned = template("POOLED_NOT_NOW", "STAGE_OFFER", true);
        RecruitmentEmailTemplate incumbent = template("STAGE_OFFER", null, true);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(incumbent, assigned)) {
            assertSame(assigned, service.findFirstActiveByTrigger(List.of("STAGE_OFFER")));
        }
    }

    @Test
    void unassignedRow_stillAnswersItsOwnKey() {
        // Every row predating V562 is in this state. It must behave exactly
        // as it did before the column existed — that is what makes the
        // release behaviour-neutral and the rollback harmless.
        RecruitmentEmailTemplate legacy = template("ACKNOWLEDGEMENT", null, true);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(legacy)) {
            assertSame(legacy, service.findFirstActiveByTrigger(List.of("ACKNOWLEDGEMENT")));
        }
    }

    @Test
    void reassignedRow_stopsAnsweringItsOldKey() {
        // The "triggerKey is null" clause in the legacy rung is what makes
        // this true. Without it a letter moved from STAGE_OFFER to POOLED
        // would answer BOTH, and the reassignment would silently mean "and
        // also" instead of "instead of" — the pooling letter landing on a
        // candidate who was just made an offer.
        RecruitmentEmailTemplate moved = template("STAGE_OFFER", "POOLED", true);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(moved)) {
            assertNull(service.findFirstActiveByTrigger(List.of("STAGE_OFFER")));
            assertSame(moved, service.findFirstActiveByTrigger(List.of("POOLED")));
        }
    }

    @Test
    void inactiveRows_areSkippedOnBothRungs() {
        // Switching a letter off is how TA silences a moment; it must silence
        // it whether the letter was assigned or is sitting on its own key.
        RecruitmentEmailTemplate offAssigned = template("SOME_LETTER", "STAGE_OFFER", false);
        RecruitmentEmailTemplate offLegacy = template("ACKNOWLEDGEMENT", null, false);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(offAssigned, offLegacy)) {
            assertNull(service.findFirstActiveByTrigger(List.of("STAGE_OFFER")));
            assertNull(service.findFirstActiveByTrigger(List.of("ACKNOWLEDGEMENT")));
        }
    }

    @Test
    void chainOrderIsRespected_mostSpecificFirst() {
        // The reactor hands a chain, most specific first. The specific rung
        // must win while it is sendable, and the generic rung must take over
        // the moment it is not — the pre-chain behaviour, byte for byte.
        RecruitmentEmailTemplate specific = template("REJECTION_EXPERIENCE_LEVEL_SCREENING", null, true);
        RecruitmentEmailTemplate generic = template("REJECTION_SCREENING", null, true);
        List<String> chain = List.of("REJECTION_EXPERIENCE_LEVEL_SCREENING",
                "REJECTION_EXPERIENCE_LEVEL", "REJECTION_SCREENING");

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(specific, generic)) {
            assertSame(specific, service.findFirstActiveByTrigger(chain));
        }
        try (MockedStatic<PanacheEntityBase> panache = panacheOver(generic)) {
            assertSame(generic, service.findFirstActiveByTrigger(chain));
        }
    }

    @Test
    void anEmptyChain_answersNothing_withoutTouchingTheDatabase() {
        assertNull(service.findFirstActiveByTrigger(List.of()));
        assertNull(service.findFirstActiveByTrigger(null));
    }

    @Test
    void nothingAnswering_yieldsNull_whichTheMailerReadsAsSendNothing() {
        RecruitmentEmailTemplate unrelated = template("SOME_LETTER", null, true);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(unrelated)) {
            assertNull(service.findFirstActiveByTrigger(List.of("STAGE_OFFER")));
        }
    }

    @Test
    void effectiveTrigger_isTheAssignment_thenTheOwnKey_thenNothing() {
        assertEquals("POOLED",
                RecruitmentEmailService.effectiveTrigger(template("STAGE_OFFER", "POOLED", true)));
        assertEquals("STAGE_OFFER",
                RecruitmentEmailService.effectiveTrigger(template("STAGE_OFFER", null, true)));
        // A manual-send letter claims nothing: its key is not a reserved
        // trigger, so no event will ever reach it.
        assertNull(RecruitmentEmailService.effectiveTrigger(template("SOME_LETTER", null, true)));
        assertNull(RecruitmentEmailService.effectiveTrigger(null));
    }

    @Test
    void thePlanPreviewsTheSameLetterTheMailerWouldSend() {
        // The communication-plan strip in the pipeline dialogs must resolve a
        // reassigned letter exactly as the reactor will. Previewing over
        // template_key alone was the original defect: after TA connected the
        // graduate-rejection letter to REJECTION_EXPERIENCE_LEVEL, the reject
        // dialog would still have said "nothing will be sent".
        RecruitmentEmailTemplate connected =
                template("NEJ_TAK_NYUDDANNEDE", "REJECTION_EXPERIENCE_LEVEL", true);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(connected)) {
            assertSame(connected,
                    service.findByTriggerIncludingInactive("REJECTION_EXPERIENCE_LEVEL"));
            assertSame(connected, service.findFirstActiveByTrigger(
                    List.of("REJECTION_EXPERIENCE_LEVEL", "REJECTION_SCREENING")));
        }
    }

    @Test
    void thePlanStillSeesASwitchedOffLetter_whereTheMailerSeesNothing() {
        // The one deliberate difference between the two lookups. "A letter
        // exists but is switched off" and "there is no letter" are different
        // outcomes on the Journey screen and in the dialogs, and only this
        // asymmetry lets them be told apart.
        RecruitmentEmailTemplate off = template("STAGE_OFFER", null, false);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(off)) {
            assertSame(off, service.findByTriggerIncludingInactive("STAGE_OFFER"));
            assertNull(service.findFirstActiveByTrigger(List.of("STAGE_OFFER")));
        }
    }

    @Test
    void thePlanLookupHonoursTheSamePrecedence_andTheSameReassignmentRule() {
        RecruitmentEmailTemplate assigned = template("POOLED_NOT_NOW", "STAGE_OFFER", true);
        RecruitmentEmailTemplate incumbent = template("STAGE_OFFER", null, true);

        try (MockedStatic<PanacheEntityBase> panache = panacheOver(incumbent, assigned)) {
            assertSame(assigned, service.findByTriggerIncludingInactive("STAGE_OFFER"));
        }

        RecruitmentEmailTemplate moved = template("STAGE_OFFER", "POOLED", true);
        try (MockedStatic<PanacheEntityBase> panache = panacheOver(moved)) {
            assertNull(service.findByTriggerIncludingInactive("STAGE_OFFER"));
        }

        assertNull(service.findByTriggerIncludingInactive(null));
        assertNull(service.findByTriggerIncludingInactive("  "));
    }

    // ------------------------------------------------------------------
    // In-memory stand-in for the four queries
    // ------------------------------------------------------------------

    private static MockedStatic<PanacheEntityBase> panacheOver(RecruitmentEmailTemplate... rows) {
        List<RecruitmentEmailTemplate> table = List.of(rows);
        return mockStatic(PanacheEntityBase.class, invocation -> {
            if (!"find".equals(invocation.getMethod().getName())) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            String query = (String) invocation.getArgument(0);
            String key = firstParameter(invocation.getArguments());
            RecruitmentEmailTemplate hit = table.stream()
                    .filter(row -> answers(query, key, row))
                    .findFirst()
                    .orElse(null);
            @SuppressWarnings("unchecked")
            PanacheQuery<RecruitmentEmailTemplate> result = mock(PanacheQuery.class);
            when(result.firstResult()).thenReturn(hit);
            return result;
        });
    }

    /** What MariaDB would answer for the four clauses — and only those four. */
    private static boolean answers(String query, String key, RecruitmentEmailTemplate row) {
        // The plan clauses come first precisely because they do NOT filter on
        // active: a letter that exists but is switched off is the outcome the
        // pipeline dialogs need to report.
        if (PLAN_ASSIGNED_QUERY.equals(query)) {
            return key.equals(row.getTriggerKey());
        }
        if (PLAN_LEGACY_QUERY.equals(query)) {
            return key.equals(row.getTemplateKey()) && row.getTriggerKey() == null;
        }
        if (!row.isActive()) {
            return false; // both resolver clauses carry "active = true"
        }
        if (ASSIGNED_QUERY.equals(query)) {
            return key.equals(row.getTriggerKey());
        }
        if (LEGACY_QUERY.equals(query)) {
            return key.equals(row.getTemplateKey()) && row.getTriggerKey() == null;
        }
        throw new AssertionError("Unexpected template query — this fake only stands in for the "
                + "two resolution clauses, so update it deliberately: " + query);
    }

    /** Mockito hands varargs expanded or packed depending on the call; take both. */
    private static String firstParameter(Object[] arguments) {
        Object last = arguments[arguments.length - 1];
        return last instanceof Object[] packed ? (String) packed[0] : (String) last;
    }

    private static RecruitmentEmailTemplate template(String templateKey, String triggerKey,
                                                     boolean active) {
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setUuid(templateKey.toLowerCase() + "-uuid");
        template.setTemplateKey(templateKey);
        template.setTriggerKey(triggerKey);
        template.setName(templateKey);
        template.setActive(active);
        return template;
    }
}
