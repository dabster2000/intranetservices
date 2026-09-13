package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * "Did last night work?", answered over HTTP.
 *
 * <p><b>Why this exists.</b> The nightly job writes {@code trustlink_sync_state} at 02:40 and
 * until now nothing read it back. The feed is invisible when it works and equally invisible
 * when it stops — connections simply stop getting fresher, and a stale relationship graph
 * looks exactly like a quiet one — so the only way to tell the difference was a SQL client
 * pointed at a production database that is read-only by default and that most of the people
 * who care cannot reach at all. A write-only health table is not health.
 *
 * <p><b>{@code enabled} is the field that makes the rest legible.</b> It is read live from
 * {@code TrustLinkConfig}, not from the row, and it is what separates the two ways this
 * endpoint can show nothing: the feature flag is off in this environment (expected; the
 * feature ships dark), or the job is running and getting nowhere (not expected). Without it
 * a reader sees null timestamps and zeros and cannot tell which they are looking at.
 *
 * <p><b>{@code lastRunAt} versus {@code lastSuccessAt}</b> is the second question the row
 * answers and the reason it has two timestamps. A run that failed every batch still ran. The
 * gap between them is the alarm: if {@code lastRunAt} is this morning and
 * {@code lastSuccessAt} is a fortnight ago, the job is firing nightly and achieving nothing,
 * which no single "last sync" timestamp could ever show.
 *
 * <p><b>No third-party PII.</b> {@code unmatchedTrustworkerNames} are TRUSTWORKS COLLEAGUES
 * out of our own staff directory — the names the matcher could not place. The external people
 * this feature mirrors are served only by {@code GET /accounts/{uuid}/relationships}, inside
 * the graph that gives them a reason to be on screen; none of them appears here.
 *
 * @param enabled                   whether the feature flag is on in THIS environment, read
 *                                  live rather than from the row
 * @param lastRunAt                 when a pass last started; null means no pass has ever run
 * @param lastSuccessAt             when a pass last finished with zero failed batches
 * @param companiesQueried          distinct TrustLink company names the last pass asked about
 * @param connectionsUpserted       rows the last pass inserted or re-stamped; a steady-state
 *                                  night re-stamps roughly what the night before did
 * @param edgesUpserted             trustworker edges the last pass inserted or re-stamped
 * @param unmatchedTrustworkers     how many directory names the ladder could not place — the
 *                                  true count, never capped
 * @param unmatchedTrustworkerNames up to
 *                                  {@link TrustLinkSyncSummary#MAX_REPORTED_UNMATCHED_NAMES}
 *                                  of those names, so the count can actually be acted on: the
 *                                  one fix is a {@code trustlink_trustworker_map} row keyed BY
 *                                  the name
 * @param failureCode               the {@code TrustLinkClient.Failure} code of the last
 *                                  failure, or null. A CODE and never a message or a body — an
 *                                  upstream error body can echo our client list back
 */
public record TrustLinkSyncStateDTO(
        boolean enabled,
        LocalDateTime lastRunAt,
        LocalDateTime lastSuccessAt,
        int companiesQueried,
        int connectionsUpserted,
        int edgesUpserted,
        int unmatchedTrustworkers,
        List<String> unmatchedTrustworkerNames,
        String failureCode) {

    /**
     * The answer when the singleton row is not there.
     *
     * <p>A 404 would be the wrong answer to "has the job ever run": the caller asked about the
     * state of a feature that exists, and "no row" IS its state — nothing has run yet, which is
     * exactly what a fresh environment or an un-migrated one looks like. Answering 404 would
     * put a reader on the trail of a broken endpoint instead of an idle job. {@code enabled}
     * is still filled in, because "off and never run" and "on and never run" are different
     * problems.
     */
    public static TrustLinkSyncStateDTO neverRun(boolean enabled) {
        return new TrustLinkSyncStateDTO(enabled, null, null, 0, 0, 0, 0, List.of(), null);
    }

    /** Never null: an absent list is an empty one, so no caller has to null-check a diagnostic. */
    public TrustLinkSyncStateDTO {
        unmatchedTrustworkerNames = unmatchedTrustworkerNames == null
                ? List.of()
                : List.copyOf(unmatchedTrustworkerNames);
    }
}
