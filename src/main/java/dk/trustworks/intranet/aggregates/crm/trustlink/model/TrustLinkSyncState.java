package dk.trustworks.intranet.aggregates.crm.trustlink.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * What the TrustLink sync last did. One row, id 1, seeded by V596.
 *
 * <h2>Why a table and not just a log line</h2>
 * The feed is invisible when it works and equally invisible when it stops: connections
 * simply stop getting fresher, and a stale relationship graph looks exactly like a quiet
 * one. This row is the answer to "is it still running, and did the last run find anything" —
 * readable from the database by anyone, without CloudWatch and without waiting for 02:40.
 *
 * <p>{@link #lastRunAt} is stamped on every run, {@link #lastSuccessAt} only on a run with
 * no failed batches. The gap between them is the alarm: a widening one means the job fires
 * and gets nowhere. {@link #failureCode} carries the {@code TrustLinkClient.Failure} code of
 * the last failure and <b>never a message or a response body</b> — an upstream error body
 * can echo back the request, which for this feed is a list of our clients' names.
 *
 * <p>Configuration, not personal data: unlike {@code trustlink_connection} this table IS
 * copied prod → staging, so a staging run starts from an honest picture of production.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "trustlink_sync_state")
public class TrustLinkSyncState extends PanacheEntityBase {

    /** Always 1. The table holds exactly one row; V596 seeds it. */
    public static final byte SINGLETON_ID = 1;

    /** Matches {@code VARCHAR(2000)} in V596. 25 names of 255 could not fit; see {@link #encodeNames}. */
    static final int MAX_NAMES_COLUMN_CHARS = 2000;

    @Id
    @Column(name = "id")
    private byte id;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** Only set when the run finished with zero failed batches. */
    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "companies_queried", nullable = false)
    private int companiesQueried;

    @Column(name = "connections_upserted", nullable = false)
    private int connectionsUpserted;

    @Column(name = "edges_upserted", nullable = false)
    private int edgesUpserted;

    /** Distinct trustworker names no rung of the ladder resolved. A rising number needs a look. */
    @Column(name = "unmatched_trustworkers", nullable = false)
    private int unmatchedTrustworkers;

    /**
     * Which names those were, newline-separated, capped by
     * {@code TrustLinkSyncSummary.MAX_REPORTED_UNMATCHED_NAMES}. Use
     * {@link #encodeNames(List)} and {@link #decodeNames(String)} rather than touching the
     * string: the encoding is this column's business and nobody else's.
     *
     * <p>The count beside it is the truth; this list may be shorter. Its whole purpose is
     * that {@code unmatched_trustworkers = 7} is an alarm nobody can act on — the one fix is
     * a {@code trustlink_trustworker_map} row keyed BY the name, so without the names the
     * number can only be watched, never resolved.
     *
     * <p>These are TRUSTWORKS COLLEAGUES out of our own staff directory, which is why they
     * may sit in a column that staging copies while {@code trustlink_connection} may not:
     * the third-party people this feature mirrors are never named outside their own tables.
     */
    @Column(name = "unmatched_trustworker_names", length = MAX_NAMES_COLUMN_CHARS)
    private String unmatchedTrustworkerNames;

    /** A {@code Failure} enum name. Never a message, never a body. */
    @Column(name = "failure_code", length = 40)
    private String failureCode;

    /**
     * Packs the names for storage.
     *
     * <p>Newline-separated, not comma-separated, for one reason: a person's name may contain
     * a comma ("Michelle R. Cantor, MBA" is the kind of thing TrustLink holds) and none
     * contains a newline, so this is the only cheap separator that round-trips. The result is
     * truncated at a name boundary rather than mid-name if it would overflow the column — a
     * half-written name is worse than a missing one, because somebody would search the
     * directory for it and conclude the colleague does not exist.
     */
    public static String encodeNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        StringBuilder packed = new StringBuilder();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String cleaned = name.replace('\n', ' ').replace('\r', ' ').trim();
            int addition = cleaned.length() + (packed.isEmpty() ? 0 : 1);
            if (packed.length() + addition > MAX_NAMES_COLUMN_CHARS) {
                break;
            }
            if (!packed.isEmpty()) {
                packed.append('\n');
            }
            packed.append(cleaned);
        }
        return packed.isEmpty() ? null : packed.toString();
    }

    /** The inverse. A null or empty column is an empty list, never a null one. */
    public static List<String> decodeNames(String packed) {
        if (packed == null || packed.isBlank()) {
            return List.of();
        }
        return Arrays.stream(packed.split("\n"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }
}
