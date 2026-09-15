package dk.trustworks.intranet.aggregates.crm.retention.model;

import dk.trustworks.intranet.aggregates.crm.retention.model.enums.CrmPurgeStatus;
import dk.trustworks.intranet.aggregates.crm.retention.model.enums.CrmPurgeTrigger;
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
 * One run of the 24-month CRM retention purge, and what it did (spec §7).
 *
 * <p>Eight migrations since V584 and nine entity javadocs record the same promise — third
 * party personal data goes 24 months after the account last saw activity — and until this
 * cut nothing kept it. A promise that is finally being kept has to be auditable, and a log
 * line is not: it answers "did it go last night" only for somebody with CloudWatch open.
 * This table answers it, and answers the harder question underneath — <em>how much did it
 * erase, and was it armed at all</em> — from a browser.
 *
 * <p><b>Opened {@link CrmPurgeStatus#RUNNING} before the work, closed with the counts
 * after</b>, rather than written once at the end. For a destructive job that two-phase write
 * earns its keep twice over: a run killed mid-sweep is then visible as exactly what it was,
 * a row nobody ever closed, and the rows it had already erased are accounted for by the
 * eligibility rule re-deriving the same answer tomorrow.
 *
 * <p><b>{@link #dryRun} is a first-class column, not a log detail.</b> A preview and a
 * destruction are the two things this table must never confuse, and an admin reading back
 * "meetingAttendees = 4,318" has to be able to tell at a glance whether those rows are gone.
 *
 * <p>The column behind {@link #triggerKind} is {@code trigger_kind}: {@code TRIGGER} is
 * reserved in MariaDB, the same trap {@code LINES} sprang on V534.
 *
 * <p><b>{@link #failureCode} is a code, never an upstream body.</b> Everywhere else in this
 * repo that rule protects an admin screen from echoing a channel name or a line somebody
 * wrote; here it protects something sharper. The error this job throws is a SQL error about
 * the very row it is erasing, and a constraint message can carry that row's value verbatim —
 * so a purge that wrote its exception text here would file the personal data it exists to
 * destroy into a table that is read back, kept for ninety days and copied nowhere safer. The
 * counters are the same discipline: they say how much happened, never what was seen.
 *
 * <p>Rows older than 90 days are deleted at the end of each run, on {@code started_at} — a
 * row that was never closed has no finish, and those are precisely the ones that would
 * otherwise accumulate for ever.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "crm_retention_purge_run")
public class CrmRetentionPurgeRun extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", length = 10, nullable = false)
    private CrmPurgeTrigger triggerKind;

    /** The admin who asked for the run; null on a scheduled one, which has nobody behind it. */
    @Column(name = "started_by", length = 36)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private CrmPurgeStatus status;

    /** A preview: the run row is the only thing this run wrote. */
    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    /** Accounts found past the 24-month threshold with something left to erase — before the cap. */
    @Column(name = "accounts_considered", nullable = false)
    private int accountsConsidered;

    /** Of those, the ones this run actually swept: the blast-radius cap, oldest first. */
    @Column(name = "accounts_purged", nullable = false)
    private int accountsPurged;

    /** Calendar attendee, candidate and review PII rows deleted; dated meeting skeletons stay. */
    @Column(name = "meeting_attendees", nullable = false)
    private int meetingAttendees;

    /** {@code account_signal} rows whose four person-bearing columns were cleared or redacted. */
    @Column(name = "signals_redacted", nullable = false)
    private int signalsRedacted;

    /** {@code account_slack_mention} rows deleted; their participants went on the cascade. */
    @Column(name = "slack_mentions", nullable = false)
    private int slackMentions;

    /** {@code account_person} rows deleted; identities and claims went on the cascade. */
    @Column(name = "people_deleted", nullable = false)
    private int peopleDeleted;

    /** {@code client_plan_stakeholder} rows whose name and person link were nulled — the seat stays. */
    @Column(name = "stakeholders_cleared", nullable = false)
    private int stakeholdersCleared;

    /** {@code trustlink_connection} rows deleted on staleness, not on account inactivity. */
    @Column(name = "trustlink_connections", nullable = false)
    private int trustlinkConnections;

    /** Accounts that threw and were skipped. One bad account never abandons the rest. */
    @Column(name = "failures", nullable = false)
    private int failures;

    /** A CODE only. Never an upstream error body — see the class javadoc, it matters here. */
    @Column(name = "failure_code", length = 40)
    private String failureCode;
}
