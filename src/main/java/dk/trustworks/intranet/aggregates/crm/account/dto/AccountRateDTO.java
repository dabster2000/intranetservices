package dk.trustworks.intranet.aggregates.crm.account.dto;

/**
 * The rate KPI (CRM spec §4.3): what we charge on this account, against what it costs.
 *
 * <p><b>{@code breakEven} and {@code target} are null for most callers, on purpose.</b>
 * Break-even is derived from salaries, and the account page is open to every employee.
 * The filtering happens in {@code AccountRateService} — server-side — so the figure never
 * crosses the wire to someone who may not see it. Hiding it in the UI would leave it in
 * the JSON.
 *
 * <p>{@code weighted} is null when nothing is running: an average of no contracts is not
 * zero, it is nothing, and a zero would render as a catastrophic rate.
 */
public record AccountRateDTO(Double weighted, Double breakEven, Double target) {
}
