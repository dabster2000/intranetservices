package dk.trustworks.intranet.aggregates.clientstatus.dto;

/** Billing-coverage state for one client-month cell. */
public enum ClientStatusCellState {
    NO_ACTIVITY,
    NOT_INVOICED,
    PARTIAL,
    FULL,
    OVER
}
