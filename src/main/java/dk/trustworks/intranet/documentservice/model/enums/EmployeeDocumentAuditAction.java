package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Action recorded in {@code employee_document_audit} (spec §6.2 —
 * GDPR art. 30 access/processing trail).
 */
public enum EmployeeDocumentAuditAction {
    UPLOAD,
    DOWNLOAD,
    UPDATE,
    ARCHIVE,
    DELETE,
    ERASE_ALL,
    DSAR_EXPORT,
    RETENTION_DELETE,
    MIGRATE
}
