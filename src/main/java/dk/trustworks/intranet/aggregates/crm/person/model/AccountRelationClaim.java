package dk.trustworks.intranet.aggregates.crm.person.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * "I know her." One colleague's own statement about one person at one account
 * (spec §3.5, V605).
 *
 * <h2>Why this is worth a table</h2>
 * Everything else on the relationships tab is inferred. A calendar meeting says two people
 * were in a room; a LinkedIn connection says somebody once pressed connect. Neither says the
 * thing the tab exists to answer — who can actually pick up the phone. That is knowledge only
 * the colleague has, and until this cut there was nowhere to put it, so it stayed in people's
 * heads and left with them.
 *
 * <p>A claim is also the only edge in the model with a human behind it, which is why it
 * outranks everything: {@link #strength} ≥ 3 is tier 1 alongside a meeting in the last 90
 * days (spec §3.4).
 *
 * <h2>user_uuid is never a body field</h2>
 * It is always the {@code X-Requested-By} user, resolved server-side. A claim is a statement
 * of the form "I know this person"; a body field would let the caller file that statement in
 * somebody else's name, and since only the claimant may edit or delete their own claim, it
 * would also let them plant one nobody but an admin could remove.
 *
 * <h2>Why the unique key is (person_uuid, user_uuid)</h2>
 * A person already belongs to exactly one client — {@code account_person.client_uuid} is half
 * of its own unique key. Adding {@link #clientUuid} to this one would let the same colleague
 * hold two claims on one person by disagreeing about which client the person is at.
 * {@code client_uuid} is still stored, and indexed, because every read is "all claims on this
 * account" and the alternative is a join through {@code account_person} on every page load.
 *
 * <h2>No history, on purpose</h2>
 * A second PUT updates the row. "How well do you know her today" has one answer.
 *
 * <h2>Third-party-adjacent personal data</h2>
 * The name lives on the {@code account_person} row this one points at, but {@link #how} is
 * free text a colleague typed, and free text names people ("worked together at KMD 2019-21").
 * There is no structured column to redact, so the erasure primitive is deleting the row — the
 * cascade from {@code account_person} is how the V608 purge gets both.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_relation_claim")
public class AccountRelationClaim extends PanacheEntityBase {

    /** 1 met once. The floor; anything below it is not a claim. */
    public static final int MIN_STRENGTH = 1;

    /** 4 trusted. The ceiling. Backed by {@code chk_account_relation_claim_strength} in V605. */
    public static final int MAX_STRENGTH = 4;

    /** Matches {@code VARCHAR(255)} in V605. Checked in Java before the database ever sees it. */
    public static final int MAX_HOW_CHARS = 255;

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /** Denormalised from the person; see the class javadoc. */
    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** The {@code account_person}. FK, {@code ON DELETE CASCADE}. */
    @Column(name = "person_uuid", length = 36, nullable = false)
    private String personUuid;

    /** Always the {@code X-Requested-By} user; never a body field. */
    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    /** 1 met once · 2 know each other · 3 good working relationship · 4 trusted. */
    @Column(name = "strength", nullable = false)
    private int strength;

    /** Free text, optional: how they know them. Null = the claimant did not say. */
    @Column(name = "how", length = MAX_HOW_CHARS)
    private String how;

    /** Set on INSERT only; the date warmth ranks a claim by (spec §3.4). */
    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt;

    /** Moved by every PUT that changes the claim. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * This colleague's claim on this person, or null.
     *
     * <p>The pair is the unique key, so this is the whole of "does the caller already have a
     * claim here" — and, in {@code delete}, the whole of "is the caller the claimant". There
     * is deliberately no finder that takes a person and returns somebody else's claim to be
     * edited.
     */
    public static AccountRelationClaim findByPersonAndUser(String personUuid, String userUuid) {
        if (personUuid == null || personUuid.isBlank() || userUuid == null || userUuid.isBlank()) {
            return null;
        }
        return find("personUuid = ?1 and userUuid = ?2", personUuid.trim(), userUuid.trim()).firstResult();
    }

    /** Every claim on one person, whoever made it. The read side ranks these as edges. */
    public static List<AccountRelationClaim> listForPerson(String personUuid) {
        if (personUuid == null || personUuid.isBlank()) {
            return List.of();
        }
        return list("personUuid", personUuid.trim());
    }

    /** Every claim on one account — one query per page load rather than one per person. */
    public static List<AccountRelationClaim> listForClient(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return List.of();
        }
        return list("clientUuid", clientUuid.trim());
    }
}
