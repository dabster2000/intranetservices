package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSyncRun;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncLane;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncRunStatus;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncTrigger;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackSyncService.SyncSummary;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Run bookkeeping for both Slack lanes, and the single-flight guard that keeps two runs off
 * the same rows (spec §4.1, §5.4, §6).
 *
 * <h2>Why a row rather than a log line</h2>
 * Both lanes used to leave nothing behind but one closing {@code log.infof}, which answers
 * "did it go last night" only for somebody with CloudWatch open and the log group name to
 * hand. Settings → CRM has to answer it in a browser, and a manual run has to answer "is it
 * still going" the moment after the button was pressed — so the row is opened
 * {@link SlackSyncRunStatus#RUNNING} before the work starts and closed with the counts when
 * it ends, rather than written once at the end. A run that died mid-way is then visible as
 * exactly what it was: a row nobody ever closed.
 *
 * <h2>The lane lock, and the trap in it</h2>
 * Each lane has its own {@link AtomicBoolean}. <b>{@code ConcurrentExecution.SKIP} on the
 * scheduled jobs guards the scheduler's own invocations and nothing else</b> — it cannot see
 * a run started through {@code POST /crm/slack/sync/{lane}}, so without this guard an admin
 * pressing the button at 02:26 would have their run race the nightly one onto the same
 * deterministic primary keys, and the loser would abandon a night's worth of days and log it
 * as {@code UNEXPECTED}. That is the trap the next reader falls into, and it is why the
 * lanes take this lock as well as carrying {@code SKIP}. It is per-JVM, like
 * {@code TrustLinkSyncService}'s: {@code @Scheduled} fires on every running task, and this
 * covers the overlap that actually happens in practice rather than the one that is
 * theoretically possible.
 *
 * <p>The two lanes deliberately do <em>not</em> share a lock. They read different channels
 * and write different tables, their jobs are fifteen minutes apart so they do not compete
 * for the pool, and an admin re-reading one lane has no reason to be told the other one is
 * busy.
 *
 * <h2>Submit only after the transaction has closed</h2>
 * {@link ManagedExecutor} propagates the JTA context of the thread that submits. A submit
 * made while a transaction is still open therefore drags the request thread's pooled
 * connection onto the worker and holds it for the length of a run that reads Slack and calls
 * a model. Every write below is wrapped in its own {@link QuarkusTransaction#requiringNew()},
 * which commits and closes before it returns, and {@link #runAsync} snapshots the run uuid
 * as a plain {@code String} before the submit rather than carrying the entity across — an
 * entity detached by that commit would be a second way to be wrong about the same thing.
 *
 * <h2>Codes, never bodies</h2>
 * {@code failure_code} is a code. A Slack or model error body can echo a channel name, a
 * company name or a line somebody wrote, and none of that belongs in a table an admin screen
 * reads back — the same discipline the counters follow, which record how much of a run
 * happened rather than what it saw.
 */
@JBossLog
@ApplicationScoped
public class SlackSyncRunService {

    /** How long a closed run is worth keeping. Past a quarter nobody is asking about that night any more. */
    public static final int RETENTION_DAYS = 90;

    /** The Settings tab shows a handful of runs, not a history. */
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 50;

    /** The fallback code, as {@code TrustLinkSyncService.failureCodeOf} uses it. */
    public static final String FAILURE_UNEXPECTED = "UNEXPECTED";

    /** The executor itself refused the work — the run never started and must not be left open. */
    static final String FAILURE_SUBMIT_REJECTED = "SUBMIT_REJECTED";

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    AccountSlackSyncService accountSlackSyncService;

    @Inject
    SlackSourceSyncService slackSourceSyncService;

    /**
     * What this process knows about a lane right now. Created on first use rather than
     * pre-populated, so a third lane needs no second edit here.
     */
    private final Map<SlackSyncLane, LaneState> lanes = new ConcurrentHashMap<>();

    private static final class LaneState {

        /** Held for the whole of a run, across the hand-off from the request thread to the worker. */
        final AtomicBoolean running = new AtomicBoolean(false);

        /** A row {@link SlackSyncRunService#runAsync} opened that the lane's own work has not taken over yet. */
        final AtomicReference<String> handedOver = new AtomicReference<>();
    }

    // ------------------------------------------------------------------------
    // The lane lock
    // ------------------------------------------------------------------------

    /**
     * Takes the lane, or answers that somebody else has it.
     *
     * <p>The caller that got {@code true} is the one that must {@link #release} it, and only
     * that one — the lock is an {@link AtomicBoolean} and not a {@code ReentrantLock}
     * precisely because the manual path acquires it on the request thread and releases it on
     * a worker, which a lock owned by its acquiring thread cannot do.
     */
    public boolean tryAcquire(SlackSyncLane lane) {
        return state(lane).running.compareAndSet(false, true);
    }

    /** Gives the lane back. Safe to call only from whoever {@link #tryAcquire} said yes to. */
    public void release(SlackSyncLane lane) {
        state(lane).running.set(false);
    }

    /**
     * Whether a run holds this lane, for the resource's 409 and for the tab to grey out Run
     * now while one is in flight.
     *
     * <p>Answering 409 from this alone is a check-then-act race: two clicks a millisecond
     * apart both read {@code false}. It is a good enough answer for rendering, and
     * {@link #runAsync} gives the race-free one.
     */
    public boolean isRunning(SlackSyncLane lane) {
        return state(lane).running.get();
    }

    // ------------------------------------------------------------------------
    // The run row
    // ------------------------------------------------------------------------

    /**
     * Opens a run row — or hands back the one {@link #runAsync} opened moments ago for this
     * lane.
     *
     * <p>The hand-back is what lets a lane's own {@code syncAll} be written the obvious way.
     * A manual run has to name its row in the {@code 202} it answers with, so the row is
     * opened on the request thread before the work is submitted; the lane then reaches this
     * method on the worker and would otherwise open a second row and leave the first
     * {@code RUNNING} for ever. The lane lock is held across that hand-off, so at most one
     * row is ever waiting to be taken over.
     *
     * <p>The returned entity is detached — the transaction that wrote it has already
     * committed. It is a handle for {@link #finish}, which re-reads by uuid; nothing should
     * mutate it.
     */
    public SlackSyncRun start(SlackSyncLane lane, SlackSyncTrigger trigger, String actor) {
        String handedOver = state(lane).handedOver.getAndSet(null);
        if (handedOver != null) {
            SlackSyncRun waiting = QuarkusTransaction.requiringNew()
                    .call(() -> SlackSyncRun.<SlackSyncRun>findById(handedOver));
            if (waiting != null) {
                return waiting;
            }
        }
        SlackSyncRun run = new SlackSyncRun();
        run.setUuid(UUID.randomUUID().toString());
        run.setLane(lane);
        run.setTriggerKind(trigger);
        // Only a manual run has anybody behind it; a scheduled one is nobody's, and writing a
        // placeholder there would make the column a liar.
        run.setStartedBy(trigger == SlackSyncTrigger.MANUAL ? actor : null);
        run.setStartedAt(LocalDateTime.now());
        run.setStatus(SlackSyncRunStatus.RUNNING);
        QuarkusTransaction.requiringNew().run(run::persist);
        log.infof("Slack sync run %s opened: lane=%s trigger=%s", run.getUuid(), lane, trigger);
        return run;
    }

    /**
     * Closes a run with its counts, then forgets the runs nobody will ask about again.
     *
     * <p>{@code failures} being non-zero still closes the run {@code DONE}: the loop got to
     * the end, the channels it could not read carry their own verdicts, and the next run
     * re-reads their windows because their cursors never moved. The one status that is not
     * an ordinary ending is {@link SlackSyncRunStatus#STOPPED}, where the Slack app itself is
     * misconfigured and every remaining channel would have answered the same way.
     *
     * <p>The purge rides along here rather than sitting in the jobs, exactly as the calendar
     * lane's sighting purge rides inside {@code refreshAggregates()}: one place that has to
     * be reached for a run to be recorded at all is one place that cannot be forgotten.
     *
     * <p>A null summary still closes the row, with its counters left at zero. Leaving it
     * {@code RUNNING} instead would turn a lane that answered nothing into a run that looks
     * like it is still going, which is the one reading of this table that must never be wrong.
     */
    public void finish(SlackSyncRun run, SyncSummary summary) {
        if (run == null) {
            return;
        }
        close(run.getLane(), run.getUuid(), statusOf(summary), summary);
    }

    /**
     * A run that could not read three channels still finished; one whose Slack app is
     * misconfigured did not, because every remaining channel would have answered the same
     * way. That is the whole distinction, and it is the only one the counters cannot make on
     * their own.
     */
    private static SlackSyncRunStatus statusOf(SyncSummary summary) {
        return summary != null && summary.stoppedOnConfiguration()
                ? SlackSyncRunStatus.STOPPED
                : SlackSyncRunStatus.DONE;
    }

    /**
     * Closes a run that threw where the per-channel handling could not catch it.
     *
     * <p>Separate from {@link #finish} because the counts are not knowable at that point —
     * the summary was never built — and because a run that fell over is a different fact from
     * one that finished having failed at three channels. The code is a code: see the class
     * javadoc.
     */
    public void fail(SlackSyncRun run, String failureCode) {
        if (run == null) {
            return;
        }
        close(run.getLane(), run.getUuid(), SlackSyncRunStatus.FAILED, null, failureCode);
    }

    /**
     * The newest runs of a lane, for {@code GET /crm/slack/sync/runs}.
     *
     * <p>No transaction: one indexed read off {@code idx_crm_slack_sync_run_lane}, exactly as
     * the calendar suggestions next door are read.
     */
    public List<SlackSyncRun> latest(SlackSyncLane lane, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return SlackSyncRun.find("lane = ?1 order by startedAt desc", lane)
                .page(0, capped)
                .list();
    }

    // ------------------------------------------------------------------------
    // The manual trigger
    // ------------------------------------------------------------------------

    /**
     * Starts a lane in the background and names the row the caller can poll.
     *
     * <p>Asynchronous because the ALB cuts a request at 60 seconds and a lane that reads
     * twenty-five channels and makes a model call per channel-day is minutes of work — the
     * {@code SalaryRecalculationService} shape: take the guard, submit, answer {@code 202}.
     *
     * <p>The guard is taken <b>before</b> the submit, not inside the worker, so a
     * double-click is refused while the admin is still looking at the button rather than
     * being discovered minutes later by a worker that then silently does nothing. An empty
     * answer is the race-free "that lane is already running" the resource turns into a 409;
     * {@link #isRunning} is the cheaper, racier one for rendering.
     *
     * <p>The lane's flag is deliberately not read here. A lane switched off answers
     * {@code 412} at the resource before this is reached, and the lane's own work checks the
     * flag again anyway; a run row opened for a switched-off lane is closed with zeros by the
     * hand-back below rather than left hanging.
     *
     * @return the uuid of the run row, or empty when the lane was already held
     */
    public Optional<String> runAsync(SlackSyncLane lane, String actor) {
        LaneState state = state(lane);
        if (!state.running.compareAndSet(false, true)) {
            log.infof("Slack sync: lane %s is already running — the manual trigger was refused", lane);
            return Optional.empty();
        }

        String runUuid;
        try {
            runUuid = start(lane, SlackSyncTrigger.MANUAL, actor).getUuid();
        } catch (RuntimeException e) {
            state.running.set(false);
            throw e;
        }
        state.handedOver.set(runUuid);

        try {
            // The transaction start() needed has committed and closed by now. See the class
            // javadoc: submitting with one open would carry it onto the worker.
            managedExecutor.submit(() -> {
                try {
                    SyncSummary summary = work(lane, SlackSyncTrigger.MANUAL, actor);
                    // The lane took the row over through start() and closed it itself unless
                    // it never got that far — a switched-off flag is the ordinary way that
                    // happens — in which case it is closed here with what little there is.
                    if (state.handedOver.compareAndSet(runUuid, null)) {
                        close(lane, runUuid, statusOf(summary), summary);
                    }
                } catch (RuntimeException e) {
                    // The message, never the cause chain and never an upstream body.
                    log.errorf("Slack sync run %s on lane %s failed: %s", runUuid, lane, e.getMessage());
                    if (state.handedOver.compareAndSet(runUuid, null)) {
                        close(lane, runUuid, SlackSyncRunStatus.FAILED, null, FAILURE_UNEXPECTED);
                    }
                } finally {
                    // After the row is closed, never before: the lane has to look busy until
                    // there is nothing left RUNNING for the next caller to collide with.
                    state.running.set(false);
                }
            });
        } catch (RuntimeException e) {
            state.handedOver.compareAndSet(runUuid, null);
            close(lane, runUuid, SlackSyncRunStatus.FAILED, null, FAILURE_SUBMIT_REJECTED);
            state.running.set(false);
            log.errorf("Slack sync: lane %s could not be submitted to the executor: %s", lane, e.getMessage());
            throw e;
        }
        return Optional.of(runUuid);
    }

    /**
     * Which lane does the work. An exhaustive switch over the enum rather than a registry:
     * there are two lanes, the compiler checks the third one is handled the day it exists,
     * and both take the same {@code (trigger, actor)} so the attribution survives the
     * hand-off to the worker.
     */
    private SyncSummary work(SlackSyncLane lane, SlackSyncTrigger trigger, String actor) {
        return switch (lane) {
            case ACCOUNT_SPACES -> accountSlackSyncService.syncAll(trigger, actor);
            case SOURCE_CHANNELS -> slackSourceSyncService.syncAll(trigger, actor);
        };
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private LaneState state(SlackSyncLane lane) {
        return lanes.computeIfAbsent(lane, key -> new LaneState());
    }

    private void close(SlackSyncLane lane, String runUuid, SlackSyncRunStatus status, SyncSummary summary) {
        close(lane, runUuid, status, summary, summary == null ? null : summary.failureCode());
    }

    /**
     * Writes the ending, then purges.
     *
     * <p>The row is re-read by uuid rather than the caller's handle being mutated: that
     * handle came out of a transaction that committed long ago, and on the manual path it
     * crossed a thread as well.
     */
    private void close(SlackSyncLane lane, String runUuid, SlackSyncRunStatus status,
                       SyncSummary summary, String failureCode) {
        QuarkusTransaction.requiringNew().run(() -> {
            SlackSyncRun row = SlackSyncRun.findById(runUuid);
            if (row == null) {
                return;
            }
            row.setStatus(status);
            row.setFinishedAt(LocalDateTime.now());
            if (summary != null) {
                // "accounts" is the lane's own word for the things it set out to read: linked
                // account spaces on one lane, listed source channels on the other. The column
                // is channels for both.
                row.setChannels(summary.accounts());
                row.setChannelsRead(summary.channelsRead());
                row.setDays(summary.daysDigested());
                row.setReadings(summary.readings());
                row.setUnmatched(summary.unmatched());
                row.setLinkErrors(summary.linkErrors());
                row.setFailures(summary.failures());
            }
            row.setFailureCode(failureCode);
            row.persist();
        });

        log.infof("Slack sync run %s closed: lane=%s status=%s channels=%d read=%d days=%d readings=%d "
                        + "unmatched=%d linkErrors=%d failures=%d failureCode=%s",
                runUuid, lane, status,
                summary == null ? 0 : summary.accounts(),
                summary == null ? 0 : summary.channelsRead(),
                summary == null ? 0 : summary.daysDigested(),
                summary == null ? 0 : summary.readings(),
                summary == null ? 0 : summary.unmatched(),
                summary == null ? 0 : summary.linkErrors(),
                summary == null ? 0 : summary.failures(),
                failureCode == null ? "-" : failureCode);

        purge();
    }

    /**
     * Forgets runs older than the retention window.
     *
     * <p>{@code started_at}, not {@code finished_at}: a row that was never closed has no
     * finish, and those are precisely the rows — a process killed mid-run — that would
     * otherwise accumulate for ever.
     */
    void purge() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long purged = QuarkusTransaction.requiringNew()
                .call(() -> SlackSyncRun.delete("startedAt < ?1", cutoff));
        if (purged > 0) {
            log.infof("Slack sync runs: purged %d rows older than %s", purged, cutoff.toLocalDate());
        }
    }
}
