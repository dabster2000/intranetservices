package dk.trustworks.intranet.security.apiclient.model;

/**
 * Classification of events written to the api_client_audit_log table.
 * Each event type corresponds to a specific client lifecycle or token action.
 */
public enum AuditEventType {
    CLIENT_CREATED,
    CLIENT_UPDATED,
    CLIENT_DISABLED,
    CLIENT_ENABLED,
    CLIENT_DELETED,
    SECRET_ROTATED,
    TOKEN_ISSUED,
    TOKEN_DENIED,
    TOKEN_REVOKED
}
