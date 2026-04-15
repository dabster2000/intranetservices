package dk.trustworks.intranet.dao.crm.model.enums;

/**
 * Client typing used to separate end-customers (CLIENT) from intermediary
 * billing entities (PARTNER). PARTNERs are filtered out of all non-billing
 * contexts (timesheets, sales leads, contract clientuuid, staffing).
 *
 * SPEC-INV-001 §3.1.
 */
public enum ClientType {
    CLIENT,
    PARTNER
}
