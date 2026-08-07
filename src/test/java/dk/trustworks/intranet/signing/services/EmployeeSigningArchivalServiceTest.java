package dk.trustworks.intranet.signing.services;

import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.signing.domain.SigningCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Category resolution for the S3 archival step (V475): the sender's
 * explicit {@code archive_category} wins; otherwise the template mapping.
 * The template-mapping leg itself needs a live {@code DocumentTemplateEntity}
 * lookup and is covered by the template-less (null → OTHER) case here; the
 * per-TemplateCategory mapping is covered by
 * {@code EmployeeDocumentCategory.fromTemplateCategory}'s own contract.
 */
class EmployeeSigningArchivalServiceTest {

    private static SigningCase caseWith(String archiveCategory, String templateUuid) {
        SigningCase signingCase = new SigningCase();
        signingCase.setCaseKey("case-1");
        signingCase.setArchiveCategory(archiveCategory);
        signingCase.setTemplateUuid(templateUuid);
        return signingCase;
    }

    @Test
    void explicitCategoryWins() {
        assertEquals(EmployeeDocumentCategory.SALARY,
                EmployeeSigningArchivalService.resolveCategory(caseWith("SALARY", null)));
    }

    @Test
    void everyEnumValueIsAccepted() {
        for (EmployeeDocumentCategory category : EmployeeDocumentCategory.values()) {
            assertEquals(category,
                    EmployeeSigningArchivalService.resolveCategory(caseWith(category.name(), null)));
        }
    }

    @Test
    void nullOrBlankFallsBackToTemplateMapping_templateLessIsOther() {
        assertEquals(EmployeeDocumentCategory.OTHER,
                EmployeeSigningArchivalService.resolveCategory(caseWith(null, null)));
        assertEquals(EmployeeDocumentCategory.OTHER,
                EmployeeSigningArchivalService.resolveCategory(caseWith("  ", null)));
    }

    @Test
    void unknownStoredValueDegradesToTemplateMapping() {
        assertEquals(EmployeeDocumentCategory.OTHER,
                EmployeeSigningArchivalService.resolveCategory(caseWith("NOT_A_CATEGORY", null)));
    }
}
