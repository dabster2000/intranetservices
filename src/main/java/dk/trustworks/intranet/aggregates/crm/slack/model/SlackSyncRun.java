package dk.trustworks.intranet.aggregates.crm.slack.model;

import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncLane;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncRunStatus;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncTrigger;
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
 * One run of either Slack lane, and what it did (spec §5.4).
 *
 * <p>Until now a run left nothing behind but a log line, which answers "did it go last
 * night" only for somebody with CloudWatch open. The Settings tab has to answer it in the
 * browser, and the manual trigger has to answer "is it still going" the moment after
 * pressing the button — so the row is opened {@link SlackSyncRunStatus#RUNNING} before the
 * work starts and closed with the counts when it ends, rather than written once at the end.
 *
 * <p>Both lanes share the table because they share every question asked of them and because
 * one bookkeeping table is one purge rule; {@link #lane} separates them. They do not share
 * a lock.
 *
 * <p>The column behind {@link #triggerKind} is {@code trigger_kind}: {@code TRIGGER} is
 * reserved in MariaDB, the same trap {@code LINES} sprang on V534.
 *
 * <p><b>{@link #failureCode} is a code, never an upstream body.</b> A Slack or model error
 * body can carry a channel name, a company name or a line somebody wrote, and none of that
 * belongs in a table an admin screen reads back. The counts are the same discipline: what
 * is stored about a run is how much of it happened, not what it saw.
 *
 * <p>Rows older than 90 days are deleted at the end of each run.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "crm_slack_sync_run")
public class SlackSyncRun extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "lane", length = 20, nullable = false)
    private SlackSyncLane lane;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", length = 10, nullable = false)
    private SlackSyncTrigger triggerKind;

    /** The admin who pressed the button; null on a scheduled run, which has nobody behind it. */
    @Column(name = "started_by", length = 36)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private SlackSyncRunStatus status;

    /** Channels the run set out to read. */
    @Column(name = "channels", nullable = false)
    private int channels;

    /** Of those, the ones Slack actually answered for. */
    @Column(name = "channels_read", nullable = false)
    private int channelsRead;

    @Column(name = "days", nullable = false)
    private int days;

    /** Digest rows on the account-space lane, mention rows on the source-channel lane. */
    @Column(name = "readings", nullable = false)
    private int readings;

    @Column(name = "unmatched", nullable = false)
    private int unmatched;

    @Column(name = "link_errors", nullable = false)
    private int linkErrors;

    @Column(name = "failures", nullable = false)
    private int failures;

    /** A CODE only. Never an upstream error body. */
    @Column(name = "failure_code", length = 40)
    private String failureCode;
}
