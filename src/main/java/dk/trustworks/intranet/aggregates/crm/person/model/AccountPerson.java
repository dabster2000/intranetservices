package dk.trustworks.intranet.aggregates.crm.person.model;

import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonKind;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonSource;
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

import java.time.LocalDateTime;
import java.util.List;

/**
 * One person we know at one account — the row a claim and a star point at (spec §3.1, V604).
 *
 * <h2>Why this is a table at all</h2>
 * Until this cut, a person at a client <i>was</i> their display name, re-derived on every
 * request. That works right up until somebody wants to point at one. "I know her, we worked
 * together at KMD" and "she matters on this account" both need something to attach to, and a
 * display name is not it: the same human arrives as {@code "Sara Louise Vest (XSVES)"} from
 * one mailbox, {@code "Sara Vest"} from another and a TrustLink {@code person_id} from a
 * third, and tomorrow a fourth mailbox spells her a fourth way. A name that moves cannot
 * carry a claim.
 *
 * <h2>Derived, materialised, and never typed</h2>
 * The registry rebuild writes source-derived identity fields. Calendar candidate review may
 * additionally create a person from an exact observed email and explicitly star that person.
 * Such a REVIEW identity does not create calendar evidence or a relationship claim.
 *
 * <h2>The rebuild never deletes a row</h2>
 * A person whose every source has gone quiet keeps their row: a claim, a star, or a
 * misclassification somebody is about to correct all have to survive an alias being switched
 * off or a mailbox losing consent. {@link #lastSeenAt} stopping is the signal, not a
 * deletion. A row that turns out to be the same human as another one is RETIRED rather than
 * removed — {@link #RETIRED_SOURCES} — for the same reason. Rows leave only through
 * {@code CrmRetentionPurgeJob} (V608), which ships disarmed.
 *
 * <h2>UNIQUE (client_uuid, name_key) is the merge rule's backbone</h2>
 * {@link #nameKey} is {@code PersonNames.key(name)} — lower-cased first token, a pipe,
 * lower-cased last token. The unique index is what makes "one person per name key per client"
 * a property of the database rather than a property of whichever rebuild ran last, and it is
 * what the upsert consults to decide which of two candidate rows survives a merge.
 *
 * <h2>Third-party personal data</h2>
 * Name, the client's shorthand for them, job title and LinkedIn URL of a person who never
 * consented to being here. Log the uuid, never the name. Purpose: commercial relationship
 * management; retention 24 months after the account's last activity.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_person")
public class AccountPerson extends PanacheEntityBase {

    /**
     * The {@link #sources} of a row that merged into another one: no feed backs it any more.
     *
     * <p>This is how a merge is recorded without a deletion. When a rebuild finds that two
     * rows are one human it keeps one of them and empties the other's sources; the emptied row
     * stays, because a claim ({@code account_relation_claim}) or a plan stakeholder
     * ({@code client_plan_stakeholder}) may already point at its uuid and spec §3.1 says those
     * survive. <b>Every read that lists the people on an account must exclude these rows</b>
     * ({@code and p.sources <> ''}) — a retired row still holds its old name key, and drawn it
     * is exactly the duplicate person with no edges that defect D8 is.
     *
     * <p>Empty is unambiguous: a live person is always backed by at least one source, because
     * a person only exists at all because some feed saw them.
     */
    public static final String RETIRED_SOURCES = "";

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /** The account. No foreign key onto {@code client}, matching V585-V603. */
    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /**
     * The name we show: TrustLink's {@code full_name} if there is one (the person's own
     * spelling), else the longest calendar name, else the signal's, else the Slack reading's.
     */
    @Column(name = "name", length = 255, nullable = false)
    private String name;

    /** {@code PersonNames.key(name)}. Half of the unique key, and the {@code NAME} identity. */
    @Column(name = "name_key", length = 190, nullable = false)
    private String nameKey;

    /**
     * The client's own shorthand when a mailbox carried one ({@code XSVES}, {@code STMJ}),
     * null when no source spelled one. Null rather than {@code ""} so a renderer can tell
     * "there is no shorthand" from "there is an empty one".
     */
    @Column(name = "initials", length = 16)
    private String initials;

    /** TrustLink {@code position} first, else a signal's {@code person_role}, else a Slack role. */
    @Column(name = "title", length = 500)
    private String title;

    /**
     * {@code CONTACT} | {@code ALUMNI} | {@code COLLEAGUE}.
     *
     * <p>The column is a MariaDB {@code ENUM} of exactly those three names, so the
     * {@code length} here is inert — Flyway owns the schema and no schema validation is
     * configured — but the {@link EnumType#STRING} mapping is not: an ordinal mapping would
     * write {@code 0} into an {@code ENUM} column and MariaDB would store the empty string
     * under {@code STRICT} off, which is how a whole account's people silently become
     * {@code CONTACT}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false)
    private AccountPersonKind kind;

    /**
     * For {@code ALUMNI} and {@code COLLEAGUE}: the {@code user} row the name resolved to.
     * Null for {@code CONTACT}, which is every person who matched no employee past or
     * present. No foreign key onto {@code user} — a user cleanup must not fail on a mirror
     * of somebody else's data.
     */
    @Column(name = "alumni_user_uuid", length = 36)
    private String alumniUserUuid;

    /**
     * From TrustLink, as TrustLink has it. There is <b>no</b> allow-list on this column and
     * never has been (V596 declares the same column with no CHECK and no trigger); the guard
     * is {@code isSafeExternalUrl} in the frontend, on every render.
     */
    @Column(name = "linkedin_url", length = 500)
    private String linkedinUrl;

    /**
     * Comma-joined {@code CALENDAR,TRUSTLINK,SIGNAL,SLACK} — which feeds currently back the
     * row. A plain string, not a MySQL {@code SET}; read and write it through
     * {@link AccountPersonSource#split(String)} and {@link AccountPersonSource#join} rather
     * than by hand, so exactly one place knows the separator.
     *
     * <p><b>Empty means retired</b> — see {@link #RETIRED_SOURCES} and {@link #isRetired()}.
     */
    @Column(name = "sources", length = 60, nullable = false)
    private String sources;

    /** Set on INSERT only; never moves. */
    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    /**
     * Stamped by every rebuild a source still backs the row on. A row whose
     * {@code last_seen_at} has stopped moving is a person the sources have gone quiet about —
     * it is <b>not</b> deleted for that.
     */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** The feeds behind this row, decoded. */
    public List<AccountPersonSource> sourceList() {
        return AccountPersonSource.split(sources);
    }

    /**
     * Has this row merged into another one? See {@link #RETIRED_SOURCES}. Null counts as
     * retired: a row with no sources at all is backed by nothing either way, and a reader that
     * threw here would take a whole account's page down over one malformed row.
     */
    public boolean isRetired() {
        return sources == null || sources.isBlank();
    }

    /**
     * The person with this uuid <b>on this client</b>, or null.
     *
     * <p>Both halves are required and that is the whole point: every write path takes the
     * client from the URL and the person from the URL, and looking a person up by uuid alone
     * would let anybody who can guess a uuid claim or unclaim a person on an account they
     * were never looking at. The client is the boundary; this method is where it is enforced.
     */
    public static AccountPerson findOnClient(String clientUuid, String personUuid) {
        if (clientUuid == null || clientUuid.isBlank() || personUuid == null || personUuid.isBlank()) {
            return null;
        }
        return find("clientUuid = ?1 and uuid = ?2", clientUuid.trim(), personUuid.trim()).firstResult();
    }

    /** Every person on one account, in no particular order. Includes {@code COLLEAGUE} rows. */
    public static List<AccountPerson> listForClient(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return List.of();
        }
        return list("clientUuid", clientUuid.trim());
    }
}
