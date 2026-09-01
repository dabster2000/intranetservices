package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HR-editable visiting address, pure half. Plain unit test: everything
 * asserted here is static, so it runs in the DB-free tier that gates
 * deploys. The stored-value states (absent / text / blanked) are exercised
 * against the real {@code app_settings} row by the settings resource.
 */
class RecruitmentVisitingAddressTest {

    @Test
    void defaultAddress_isTheVisitingAddress_notTheRegisteredOne() {
        assertEquals("Hausergade 3, 1128 K\u00f8benhavn K",
                RecruitmentVisitingAddress.defaultAddress());
        assertFalse(RecruitmentVisitingAddress.defaultAddress().contains("Pustervig"),
                "Pustervig 3 is the registered/postal address used by the expense "
                        + "geofence and the bulk-mail footer \u2014 a different concern");
    }

    /**
     * The address is merged into an HTML calendar body, into the plain
     * {@code .ics} body and into the built-in fallback text. One line is the
     * only shape that is right in all three, so a pasted multi-line address
     * is flattened rather than stored as typed.
     */
    @Test
    void sanitize_flattensToOneLine() {
        assertEquals("Hausergade 3, 1128 K\u00f8benhavn K",
                RecruitmentVisitingAddress.sanitize("  Hausergade 3,\n 1128 K\u00f8benhavn K \n"));
        assertEquals("A B", RecruitmentVisitingAddress.sanitize("A\t\tB"));
        assertEquals("", RecruitmentVisitingAddress.sanitize(null));
        assertEquals("", RecruitmentVisitingAddress.sanitize("   "));
    }

    /**
     * Control characters are invisible in every review surface but survive
     * into the calendar body, so they are removed rather than trusted.
     */
    @Test
    void sanitize_dropsControlAndFormatCharacters() {
        String nul = String.valueOf((char) 0x0000);
        String zeroWidthSpace = String.valueOf((char) 0x200b);
        String cleaned = RecruitmentVisitingAddress.sanitize(
                "Hausergade" + nul + "3, 1128" + zeroWidthSpace + "K\u00f8benhavn K");

        assertFalse(cleaned.contains(nul));
        assertFalse(cleaned.contains(zeroWidthSpace));
        assertTrue(cleaned.startsWith("Hausergade"));
        assertTrue(cleaned.endsWith("K"));
    }

    @Test
    void maxLength_matchesTheInterviewLocationColumn() {
        assertEquals(200, RecruitmentVisitingAddress.MAX_LENGTH);
    }
}
