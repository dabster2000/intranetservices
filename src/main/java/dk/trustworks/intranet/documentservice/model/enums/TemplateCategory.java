package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Template categories for document types.
 */
public enum TemplateCategory {
    EMPLOYMENT,
    NDA,
    VACATION,
    CONSULTANT,
    AMENDMENT,
    /**
     * Pay raises, bonus letters, salary adjustments. Added because the
     * template flow had no route to {@link EmployeeDocumentCategory#SALARY}
     * at all: a pay-raise template filed as ADDENDUM at best, OTHER in
     * practice. Stored in {@code document_templates.category}
     * (varchar(50)) — no migration needed.
     */
    SALARY
}
