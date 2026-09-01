package dk.trustworks.intranet.documentservice.services;

import org.junit.jupiter.api.Test;

import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_ADDRESS;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_CPR;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_CURRENT_MONTHLY_SALARY;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_EMAIL;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_FIRSTNAME;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_HIRE_DATE;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_LASTNAME;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_NAME;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_PHONE;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.FIELD_TITLE;
import static dk.trustworks.intranet.documentservice.services.PlaceholderPrefillService.effectiveUserField;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The USER-field mapping behind prefill (spec §5.1): an explicit
 * source_field always wins; the legacy keyword fallback mirrors the
 * frontend seeding heuristics exactly (specific patterns before general,
 * Danish aliases included) so a template without source_field keeps its
 * pre-prefill behavior.
 */
class PlaceholderPrefillServiceTest {

    @Test
    void explicitSourceFieldWinsOverKeywordMatching() {
        assertEquals(FIELD_CPR, effectiveUserField("EMPLOYEE_NAME", "CPR"));
        assertEquals(FIELD_EMAIL, effectiveUserField("ANYTHING", "email"));
    }

    @Test
    void specificNamePatternsBeforeGeneral() {
        assertEquals(FIELD_FIRSTNAME, effectiveUserField("EMPLOYEE_FIRSTNAME", null));
        assertEquals(FIELD_FIRSTNAME, effectiveUserField("FORNAVN", null));
        assertEquals(FIELD_LASTNAME, effectiveUserField("EMPLOYEE_LAST_NAME", null));
        assertEquals(FIELD_LASTNAME, effectiveUserField("EFTERNAVN", null));
        assertEquals(FIELD_NAME, effectiveUserField("EMPLOYEE_NAME", null));
        assertEquals(FIELD_NAME, effectiveUserField("MEDARBEJDER_NAVN", null));
    }

    @Test
    void contactAndSensitiveKeywords() {
        assertEquals(FIELD_EMAIL, effectiveUserField("EMPLOYEE_EMAIL", null));
        assertEquals(FIELD_PHONE, effectiveUserField("TELEFONNUMMER", null));
        assertEquals(FIELD_CPR, effectiveUserField("EMPLOYEE_CPR", null));
        assertEquals(FIELD_ADDRESS, effectiveUserField("HOME_ADDRESS", null));
        assertEquals(FIELD_ADDRESS, effectiveUserField("ADRESSE", null));
        assertEquals(FIELD_CURRENT_MONTHLY_SALARY, effectiveUserField("MONTHLY_SALARY", null));
        assertEquals(FIELD_TITLE, effectiveUserField("JOB_TITLE", null));
        assertEquals(FIELD_TITLE, effectiveUserField("STILLING", null));
        assertEquals(FIELD_HIRE_DATE, effectiveUserField("HIRE_DATE", null));
    }

    @Test
    void unmatchedKeysFallBackToFullName_matchingLegacyBehavior() {
        assertEquals(FIELD_NAME, effectiveUserField("SOMETHING_ELSE", null));
        assertEquals(FIELD_NAME, effectiveUserField("", null));
        assertEquals(FIELD_NAME, effectiveUserField(null, null));
    }
}
