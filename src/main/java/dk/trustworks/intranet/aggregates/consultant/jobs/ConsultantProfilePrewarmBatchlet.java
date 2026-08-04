package dk.trustworks.intranet.aggregates.consultant.jobs;

import dk.trustworks.intranet.aggregates.consultant.services.ConsultantProfileGenerationService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Nightly pre-warm for the consultant sales profiles behind the dashboard "Available Now" card.
 *
 * <p>Runs at 05:20 Europe/Copenhagen: clear of the 02:00 staging nightly refresh and of the
 * 03:00/03:30 UTC BI + OPEX windows, and finished before the workday so the first dashboard load
 * of the morning hits a warm cache.
 *
 * <p>Deliberate omissions:
 * <ul>
 *   <li><b>No {@code @Observes StartupEvent} cold-start hook.</b> This repo has been bitten by
 *       startup-race jobs, and a cold cache degrades to a thin-but-correct card, not an outage.</li>
 *   <li><b>No Slack alert.</b> A missing pitch is cosmetic. Failures log at ERROR (routed to
 *       Sentry via JBoss-log) and the next night retries.</li>
 * </ul>
 *
 * <p><b>The scheduler is not clustered</b> — every running ECS task fires this cron, and
 * {@code ConcurrentExecution.SKIP} only guards within one JVM. The cross-instance guard is the
 * per-consultant row claim in {@link #claim(String, LocalDateTime)}: whoever wins the conditional
 * {@code UPDATE} owns the generation, everyone else skips.
 */
@JBossLog
@ApplicationScoped
public class ConsultantProfilePrewarmBatchlet {

    /**
     * Another task (or an on-demand enqueue) is assumed to still own a consultant it claimed
     * within this window. Comfortably longer than the ~110 s OpenAI read timeout.
     */
    static final int CLAIM_TTL_MINUTES = 20;
    /** Mirrors {@code ConsultantProfile.isStale} — a profile older than this is regenerated. */
    static final int STALENESS_DAYS = 7;
    /**
     * A consultant parked as {@code UNAVAILABLE} is given one more chance after this long. Without
     * it the park is permanent: nothing in the codebase ever resets the status, and both the
     * on-demand path ({@code shouldAttempt}) and this job would exclude the row forever. 30 days
     * bounds the wasted spend to at most one call per parked consultant per month.
     */
    static final int UNAVAILABLE_RETRY_DAYS = 30;

    @Inject
    ConsultantProfileGenerationService generationService;

    @Inject
    EntityManager em;

    /**
     * Ships OFF — see the comment on {@code consultant-profile.prewarm.enabled} in
     * {@code application.yml}. Bulk unattended CV transmission to a US processor needs privacy
     * sign-off and a manual staging quality read before it is switched on.
     */
    @ConfigProperty(name = "consultant-profile.prewarm.enabled", defaultValue = "false")
    boolean prewarmEnabled;

    @ConfigProperty(name = "consultant-profile.prewarm.batch-size", defaultValue = "40")
    int batchSize;

    @ConfigProperty(name = "consultant-profile.prewarm.pause-ms", defaultValue = "1000")
    long pauseMs;

    @Scheduled(cron = "0 20 5 * * ?",
            identity = "consultant-profile-prewarm",
            timeZone = "Europe/Copenhagen",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scheduledRun() {
        if (!prewarmEnabled) {
            log.debug("Consultant profile pre-warm disabled — skipping");
            return;
        }
        if (!generationService.isGenerationEnabled()) {
            log.debug("Consultant profile generation disabled — skipping pre-warm");
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.errorf(e, "Consultant profile pre-warm run failed");
        }
    }

    /** Package-private so it can be driven deterministically outside the scheduler. */
    void run() {
        QuarkusTransaction.requiringNew().run(this::seedMissingRows);
        List<String> candidates = QuarkusTransaction.requiringNew().call(this::selectCandidates);
        if (candidates.isEmpty()) {
            log.info("Consultant profile pre-warm: nothing stale");
            return;
        }

        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (String useruuid : candidates) {
            LocalDateTime now = LocalDateTime.now();
            boolean claimed;
            try {
                claimed = QuarkusTransaction.requiringNew().call(() -> claim(useruuid, now));
            } catch (Exception e) {
                log.errorf(e, "Consultant profile pre-warm could not claim %s", useruuid);
                failed++;
                continue;
            }
            if (!claimed) {
                log.debugf("Consultant profile pre-warm skipping %s — claimed elsewhere", useruuid);
                skipped++;
                continue;
            }
            try {
                generationService.generateOne(useruuid);
                generated++;
            } catch (Exception e) {
                // One consultant must never abort the run.
                log.errorf(e, "Consultant profile pre-warm failed for %s", useruuid);
                failed++;
            }
            if (!pause()) {
                break;
            }
        }
        log.infof("Consultant profile pre-warm finished: candidates=%d generated=%d skipped=%d failed=%d",
                candidates.size(), generated, skipped, failed);
    }

    /** @return false when the thread was interrupted and the run should stop */
    private boolean pause() {
        if (pauseMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(pauseMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Consultant profile pre-warm interrupted — stopping early");
            return false;
        }
    }

    /**
     * Seeds a claimable row for every active consultant that has a CV but no profile row yet.
     * Idempotent and race-safe via {@code INSERT IGNORE}.
     */
    void seedMissingRows() {
        int seeded = em.createNativeQuery("""
                INSERT IGNORE INTO consultant_profiles (useruuid, status, generation_attempts)
                SELECT cv.useruuid, 'PENDING', 0
                FROM cv_tool_employee_cv cv
                JOIN userstatus us ON us.uuid = (
                        SELECT us2.uuid FROM userstatus us2
                        WHERE us2.useruuid = cv.useruuid AND us2.statusdate <= CURDATE()
                        ORDER BY us2.statusdate DESC, us2.created_at DESC LIMIT 1)
                WHERE us.status = 'ACTIVE' AND us.type = 'CONSULTANT'
                """).executeUpdate();
        if (seeded > 0) {
            log.infof("Consultant profile pre-warm seeded %d new profile row(s)", seeded);
        }
    }

    /**
     * One row per consultant, never per CV row.
     *
     * <p>The CV side is aggregated to {@code MAX(cv_last_updated_at)} — the same row
     * {@code ConsultantProfileGenerationService.pickCv} selects — because a consultant may hold
     * several CV rows (language variants; V460 dropped the unique constraint on
     * {@code cvtool_employee_id}). A plain join would compare {@code p.cv_updated_at} against
     * <em>every</em> variant, so the non-picked one always mismatches and the consultant is
     * regenerated on every single run forever, while {@code LIMIT} — applied by the database
     * before any Java-side {@code distinct()} — silently shrinks the effective batch.
     *
     * <p>{@code UNAVAILABLE} rows are readmitted after {@link #UNAVAILABLE_RETRY_DAYS}. Without
     * that there is no recovery path at all: a consultant parked because their CV had not synced
     * yet, or because OpenAI was down long enough to burn {@code max-attempts}, would be excluded
     * by this query and by {@code ConsultantProfile.shouldAttempt} forever, and only a manual
     * {@code UPDATE} against production could bring them back.
     */
    @SuppressWarnings("unchecked")
    List<String> selectCandidates() {
        LocalDateTime now = LocalDateTime.now();
        List<Object> rows = em.createNativeQuery("""
                        SELECT p.useruuid
                        FROM consultant_profiles p
                        JOIN (SELECT c.useruuid AS useruuid,
                                     MAX(c.cv_last_updated_at) AS cv_last_updated_at
                              FROM cv_tool_employee_cv c
                              GROUP BY c.useruuid) cv ON cv.useruuid = p.useruuid
                        JOIN userstatus us ON us.uuid = (
                                SELECT us2.uuid FROM userstatus us2
                                WHERE us2.useruuid = p.useruuid AND us2.statusdate <= CURDATE()
                                ORDER BY us2.statusdate DESC, us2.created_at DESC LIMIT 1)
                        WHERE us.status = 'ACTIVE' AND us.type = 'CONSULTANT'
                          AND (p.status <> 'UNAVAILABLE'
                               OR p.last_attempt_at IS NULL
                               OR p.last_attempt_at < :unavailableCutoff)
                          AND (p.generated_at IS NULL
                               OR p.generated_at < :staleCutoff
                               OR p.cv_updated_at IS NULL
                               OR p.cv_updated_at <> cv.cv_last_updated_at)
                        ORDER BY (p.generated_at IS NULL) DESC, p.generated_at ASC
                        LIMIT :batchSize
                        """)
                .setParameter("staleCutoff", now.minusDays(STALENESS_DAYS))
                .setParameter("unavailableCutoff", now.minusDays(UNAVAILABLE_RETRY_DAYS))
                .setParameter("batchSize", batchSize)
                .getResultList();
        return rows.stream().map(String::valueOf).distinct().toList();
    }

    /**
     * Cross-instance claim. {@code executeUpdate() == 1} means this task owns the consultant;
     * {@code 0} means another ECS task or an on-demand enqueue got there first.
     *
     * <p>The claim moves {@code last_attempt_at} only. It deliberately does <b>not</b> touch
     * {@code generation_attempts}: {@code ConsultantProfileGenerationService.recordFailure} already
     * increments that on every failure, and incrementing here too would double-count, parking a
     * consultant as {@code UNAVAILABLE} after two failed nights instead of {@code max-attempts}
     * failures — from which {@link #selectCandidates()} would only readmit it after the
     * {@link #UNAVAILABLE_RETRY_DAYS} cooldown.
     */
    boolean claim(String useruuid, LocalDateTime now) {
        return em.createNativeQuery("""
                        UPDATE consultant_profiles
                        SET last_attempt_at = :now
                        WHERE useruuid = :uuid
                          AND (last_attempt_at IS NULL OR last_attempt_at < :claimCutoff)
                        """)
                .setParameter("now", now)
                .setParameter("uuid", useruuid)
                .setParameter("claimCutoff", now.minusMinutes(CLAIM_TTL_MINUTES))
                .executeUpdate() == 1;
    }
}
