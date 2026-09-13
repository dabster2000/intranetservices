package dk.trustworks.intranet.aggregates.crm.trustlink.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * One person at a client organisation that somebody at Trustworks is connected to,
 * mirrored from TrustLink (CRM spec §3.5, §3.7).
 *
 * <p>This is the third source of relationship edges, next to the calendar (MET) and
 * signals (KNOWS). It answers the question the CRM spec asks on every account page —
 * "who here already knows them" — for the far larger part of the graph that never shows
 * up in a calendar because the relationship predates the account, or was never a meeting
 * at all.
 *
 * <h2>Third-party personal data</h2>
 * These people never gave Trustworks anything: their name, job title and LinkedIn address
 * arrive because a colleague is connected to them. Purpose is commercial relationship
 * management, access is {@code accounts:read}. The table is excluded from
 * {@code sp_sync_prod_to_staging} (V596) so the names are never copied into an environment
 * with wider access — the same posture as {@code account_meeting_attendee}. <b>No purge
 * job exists</b>; the retention gap is recorded in the migration header and is not to be
 * read as an implemented control.
 *
 * <h2>One row per (client, person), not per person</h2>
 * The same TrustLink company name can be aliased by more than one Intra client, and one
 * person can sit at a company two clients both claim. Keying by {@code (client_uuid,
 * person_id)} means every account that maps that company sees the connection, and neither
 * account's count is stolen by the other. {@link #uuid} is derived deterministically from
 * the pair by {@link #deterministicUuid(String...)}, exactly as {@code account_meeting}
 * derives its id, so a nightly re-sync updates the row it wrote yesterday instead of
 * inserting a second copy of the same person.
 *
 * <h2>Upsert, never delete</h2>
 * The sync only ever writes. {@link #firstSeenAt} is set once, on insert;
 * {@link #lastSeenAt} is stamped on every run the person was returned. A person who
 * disappears from TrustLink — because a colleague left, or because the upstream export
 * hiccuped — keeps their row and goes stale rather than silently taking a relationship out
 * of the account page. Staleness is a question the reader can answer from
 * {@link #lastSeenAt}; a missing row is not.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "trustlink_connection")
public class TrustLinkConnection extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /** The Intra client whose alias list claimed {@link #companyName}. */
    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** TrustLink's own id for the person. Opaque, stable, and the half of the key that is not ours. */
    @Column(name = "person_id", length = 64, nullable = false)
    private String personId;

    @Column(name = "full_name", length = 255, nullable = false)
    private String fullName;

    /** Job title as TrustLink has it. Free text, often long, frequently null. */
    @Column(name = "position", length = 500)
    private String position;

    /**
     * TrustLink's relationship strength. Only tier 5 is ever stored — the sync asks for
     * {@code tiers:[5]} and rejects a response that contains anything else — but the column
     * is kept so a row's tier is readable without knowing what the query asked for.
     */
    @Column(name = "tier", nullable = false)
    private int tier;

    /**
     * The TrustLink company name this person was found under, verbatim. Not the client's
     * name: TrustLink fragments an organisation across several names and which one a person
     * sits under is the only way to explain a count back to a sceptical reader.
     */
    @Column(name = "company_name", length = 255, nullable = false)
    private String companyName;

    @Column(name = "company_id", length = 64)
    private String companyId;

    @Column(name = "linkedin_url", length = 500)
    private String linkedinUrl;

    /** TrustLink's own "is this a customer" flag. Mirrored, never used to decide anything here. */
    @Column(name = "is_customer", nullable = false)
    private boolean customer;

    @Column(name = "coffee_count", nullable = false)
    private int coffeeCount;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    /** Set once, on insert. Never rewritten — it is how long we have known about this person. */
    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    /** Stamped every run the person came back. The staleness clock; nothing is ever deleted. */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /**
     * A stable 36-character id derived from the parts that make the row unique.
     *
     * <p>SHA-1 over the joined parts, formatted as a uuid. The column is {@code CHAR(36)}
     * and a random uuid would make every nightly run insert a fresh copy of a person it
     * already has; deriving the id turns "insert" into "find and update" without a lookup
     * query per person. This mirrors {@code AccountCalendarSyncService.deterministicUuid}
     * — the same problem, the same answer, so the two feeds behave alike under a re-sync.
     *
     * <p>Not a cryptographic use: the input is a pair of ids we generated the query for,
     * and a collision would need a second-preimage on a value nobody outside can choose.
     */
    public static String deterministicUuid(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed present on every JVM; this branch exists only to satisfy
            // the checked exception.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
