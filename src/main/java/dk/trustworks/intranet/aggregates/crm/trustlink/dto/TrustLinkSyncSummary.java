package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import java.util.List;

/**
 * What one run of the TrustLink sync did. Logged as the single INFO line at the end of a
 * run, mirrored into {@code trustlink_sync_state}, and returned by the manual trigger so
 * an operator can see whether a run found anything without going to CloudWatch.
 *
 * <p>Read it as an assertion about a job that is designed never to delete. Rows only ever
 * accumulate and {@code last_seen_at} records staleness, so "upserted" counts touches, not
 * growth: a steady-state night re-stamps roughly the same numbers it did the night before.
 * The number to watch is {@code failures} — a batch TrustLink refused does not abort the
 * run, it is counted here and the loop continues, because one bad batch must not cost the
 * firm its whole relationship graph.
 *
 * <p>{@code unmatchedTrustworkers} is the honest cost of the name ladder. It is expected to
 * be small and is expected to stay flat; a jump means either new colleagues TrustLink has
 * not learned about or a change in how names are spelled, and the fix is a row in
 * {@code trustlink_trustworker_map}, not a looser heuristic.
 *
 * <p><b>Why the names travel with the count.</b> A count alone is an alarm nobody can act
 * on: there is exactly one remedy for an unmatched name — a {@code trustlink_trustworker_map}
 * row keyed BY that name — and "7" does not tell you which seven to write. So the names come
 * too. They are Trustworks colleagues out of our own staff directory, not the external people
 * the rest of this feature deliberately never names, which is why this is the one list in
 * TrustLink that is safe to carry and to log.
 *
 * <p>The five middle counters are exactly the five columns of {@code trustlink_sync_state},
 * in the same order, so a run's log line and the row it wrote say the same thing.
 *
 * @param status                    what kind of run this was; see {@link Status}, and read
 *                                  it BEFORE the counters, because zeros mean different
 *                                  things under each one
 * @param clients                   clients considered for a TrustLink lookup
 * @param aliasesSeeded             AUTO alias rows added this run; steady state is 0, because
 *                                  a client that already has aliases is skipped and makes no
 *                                  typeahead call at all
 * @param companiesQueried          distinct TrustLink company names sent to the search
 * @param connectionsUpserted       {@code trustlink_connection} rows inserted or re-stamped
 * @param edgesUpserted             {@code trustlink_connection_trustworker} rows inserted or
 *                                  re-stamped
 * @param unmatchedTrustworkers     distinct TrustLink names that resolved to no Intra user;
 *                                  the edges are still written and still shown, under the raw
 *                                  name. This is the TRUE count and is never capped
 * @param unmatchedTrustworkerNames up to {@link #MAX_REPORTED_UNMATCHED_NAMES} of those names,
 *                                  sorted, so the count can be acted on; shorter than the
 *                                  count when a run goes pathological
 * @param failures                  batches that failed; the run continued past each of them
 */
public record TrustLinkSyncSummary(
        Status status,
        int clients,
        int aliasesSeeded,
        int companiesQueried,
        int connectionsUpserted,
        int edgesUpserted,
        int unmatchedTrustworkers,
        List<String> unmatchedTrustworkerNames,
        int failures) {

    /**
     * How many unmatched names are worth carrying. Twenty-five is far past the handful this
     * is expected to be and far short of the 61-name directory, so the cap only ever bites
     * when something has gone properly wrong — a rung that stopped working takes the whole
     * directory unmatched at once, and that case must not bloat the bookkeeping row or the
     * response on its way to being diagnosed. {@code unmatchedTrustworkers} still reports the
     * real number, so a truncated list can never read as a smaller problem than it is.
     */
    public static final int MAX_REPORTED_UNMATCHED_NAMES = 25;

    /**
     * Why a run produced the numbers it did.
     *
     * <p>This exists because {@link #disabled()} and {@link #skipped()} are, numerically,
     * indistinguishable from a healthy run that found nothing — all three are zeros and all
     * three answer HTTP 200. An operator who presses Sync with the feature flag off would
     * read "0 connections" as "TrustLink knows nobody at this client" and go and rewrite a
     * mapping that was never consulted. The status is what makes that impossible.
     */
    public enum Status {

        /** The pass actually queried TrustLink. Only here do the counters describe the world. */
        RAN,

        /**
         * {@code dk.trustworks.crm.trustlink.enabled} is false, so nothing was read and
         * nothing was written. A configuration state, not an error — the feature ships dark
         * and is turned on per environment.
         */
        DISABLED,

        /**
         * Another pass held the guard, so this one stood down. Only the nightly job ever sees
         * this: the manual trigger answers 409 instead, because an operator who has just
         * fixed a mapping must not be handed a summary at all when nothing was looked at.
         */
        ALREADY_RUNNING
    }

    /**
     * Defends the two invariants a caller cannot be trusted with: the name list is never null
     * (an absent list is an empty one, so nothing downstream has to null-check a diagnostic),
     * and it is capped and immutable. The cap is applied HERE rather than at each call site
     * so that the log line, the persisted row and the HTTP response cannot disagree about how
     * much of the list they are looking at.
     */
    public TrustLinkSyncSummary {
        status = status == null ? Status.RAN : status;
        unmatchedTrustworkerNames = unmatchedTrustworkerNames == null
                ? List.of()
                : unmatchedTrustworkerNames.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .limit(MAX_REPORTED_UNMATCHED_NAMES)
                        .toList();
    }

    /**
     * A pass that ran. The only factory that takes counters, because the other two states are
     * zeros by definition and inventing a number for them would be a lie with a shape.
     */
    public static TrustLinkSyncSummary ran(int clients,
                                           int aliasesSeeded,
                                           int companiesQueried,
                                           int connectionsUpserted,
                                           int edgesUpserted,
                                           int unmatchedTrustworkers,
                                           List<String> unmatchedTrustworkerNames,
                                           int failures) {
        return new TrustLinkSyncSummary(Status.RAN, clients, aliasesSeeded, companiesQueried,
                connectionsUpserted, edgesUpserted, unmatchedTrustworkers, unmatchedTrustworkerNames, failures);
    }

    /**
     * The result of a run that did not happen because the feature flag is off. An empty
     * summary rather than an exception: a disabled integration is a configuration state,
     * not an error, and the scheduled job must not page anybody for it. The {@code DISABLED}
     * status is the whole difference between this and a run that found nothing.
     */
    public static TrustLinkSyncSummary disabled() {
        return new TrustLinkSyncSummary(Status.DISABLED, 0, 0, 0, 0, 0, 0, List.of(), 0);
    }

    /**
     * The result of a nightly pass that stood down because another pass was already
     * running in this instance. Zeros for the same reason {@link #disabled()} is zeros:
     * nothing was read and nothing was written, so claiming any other number would be
     * inventing one. {@code ALREADY_RUNNING} — not the WARN line beside it — is now what
     * tells the two apart; the manual trigger still does not use this at all and answers 409
     * instead, because an operator who has just fixed a mapping would read a summary of
     * zeros as "it found nothing".
     */
    public static TrustLinkSyncSummary skipped() {
        return new TrustLinkSyncSummary(Status.ALREADY_RUNNING, 0, 0, 0, 0, 0, 0, List.of(), 0);
    }
}
