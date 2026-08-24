package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.vacationservice.engine.EmploymentCompanyResolver.StatusFact;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no DB) for "which company employed this person on date X" —
 * including the cases where the honest answer is "cannot say".
 */
class EmploymentCompanyResolverTest {

    private static final String AS = "company-as";
    private static final String TECH = "company-tech";
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

    @Test
    void theLatestStatusAtOrBeforeTheDateWins() {
        List<StatusFact> timeline = List.of(
                new StatusFact(LocalDate.of(2020, 1, 1), AS),
                new StatusFact(LocalDate.of(2025, 3, 1), TECH));

        assertEquals(Optional.of(TECH), EmploymentCompanyResolver.companyAt(timeline, AS_OF));
    }

    /**
     * The comparison keys on statusdate — the effective date — so a transfer
     * registered late but backdated to the day it happened resolves correctly.
     */
    @Test
    void aStatusEffectiveExactlyOnTheDateCounts() {
        List<StatusFact> timeline = List.of(
                new StatusFact(LocalDate.of(2020, 1, 1), AS),
                new StatusFact(AS_OF, TECH));

        assertEquals(Optional.of(TECH), EmploymentCompanyResolver.companyAt(timeline, AS_OF));
    }

    /** A transfer already registered for next month must not change today's answer. */
    @Test
    void aFutureTransferIsIgnored() {
        List<StatusFact> timeline = List.of(
                new StatusFact(LocalDate.of(2020, 1, 1), AS),
                new StatusFact(LocalDate.of(2026, 8, 15), TECH));

        assertEquals(Optional.of(AS), EmploymentCompanyResolver.companyAt(timeline, AS_OF));
    }

    /**
     * The trap, stated as a requirement. An empty timeline is the shape a
     * shallow-loaded User presents, and the answer must be "cannot say" — never
     * a fabricated status, and never a silent fall-through to some default
     * company.
     */
    @Test
    void anEmptyTimelineCannotSay() {
        assertTrue(EmploymentCompanyResolver.companyAt(List.of(), AS_OF).isEmpty());
        assertTrue(EmploymentCompanyResolver.companyAt(null, AS_OF).isEmpty());
    }

    /** Everything the person has starts after the date — e.g. a preboarder. */
    @Test
    void aTimelineThatStartsAfterTheDateCannotSay() {
        List<StatusFact> timeline = List.of(new StatusFact(LocalDate.of(2026, 9, 1), TECH));

        assertTrue(EmploymentCompanyResolver.companyAt(timeline, AS_OF).isEmpty());
    }

    /** Legacy userstatus rows exist without a company. Unknown, not "same". */
    @Test
    void aStatusWithoutACompanyCannotSay() {
        List<StatusFact> timeline = List.of(
                new StatusFact(LocalDate.of(2020, 1, 1), AS),
                new StatusFact(LocalDate.of(2025, 3, 1), null));

        assertTrue(EmploymentCompanyResolver.companyAt(timeline, AS_OF).isEmpty());
        assertTrue(EmploymentCompanyResolver.companyAt(
                List.of(new StatusFact(LocalDate.of(2025, 3, 1), "   ")), AS_OF).isEmpty());
    }
}
