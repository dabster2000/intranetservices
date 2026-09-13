package dk.trustworks.intranet.aggregates.crm.signal.dto;

/**
 * The owner's verdict on a signal (CRM spec §3.4).
 *
 * @param status   LEAD_CREATED, PARKED or NOT_RELEVANT. NEW is not a decision and is
 *                 refused — a signal cannot be un-decided by an API call.
 * @param leadUuid the lead that was created, when the status is LEAD_CREATED
 */
public record SignalDecisionRequest(String status, String leadUuid) {
}
