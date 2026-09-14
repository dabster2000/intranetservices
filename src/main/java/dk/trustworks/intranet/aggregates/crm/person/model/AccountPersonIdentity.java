package dk.trustworks.intranet.aggregates.crm.person.model;

import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonIdentityKind;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One key that proves two sightings of a person are the same person, within one client
 * (spec §3.1, V604).
 *
 * <h2>Why the keys live in their own table</h2>
 * A person has as many identities as the feeds gave them and the number changes every night:
 * two mailboxes, a LinkedIn id, a name key today; a third mailbox tomorrow. Columns on
 * {@code account_person} would have to be either a fixed few — which loses the second address
 * — or a blob, which cannot be indexed and therefore cannot answer the only question that
 * matters here: <i>which person does this address belong to</i>. A row per key, UNIQUE on
 * {@code (client_uuid, kind, value)}, makes that a primary-key lookup and makes "one address,
 * one person, one account" a property the database enforces rather than one the rebuild
 * promises.
 *
 * <h2>Why the uuid is derived and not random</h2>
 * {@link #deterministicUuid(String, AccountPersonIdentityKind, String)} is SHA-1 over
 * {@code client|kind|value}, exactly the idiom {@code TrustLinkConnection} and
 * {@code AccountMeeting} already use. Two consequences, both wanted: the nightly upsert is a
 * {@code findById} rather than a query against the unique index, and re-pointing an identity
 * at a different person when two people turn out to be one is an <b>update of the same row</b>
 * rather than a delete and an insert that would race the unique key.
 *
 * <h2>client_uuid is denormalised on purpose</h2>
 * It duplicates {@code account_person.client_uuid}. The unique key is per client and the
 * merge lookup runs before any person is known, so a join to find out which client we are
 * scoped to would have to happen on every single lookup of every single rebuild.
 *
 * <h2>Third-party personal data</h2>
 * E-mail addresses and names of people at client organisations. Deleted with their person
 * through {@code ON DELETE CASCADE}, which is why the V608 purge sweeps one table and not two.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_person_identity")
public class AccountPersonIdentity extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /** The {@code account_person} this key belongs to. FK, {@code ON DELETE CASCADE}. */
    @Column(name = "person_uuid", length = 36, nullable = false)
    private String personUuid;

    /** Denormalised from the person; see the class javadoc. */
    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** {@code EMAIL} | {@code TRUSTLINK} | {@code NAME}. A MariaDB {@code ENUM} column. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false)
    private AccountPersonIdentityKind kind;

    /**
     * {@code EMAIL}: the lower-cased address. {@code TRUSTLINK}: the
     * {@code trustlink_connection.person_id}. {@code NAME}: the name key.
     *
     * <p>The field is named {@code value} because the column is. {@code VALUES} is reserved
     * in MariaDB and {@code VALUE} is not — it is the keyword in {@code INSERT … VALUE} and a
     * plain non-reserved identifier everywhere else. 320 characters is RFC 5321's maximum and
     * the length {@code account_meeting_attendee.email} already uses, so an address never
     * truncates on the way in.
     */
    @Column(name = "value", length = 320, nullable = false)
    private String value;

    /** Set on INSERT only. */
    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    /** Stamped by every rebuild the source still produced this identity on. */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /**
     * The stable id of one identity: SHA-1 over {@code client|kind|value}, first 16 bytes,
     * worn as {@code 8-4-4-4-12}.
     *
     * <p>Not an RFC-4122 UUID — no version or variant bits — just a reproducible 36
     * characters for a {@code CHAR(36)} column, the same shape
     * {@code AccountCalendarSyncService.deterministicUuid} and
     * {@code TrustLinkConnection.deterministicUuid} produce. It must stay reproducible: change
     * the input or the digest and every existing row's primary key stops matching, the upsert
     * starts inserting instead of updating, and the unique index throws on the first address
     * the registry has seen before.
     *
     * @param value the value <b>already normalised</b> the way the caller stores it — the
     *              address already lower-cased, the name key already a key. This method does
     *              not fold anything; two casings hashed here are two rows.
     */
    public static String deterministicUuid(String clientUuid, AccountPersonIdentityKind kind, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((clientUuid + "|" + kind.name() + "|" + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) {
                    hex.append('-');
                }
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** Every identity of one person. */
    public static List<AccountPersonIdentity> listForPerson(String personUuid) {
        if (personUuid == null || personUuid.isBlank()) {
            return List.of();
        }
        return list("personUuid", personUuid.trim());
    }

    /** Every identity on one account, which is what the merge lookup reads in one go. */
    public static List<AccountPersonIdentity> listForClient(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return List.of();
        }
        return list("clientUuid", clientUuid.trim());
    }
}
