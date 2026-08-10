package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Category of an employee document in the S3-only store (spec §6.2 / D3:
 * flat list + category field, no folder tree). {@code SICKNESS} and
 * {@code TERMINATION} replace the SharePoint "Sygdom" / "Opsigelse"
 * subfolders; {@code OTHER} is the default for uncategorized and
 * self-uploaded documents.
 */
public enum EmployeeDocumentCategory {
    CONTRACT,
    ADDENDUM,
    SALARY,
    IDENTITY,
    DECLARATION,
    VACATION,
    SICKNESS,
    TERMINATION,
    OTHER;

    /**
     * Map a document template's {@link TemplateCategory} to the archival
     * category (spec §6.5.1): EMPLOYMENT/CONSULTANT→CONTRACT,
     * AMENDMENT→ADDENDUM, NDA→DECLARATION, VACATION→VACATION;
     * template-less (null) → OTHER.
     */
    public static EmployeeDocumentCategory fromTemplateCategory(TemplateCategory templateCategory) {
        if (templateCategory == null) return OTHER;
        return switch (templateCategory) {
            case EMPLOYMENT, CONSULTANT -> CONTRACT;
            case AMENDMENT -> ADDENDUM;
            case NDA -> DECLARATION;
            case VACATION -> VACATION;
            case SALARY -> SALARY;
        };
    }
}
