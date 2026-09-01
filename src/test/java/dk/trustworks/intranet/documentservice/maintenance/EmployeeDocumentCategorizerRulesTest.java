package dk.trustworks.intranet.documentservice.maintenance;

import dk.trustworks.intranet.documentservice.maintenance.EmployeeDocumentCategorizerRules.RuleResult;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every row of the §9.5 deterministic fallback table, ordering included
 * (runbook 2a-5 verify).
 */
class EmployeeDocumentCategorizerRulesTest {

    private static RuleResult categorize(String path, String filename) {
        return EmployeeDocumentCategorizerRules.categorize(path, filename);
    }

    // ── Row by row ─────────────────────────────────────────────────────────

    @Test
    void onboardingPathIsIdentity() {
        assertEquals(EmployeeDocumentCategory.IDENTITY, categorize("Onboarding", "scan.pdf").category());
    }

    @Test
    void identityFilenameSignals() {
        assertEquals(EmployeeDocumentCategory.IDENTITY, categorize("", "pas kopi.pdf").category());
        assertEquals(EmployeeDocumentCategory.IDENTITY, categorize("", "passport.jpg").category());
        assertEquals(EmployeeDocumentCategory.IDENTITY, categorize("", "sundhedskort.png").category());
        assertEquals(EmployeeDocumentCategory.IDENTITY, categorize("", "health card.pdf").category());
        assertEquals(EmployeeDocumentCategory.IDENTITY, categorize("", "ID_2020.pdf").category());
        assertEquals(EmployeeDocumentCategory.IDENTITY, categorize("", "kørekort.jpg").category());
        assertEquals(EmployeeDocumentCategory.IDENTITY, categorize("", "drivers license.pdf").category());
    }

    @Test
    void sygdomPathIsSickness() {
        assertEquals(EmployeeDocumentCategory.SICKNESS, categorize("Sygdom", "notat.pdf").category());
        assertEquals(EmployeeDocumentCategory.SICKNESS, categorize("2021/Sygdom/attester", "x.pdf").category());
    }

    @Test
    void opsigelseIsTermination() {
        assertEquals(EmployeeDocumentCategory.TERMINATION, categorize("Opsigelse", "brev.pdf").category());
        assertEquals(EmployeeDocumentCategory.TERMINATION, categorize("", "fratrædelsesaftale.pdf").category());
        assertEquals(EmployeeDocumentCategory.TERMINATION, categorize("", "opsigelse 2020.pdf").category());
        assertEquals(EmployeeDocumentCategory.TERMINATION, categorize("", "termination letter.pdf").category());
    }

    @Test
    void arkivSetsArchivedAndKeepsRuleCategory() {
        RuleResult result = categorize("Arkiv", "Lønregulering 2021.pdf");
        assertEquals(EmployeeDocumentCategory.SALARY, result.category());
        assertTrue(result.archived());

        RuleResult plain = categorize("", "Lønregulering 2021.pdf");
        assertFalse(plain.archived());
    }

    @Test
    void salarySignals() {
        assertEquals(EmployeeDocumentCategory.SALARY, categorize("", "lønregulering 2021.pdf").category());
        assertEquals(EmployeeDocumentCategory.SALARY, categorize("", "Loenregulering.pdf").category());
        assertEquals(EmployeeDocumentCategory.SALARY, categorize("", "salary adjustment.pdf").category());
        assertEquals(EmployeeDocumentCategory.SALARY, categorize("", "lønseddel marts.pdf").category());
    }

    @Test
    void addendumSignals() {
        assertEquals(EmployeeDocumentCategory.ADDENDUM, categorize("", "Tillæg til kontrakt.pdf").category());
        assertEquals(EmployeeDocumentCategory.ADDENDUM, categorize("", "addendum 2.pdf").category());
        assertEquals(EmployeeDocumentCategory.ADDENDUM, categorize("", "kundeklausul.pdf").category());
    }

    @Test
    void declarationSignals() {
        assertEquals(EmployeeDocumentCategory.DECLARATION, categorize("", "tro og love.pdf").category());
        assertEquals(EmployeeDocumentCategory.DECLARATION, categorize("", "tro_og_love_2022.pdf").category());
        assertEquals(EmployeeDocumentCategory.DECLARATION, categorize("", "loyalitetsprogram.pdf").category());
        assertEquals(EmployeeDocumentCategory.DECLARATION, categorize("", "Din del af Trustworks.pdf").category());
    }

    @Test
    void vacationSignals() {
        assertEquals(EmployeeDocumentCategory.VACATION, categorize("", "ferieaftale.pdf").category());
        assertEquals(EmployeeDocumentCategory.VACATION, categorize("", "vacation plan.pdf").category());
    }

    @Test
    void contractSignals() {
        assertEquals(EmployeeDocumentCategory.CONTRACT, categorize("", "Ansættelseskontrakt.pdf").category());
        assertEquals(EmployeeDocumentCategory.CONTRACT, categorize("", "ansaettelseskontrakt.pdf").category());
        assertEquals(EmployeeDocumentCategory.CONTRACT, categorize("", "samarbejdsaftale.pdf").category());
        assertEquals(EmployeeDocumentCategory.CONTRACT, categorize("", "contract signed.pdf").category());
    }

    @Test
    void emlAndMsgAreOtherWithSubjectishLabel() {
        RuleResult eml = categorize("", "Re_ ny kontraktdato.eml");
        // ".eml" hits the CONTRACT keyword? No — ordering: "kontraktdato"
        // contains "kontrakt", so CONTRACT wins BEFORE the .eml row. That is
        // the table's documented top-to-bottom behavior.
        assertEquals(EmployeeDocumentCategory.CONTRACT, eml.category());

        RuleResult plainMail = categorize("", "FW_ mødereferat.msg");
        assertEquals(EmployeeDocumentCategory.OTHER, plainMail.category());
        assertEquals("FW  mødereferat", plainMail.label());
    }

    @Test
    void fallbackIsOther() {
        RuleResult result = categorize("", "scan0001.pdf");
        assertEquals(EmployeeDocumentCategory.OTHER, result.category());
        assertFalse(result.archived());
    }

    // ── Ordering (the spec calls these out explicitly) ─────────────────────

    @Test
    void declarationWinsOverContract() {
        // "Tro og love-erklæring ifm. aftale" contains both signals — the
        // DECLARATION row is checked before CONTRACT.
        assertEquals(EmployeeDocumentCategory.DECLARATION,
                categorize("", "Tro og love-erklæring ifm. aftale.pdf").category());
    }

    @Test
    void sicknessPathWinsOverFilenameRules() {
        assertEquals(EmployeeDocumentCategory.SICKNESS,
                categorize("Sygdom", "kontrakt om fravær.pdf").category());
    }

    @Test
    void identityWinsOverEverything() {
        assertEquals(EmployeeDocumentCategory.IDENTITY,
                categorize("Onboarding", "kontrakt.pdf").category());
    }

    @Test
    void matchingIsCaseAndDiacriticInsensitive() {
        assertEquals(EmployeeDocumentCategory.SICKNESS, categorize("SYGDOM", "x.pdf").category());
        assertEquals(EmployeeDocumentCategory.SALARY, categorize("", "LØNREGULERING.PDF").category());
        assertEquals(EmployeeDocumentCategory.TERMINATION, categorize("", "FRATRAEDELSE.pdf").category());
    }
}
