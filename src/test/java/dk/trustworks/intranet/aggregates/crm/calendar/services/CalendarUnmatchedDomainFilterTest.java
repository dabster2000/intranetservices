package dk.trustworks.intranet.aggregates.crm.calendar.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which unattributed meeting domains are worth offering as a company (spec §2.5).
 *
 * <p>This is the one rule standing between "companies colleagues keep meeting" and a panel
 * full of gmail.com, every conference booking system and every recruitment agency's mail
 * relay. A panel that suggests hotmail.com twice is a panel people stop reading, and the
 * whole point of cut 2 is that a company several people keep meeting floats up by itself.
 *
 * <p>Fast tier: pure and static, no Quarkus and no database.
 */
class CalendarUnmatchedDomainFilterTest {

    @Test
    void aRealCompanyDomainIsSuggestable() {
        assertTrue(CalendarUnmatchedDomainFilter.isSuggestable("dsb.dk"));
        assertTrue(CalendarUnmatchedDomainFilter.isSuggestable("novonordisk.com"));
        assertTrue(CalendarUnmatchedDomainFilter.isSuggestable("kk.dk"));
    }

    @Test
    void freemailIdentifiesNobodyAndIsNeverSuggested() {
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("gmail.com"));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("hotmail.dk"));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("outlook.com"));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("icloud.com"));
    }

    /**
     * Our own tenant, in every spelling it turns up in. Suggesting "add trustworks.dk as a
     * company" would be absurd on its own; suggesting a SUBDOMAIN of it would be absurd and
     * hard to spot, because the panel would name something that looks like a real company.
     */
    @Test
    void ourOwnDomainIsNeverSuggested() {
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("trustworks.dk"));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("mail.trustworks.dk"));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("trustworks.onmicrosoft.com"));
    }

    @Test
    void schedulingToolsAddThemselvesAsAttendeesAndAreNotCompanies() {
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("resource.calendar.google.com"));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("teams.microsoft.com"));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("calendly.com"));
    }

    @Test
    void junkIsRejectedRatherThanStored() {
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable(null));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable(""));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("   "));
        // No dot is not a domain, however much it looks like a word.
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("localhost"));
        // Longer than the column.
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("a".repeat(200) + ".dk"));
    }

    @Test
    void caseAndSurroundingSpaceDoNotDecideTheAnswer() {
        assertTrue(CalendarUnmatchedDomainFilter.isSuggestable("  DSB.DK  "));
        assertFalse(CalendarUnmatchedDomainFilter.isSuggestable("  GMAIL.COM  "));
    }
}
