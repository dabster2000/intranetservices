package dk.trustworks.intranet.aggregates.crm.merge.dto;

/**
 * The one thing a person decides about a merge that the rules cannot (spec D2).
 *
 * <p>Deliberately nothing else: the winner and the loser are path parameters, the actor is
 * {@code X-Requested-By}, and every other collision is decided by
 * {@code ClientMergeRules}. A body that could also carry "delete the loser" or "skip the
 * verification" would be a body a caller could forge.
 *
 * @param accountFrom {@code WINNER} or {@code LOSER} — whose {@code client_account} (band,
 *                    GTM bubble, Slack space, next step) and whose account manager the
 *                    merged account keeps. Required when both rows have an account;
 *                    ignored otherwise.
 */
public record ClientMergeRequest(String accountFrom) {}
