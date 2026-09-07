package dk.trustworks.intranet.aggregates.users.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dk.trustworks.intranet.userservice.model.enums.StatusType.ACTIVE;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.MATERNITY_LEAVE;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.NON_PAY_LEAVE;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.PAID_LEAVE;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.PREBOARDING;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.TERMINATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two employment-status sets the user selectors are built from, and the
 * line between them.
 *
 * <p>They are separate because two different questions get asked of this
 * service. Finance and public statistics ask who is on the payroll;
 * {@code findEmployedUsersByDate} answers that, and a preboarder is not on it.
 * The agreement registry asks who is contractually bound;
 * {@code findEmployedOrPreboardingUsersByDate} answers that, and a preboarder
 * is — they have signed their contract, tillæg and loyalty programme, often
 * weeks before their start date.</p>
 *
 * <p>Collapsing the two is what these tests exist to stop. On 2026-09-07 the
 * agreement backfill walked only the employed statuses, so six preboarding
 * employees holding fourteen signed documents were never corpus subjects and
 * got no agreements at all. The one-line fix — adding PREBOARDING to the
 * employed list — would have fixed the registry and silently moved every
 * headcount number in finance and on the public site along with it.</p>
 */
class UserServiceStatusSelectionTest {

    @Test
    void employedMeansAtWorkOrOnLeave_andNeverPreboarding() {
        Set<String> employed = Set.of(UserService.employedStatuses());

        assertEquals(Set.of(ACTIVE.toString(), NON_PAY_LEAVE.toString(),
                        PAID_LEAVE.toString(), MATERNITY_LEAVE.toString()), employed,
                "the employed set feeds finance and public statistics — changing it moves headcount");
        assertFalse(employed.contains(PREBOARDING.toString()),
                "a preboarder has signed but has not started and is not on the payroll; if the "
                        + "agreement registry needs them, use employedOrPreboardingStatuses()");
    }

    @Test
    void preboardingSetIsTheEmployedSetPlusPreboarding() {
        Set<String> employed = Set.of(UserService.employedStatuses());
        Set<String> withPreboarding = Set.of(UserService.employedOrPreboardingStatuses());

        assertTrue(withPreboarding.containsAll(employed),
                "everyone employed can hold an agreement, so the wider set must contain the narrower");
        assertTrue(withPreboarding.contains(PREBOARDING.toString()),
                "preboarders hold signed contracts — this is the whole point of the second set");
        assertEquals(employed.size() + 1, withPreboarding.size(),
                "PREBOARDING is the only difference between the two sets");
    }

    @Test
    void neitherSetIncludesTerminated() {
        for (String[] statuses : List.of(UserService.employedStatuses(),
                                         UserService.employedOrPreboardingStatuses())) {
            assertFalse(Set.of(statuses).contains(TERMINATED.toString()),
                    "ex-employees are out of scope for both selectors; widening to them is a "
                            + "separate decision with data-retention consequences, not a side effect");
        }
    }

    /**
     * The derivation, not just the current contents: the wider set is built from
     * the narrower one at runtime, so a status added to {@code employedStatuses}
     * cannot go missing from the preboarding set.
     */
    @Test
    void theWiderSetIsDerivedSoTheTwoCannotDrift() {
        String[] employed = UserService.employedStatuses();
        String[] withPreboarding = UserService.employedOrPreboardingStatuses();

        for (int i = 0; i < employed.length; i++) {
            assertEquals(employed[i], withPreboarding[i],
                    "the wider set must keep the employed statuses in order, appending PREBOARDING — "
                            + "if these were written out twice they would drift");
        }
        assertEquals(PREBOARDING.toString(), withPreboarding[employed.length]);
    }
}
