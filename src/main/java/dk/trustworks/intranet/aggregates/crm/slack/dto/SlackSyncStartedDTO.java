package dk.trustworks.intranet.aggregates.crm.slack.dto;

/**
 * The answer to a manual run: the row to watch (spec §6.1).
 *
 * <p>The trigger is {@code 202} and not {@code 200} with a summary because a lane that
 * reads twenty-five channels and makes a model call per channel-day is minutes of work and
 * the load balancer cuts a request at sixty seconds. What the caller gets is therefore the
 * uuid of the run row it has just opened, and the runs endpoint is where the outcome
 * appears — a run that fell over is still a row, which a request that timed out would not
 * have been.
 */
public record SlackSyncStartedDTO(String runUuid) {
}
