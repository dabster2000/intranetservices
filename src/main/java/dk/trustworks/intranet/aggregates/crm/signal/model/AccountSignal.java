package dk.trustworks.intranet.aggregates.crm.signal.model;

import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalSource;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalStatus;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalType;
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

/**
 * One line a colleague heard about a client (CRM spec §3.4) — the "Heard something?"
 * capture.
 *
 * <p>This is the one deliberate "we all sell" input: any employee types a single
 * sentence, an extractor pulls out the person, their role, how the author knows them
 * and what kind of signal it is, and the row lands against the client. Nothing else is
 * typed and nothing is chased. The read surfaces (the account plan's "People &amp; what
 * we've heard", the owner's queue, the timeline) are specified but not built in this
 * cut — this release captures and saves, nothing more.
 *
 * <p>Never a REST body — the resource takes
 * {@code dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalRequest}.
 * {@link #authorUuid}, {@link #source}, {@link #status} and {@link #createdAt} are
 * derived server-side and must never be accepted from a caller.
 *
 * <p><b>Third-party personal data.</b> {@link #personName}, {@link #personRole},
 * {@link #relationText} and the free-text {@link #text} name real people at client
 * organisations who never gave us anything. Purpose is commercial relationship
 * management. The agreed retention is 24 months after the account's last activity, after
 * which the name is nulled and the text redacted (spec §3.4) — but the purge job is NOT
 * built in this cut, so nothing enforces it yet. Do not read this as an implemented
 * control. The table is excluded from
 * {@code sp_sync_prod_to_staging} so these names are never copied into staging, and the
 * row is refused outright while an admin is impersonating someone — attributing what a
 * named third party is doing to an employee who did not say it is worse than having no
 * signal at all.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_signal")
public class AccountSignal extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /**
     * The capture this row belongs to (V593).
     *
     * <p>One line may name several accounts — <i>"@Rigspolitiet … som jeg har mødt i
     * @KOMBIT"</i> — and each account gets its OWN row, sharing this uuid. That is
     * deliberate: {@link #status}, {@link #leadUuid} and {@link #decidedBy} are per
     * account, and {@code AccountSignalService.decide} authorizes against one client's
     * account manager, so one owner parking a signal must not park it for another's.
     * Group by this when a reader wants to say "also filed on Rigspolitiet".
     *
     * <p>Never null. A capture naming one account is a capture of one, and rows that
     * predate V593 were backfilled to their own uuid, so no reader needs a null branch.
     */
    @Column(name = "capture_uuid", length = 36, nullable = false)
    private String captureUuid;

    /** The client the signal is about. Required — a signal with no account has nowhere to land. */
    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** The employee who heard it. Resolved from {@code X-Requested-By}, never from the body. */
    @Column(name = "author_uuid", length = 36, nullable = false)
    private String authorUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    private SignalSource source;

    /** The verbatim line, {@code @Client} prefix included. Never rewritten. */
    @Column(name = "signal_text", nullable = false, columnDefinition = "TEXT")
    private String text;

    /** Extracted. Null when the line named nobody, or when extraction was unavailable. */
    @Column(name = "person_name", length = 255)
    private String personName;

    /** Extracted, e.g. "Head of AI (new)". Null when no role was stated. */
    @Column(name = "person_role", length = 255)
    private String personRole;

    /** Extracted, phrased from the author, e.g. "Hans knows Benny Hoffmann from school". */
    @Column(name = "relation_text", length = 500)
    private String relationText;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", length = 20, nullable = false)
    private SignalType signalType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private SignalStatus status;

    /** Set only when {@link #status} becomes {@code LEAD_CREATED}. Not written in this cut. */
    @Column(name = "lead_uuid", length = 36)
    private String leadUuid;

    /** The owner who decided. Not written in this cut. */
    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    /** When the owner decided. Not written in this cut. */
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
