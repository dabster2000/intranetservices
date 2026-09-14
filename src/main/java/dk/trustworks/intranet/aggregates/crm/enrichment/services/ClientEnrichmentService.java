package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentConfig;
import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentFeatureFlag;
import dk.trustworks.intranet.aggregates.crm.enrichment.dto.ClientEnrichmentDTO;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.ClientEnrichment;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.CvrEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.LogoEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.SectorEnrichmentStatus;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The front door of client enrichment: what the account page reads, the one-click
 * actions, the hooks {@code ClientResource} calls after a create or edit, and the runner
 * behind both the nightly cron and the manual trigger.
 *
 * <h2>Two switches and a gate</h2>
 * {@code dk.trustworks.crm.enrichment.enabled} (yml, env var) and the {@code app_settings}
 * row behind {@link ClientEnrichmentFeatureFlag} both have to be on for anything to spend
 * a token or a registry call. The nightly cron additionally refuses on staging
 * ({@link ClientEnrichmentConfig#nightlyAllowedHere}); the manual trigger does not, so a
 * run can be rehearsed there against the nightly copy of production's clients.
 *
 * <h2>One run at a time</h2>
 * The cron's {@code SKIP} only covers the scheduler's own invocations; {@link #running}
 * is what keeps a manual run and the cron apart. It is held from the request thread's
 * submit to the worker's finally, which is why it is an {@link AtomicBoolean} rather than a
 * lock owned by its acquiring thread — the {@code SlackSyncRunService} shape.
 *
 * <h2>The edit hooks</h2>
 * {@link #onClientUpdated} is where a person's decisions are written down: a CVR they
 * changed re-opens the CVR question; a sector they chose is {@code HUMAN_SET}; a sector
 * they put back to OTHER after the AI had set one is {@code HUMAN_OVERRIDE} and is never
 * re-checked. That last rule is what keeps the job from taking turns with a person.
 */
@JBossLog
@ApplicationScoped
public class ClientEnrichmentService {

    /** The Vert.x address the after-write sector check is published on. */
    public static final String SECTOR_VERIFY_ADDRESS = "client.sector.verify";

    public enum Job { CVR, LOGO, SECTOR }

    @Inject ClientEnrichmentConfig config;
    @Inject ClientEnrichmentFeatureFlag featureFlag;
    @Inject ClientEnrichmentRepository repository;
    @Inject CvrEnrichmentService cvrService;
    @Inject LogoEnrichmentService logoService;
    @Inject SectorEnrichmentService sectorService;
    @Inject SchedulerShutdownGuard shutdown;
    @Inject EventBus eventBus;
    @Inject TransactionSynchronizationRegistry txSyncRegistry;

    /**
     * A plain thread, deliberately NOT the {@code ManagedExecutor}. That executor propagates
     * the submitting HTTP request's CDI request context onto the worker, and that context
     * is terminated the moment the {@code 202} is written — so every request-scoped lookup
     * the run makes afterwards ({@code RequestHeaderHolder} behind the activity log, most of
     * all) dies with {@code ContextNotActiveException}. The second staging rehearsal
     * (2026-09-14) failed on exactly that, the same way the employee-document maintenance
     * runs once did. A plain thread carries no stale context, and
     * {@link #withRequestContext} gives the run a fresh one of its own.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "client-enrichment-run");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------

    public List<ClientEnrichmentDTO> readAll() {
        List<ClientEnrichmentDTO> out = new ArrayList<>();
        for (ClientEnrichment row : repository.findAll()) out.add(ClientEnrichmentDTO.of(row));
        return out;
    }

    /** A client with no row yet answers with its seeded state, without writing one. */
    public ClientEnrichmentDTO read(String clientUuid) {
        Optional<ClientEnrichment> row = repository.find(clientUuid);
        if (row.isPresent()) return ClientEnrichmentDTO.of(row.get());
        Client client = Client.findById(clientUuid);
        if (client == null) throw new WebApplicationException("Client not found", 404);
        return ClientEnrichmentDTO.of(ClientEnrichmentRepository.initialState(client));
    }

    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------------
    // One-click actions
    // ------------------------------------------------------------------------

    @ActivateRequestContext
    public ClientEnrichmentDTO acceptCvrCandidate(String clientUuid) {
        requireEnabled();
        requireRegistryAllowed();
        cvrService.acceptCandidate(clientUuid);
        return read(clientUuid);
    }

    public ClientEnrichmentDTO dismissCvr(String clientUuid) {
        QuarkusTransaction.requiringNew().run(() -> {
            ClientEnrichment row = repository.ensure(clientUuid);
            if (row == null) throw new WebApplicationException("Client not found", 404);
            row.setCvr(CvrEnrichmentStatus.DISMISSED);
            row.setCvrCheckedAt(LocalDateTime.now());
            row.setCvrError(null);
            row.clearCvrCandidate();
        });
        return read(clientUuid);
    }

    /**
     * Re-opens one question and answers it now. CVR and sector answer within seconds and
     * run inline; a logo can take a minute of image generation and runs on the executor,
     * so the caller sees {@code PENDING} and polls.
     */
    @ActivateRequestContext
    public ClientEnrichmentDTO retry(String clientUuid, Job job) {
        requireEnabled();
        if (job == Job.CVR) requireRegistryAllowed();
        QuarkusTransaction.requiringNew().run(() -> {
            ClientEnrichment row = repository.ensure(clientUuid);
            if (row == null) throw new WebApplicationException("Client not found", 404);
            switch (job) {
                case CVR -> { row.setCvr(CvrEnrichmentStatus.PENDING); row.setCvrError(null); row.clearCvrCandidate(); }
                case LOGO -> { row.setLogo(LogoEnrichmentStatus.PENDING); row.setLogoError(null); }
                case SECTOR -> { row.setSector(SectorEnrichmentStatus.PENDING); }
            }
        });
        switch (job) {
            case CVR -> {
                try {
                    cvrService.verify(clientUuid);
                } catch (CvrEnrichmentService.QuotaExhausted e) {
                    throw new WebApplicationException("The CVR registry refused (" + e.getMessage() + ") — try again later", 429);
                }
            }
            case SECTOR -> sectorService.verify(clientUuid);
            case LOGO -> executor.submit(() -> withRequestContext(() -> {
                try {
                    logoService.enrich(clientUuid);
                } catch (RuntimeException e) {
                    log.errorf(e, "Logo retry failed for client=%s", clientUuid);
                }
            }));
        }
        return read(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Hooks from ClientResource
    // ------------------------------------------------------------------------

    /** After a create has committed. A client filed under OTHER gets its sector checked now. */
    public void onClientCreated(Client created) {
        if (created == null || created.getUuid() == null) return;
        try {
            QuarkusTransaction.requiringNew().run(() -> repository.ensure(created.getUuid()));
            if (isOther(created.getSegment())) publishSectorCheck(created.getUuid());
        } catch (RuntimeException e) {
            log.warnf(e, "Enrichment hook after client create failed for %s — the nightly job will catch up", created.getUuid());
        }
    }

    /** After an edit has committed. Writes down what the person decided, then triggers what is due. */
    public void onClientUpdated(Client before, Client after) {
        if (after == null || after.getUuid() == null) return;
        try {
            boolean checkSectorNow = QuarkusTransaction.requiringNew().call(() -> applyEdit(before, after));
            if (checkSectorNow) publishSectorCheck(after.getUuid());
        } catch (RuntimeException e) {
            log.warnf(e, "Enrichment hook after client update failed for %s — the nightly job will catch up", after.getUuid());
        }
    }

    /**
     * The edit rules, on the row, inside the caller's transaction. Returns whether a sector
     * check should be published. Package-visible so the fast tier can pin the rules on a
     * row object without a database.
     */
    boolean applyEdit(Client before, Client after) {
        ClientEnrichment row = repository.ensure(after.getUuid());
        if (row == null) return false;
        return applyEdit(row, before, after);
    }

    static boolean applyEdit(ClientEnrichment row, Client before, Client after) {
        boolean checkSector = false;

        // A changed CVR re-opens the CVR question — including a CVR typed onto a NOT_FOUND
        // row, and a CVR removed from a VERIFIED one.
        String cvrBefore = before == null ? null : blankToNull(before.getCvr());
        String cvrAfter = blankToNull(after.getCvr());
        if (!Objects.equals(cvrBefore, cvrAfter)) {
            row.setCvr(CvrEnrichmentStatus.PENDING);
            row.setCvrError(null);
            row.clearCvrCandidate();
        }

        ClientSegment segBefore = before == null ? null : before.getSegment();
        ClientSegment segAfter = after.getSegment();
        boolean afterOther = isOther(segAfter);
        boolean beforeOther = before == null || isOther(segBefore);
        SectorEnrichmentStatus status = row.sectorStatus();

        if (!afterOther) {
            // A person chose a sector (or the AI's choice was left standing). Either way the
            // question is closed; a person's choice is HUMAN_SET, the AI's stays CHANGED.
            if (segBefore != segAfter || status == SectorEnrichmentStatus.PENDING) {
                row.setSector(status == SectorEnrichmentStatus.CHANGED && segBefore == segAfter
                        ? SectorEnrichmentStatus.CHANGED
                        : SectorEnrichmentStatus.HUMAN_SET);
            }
        } else if (!beforeOther) {
            // Back to OTHER from a real sector. If the AI had set that sector, the person is
            // overruling it, and the row must never be re-checked. Otherwise the person is
            // unsure, which is exactly what the check is for.
            if (status == SectorEnrichmentStatus.CHANGED) {
                row.setSector(SectorEnrichmentStatus.HUMAN_OVERRIDE);
            } else {
                row.setSector(SectorEnrichmentStatus.PENDING);
                checkSector = true;
            }
        } else {
            // OTHER before and after. A name, CVR or industry change makes an old verdict stale.
            boolean identityChanged = before == null
                    || !Objects.equals(blankToNull(before.getName()), blankToNull(after.getName()))
                    || !Objects.equals(cvrBefore, cvrAfter)
                    || !Objects.equals(blankToNull(before.getIndustryDesc()), blankToNull(after.getIndustryDesc()));
            if (status == SectorEnrichmentStatus.PENDING) {
                checkSector = true;
            } else if (identityChanged && (status == SectorEnrichmentStatus.CONFIRMED_OTHER || status == SectorEnrichmentStatus.FAILED)) {
                row.setSector(SectorEnrichmentStatus.PENDING);
                checkSector = true;
            }
        }
        return checkSector;
    }

    static boolean isOther(ClientSegment segment) {
        return segment == null || segment == ClientSegment.OTHER;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Publishes after the enclosing transaction commits, or at once when there is none. */
    private void publishSectorCheck(String clientUuid) {
        if (!enabled()) return;
        try {
            if (txSyncRegistry.getTransactionStatus() == Status.STATUS_ACTIVE) {
                txSyncRegistry.registerInterposedSynchronization(new Synchronization() {
                    @Override
                    public void beforeCompletion() {
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status == Status.STATUS_COMMITTED) eventBus.publish(SECTOR_VERIFY_ADDRESS, clientUuid);
                    }
                });
                return;
            }
        } catch (RuntimeException e) {
            // No transaction context on this thread — publish now.
        }
        eventBus.publish(SECTOR_VERIFY_ADDRESS, clientUuid);
    }

    // ------------------------------------------------------------------------
    // The runner
    // ------------------------------------------------------------------------

    /** What the consumer runs. Gated on the switches; the environment gate is the cron's alone. */
    public void verifySectorNow(String clientUuid) {
        withRequestContext(() -> {
            if (!enabled()) return;
            try {
                sectorService.verify(clientUuid);
            } catch (RuntimeException e) {
                log.errorf(e, "Sector check failed for client=%s", clientUuid);
            }
        });
    }

    /** The nightly pass. Returns false when a switch or the gate kept it from starting. */
    public boolean runNightly() {
        if (!config.enabled()) {
            log.debug("Client enrichment skipped: dk.trustworks.crm.enrichment.enabled=false");
            return false;
        }
        if (!config.nightlyAllowedHere()) {
            log.infof("Client enrichment nightly pass skipped: environment is %s", config.environmentId());
            return false;
        }
        if (!featureFlag.isEnabled()) {
            log.info("Client enrichment skipped: app_settings crm.enrichment.enabled is off");
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("Client enrichment nightly pass skipped: a run is already in progress");
            return false;
        }
        try {
            withRequestContext(() -> run(EnumSet.allOf(Job.class), "nightly"));
        } finally {
            running.set(false);
        }
        return true;
    }

    /**
     * The manual trigger: submits the run and answers whether it started. 409-shaped
     * {@code false} when a run holds the lock; a switch that is off throws so the caller can
     * say which.
     */
    public boolean runManual(Set<Job> requested, String actor) {
        requireEnabled();
        // Staging never calls the registry. A request naming only CVR is refused so the
        // caller learns why; one naming several jobs runs the others.
        Set<Job> jobs = EnumSet.copyOf(requested);
        if (!config.registryCallsAllowedHere() && jobs.remove(Job.CVR)) {
            log.infof("CVR pass dropped from the manual run: environment %s must not call the CVR registry", config.environmentId());
            if (jobs.isEmpty()) requireRegistryAllowed();
        }
        if (!running.compareAndSet(false, true)) return false;
        try {
            executor.submit(() -> {
                try {
                    withRequestContext(() -> run(jobs, "manual by " + actor));
                } catch (RuntimeException e) {
                    log.errorf(e, "Client enrichment manual run failed");
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    /**
     * Runs {@code work} inside a CDI request context, activating one when the thread has
     * none — the executor's and the scheduler's threads.
     *
     * <p>Programmatic rather than {@code @ActivateRequestContext}, because the run is
     * invoked from inside this very bean (the executor lambda, the cron entry) and an
     * interceptor binding on a self-invoked method does not fire. The first staging
     * rehearsal (2026-09-14) died on exactly that: {@code ContextNotActiveException} from
     * the first queue read. The request context is what lets Hibernate answer a read
     * without a transaction and what gives {@code RequestHeaderHolder} a scope, so the
     * activity log can attribute the job's writes to {@code system}.
     */
    static void withRequestContext(Runnable work) {
        ManagedContext requestContext = Arc.container().requestContext();
        // On the run's own thread nothing is active and a fresh context is opened here. On a
        // scheduler or Vert.x worker thread that already holds a real one, it is used as is.
        // A PROPAGATED context — the ManagedExecutor case above — must never reach this
        // method: it reads as active and then dies underneath the run.
        if (requestContext.isActive()) {
            work.run();
            return;
        }
        requestContext.activate();
        try {
            work.run();
        } finally {
            requestContext.terminate();
        }
    }

    void run(Set<Job> jobs, String trigger) {
        log.infof("Client enrichment run starting (%s): jobs=%s caps cvr=%d logo=%d sector=%d",
                trigger, jobs, config.cvrNightlyCap(), config.logoNightlyCap(), config.sectorNightlyCap());
        int seeded = repository.seedMissingRows();
        if (seeded > 0) log.infof("Client enrichment seeded %d client rows", seeded);
        LocalDateTime now = LocalDateTime.now();
        if (jobs.contains(Job.CVR)) {
            if (config.registryCallsAllowedHere()) {
                runCvr(now);
            } else {
                log.infof("CVR pass skipped: environment %s must not call the CVR registry (the key and its daily quota are production's)",
                        config.environmentId());
            }
        }
        if (jobs.contains(Job.SECTOR)) runSector(now);
        if (jobs.contains(Job.LOGO)) runLogo(now);
        log.infof("Client enrichment run finished (%s)", trigger);
    }

    private void runCvr(LocalDateTime now) {
        List<String> queue = repository.eligibleForCvr(config.cvrNightlyCap(), now.minusDays(config.cvrRetryAfterDays()));
        int done = 0, failed = 0, stopped = 0;
        for (String uuid : queue) {
            if (shutdown.isShuttingDown()) break;
            try {
                Optional<CvrEnrichmentStatus> status = cvrService.verify(uuid);
                if (status.isPresent() && status.get() == CvrEnrichmentStatus.FAILED) failed++; else done++;
            } catch (CvrEnrichmentService.QuotaExhausted e) {
                // The rows not reached stay PENDING and are first in tomorrow's queue.
                stopped = queue.size() - done - failed;
                log.warnf("CVR pass stopped with %d of %d left: %s", stopped, queue.size(), e.getMessage());
                break;
            } catch (RuntimeException e) {
                failed++;
                log.errorf(e, "CVR check crashed for client=%s", uuid);
            }
            // A client costs up to two registry calls (name search, then lookup). The second
            // rehearsal fired ~20 calls in two minutes and the registry answered 401 to every
            // call after that; a pause between clients keeps the pass under a burst limit.
            pause(config.cvrPacingMs());
        }
        log.infof("CVR pass finished: queued=%d done=%d failed=%d stopped=%d", queue.size(), done, failed, stopped);
    }

    private static void pause(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runSector(LocalDateTime now) {
        List<String> queue = repository.eligibleForSector(config.sectorNightlyCap(), now.minusDays(config.sectorRetryAfterDays()));
        int done = 0, failed = 0, consecutiveFailures = 0;
        for (String uuid : queue) {
            if (shutdown.isShuttingDown()) break;
            try {
                Optional<SectorEnrichmentStatus> status = sectorService.verify(uuid);
                if (status.isPresent() && status.get() == SectorEnrichmentStatus.FAILED) {
                    failed++;
                    consecutiveFailures++;
                } else {
                    done++;
                    consecutiveFailures = 0;
                }
            } catch (RuntimeException e) {
                failed++;
                consecutiveFailures++;
                log.errorf(e, "Sector check crashed for client=%s", uuid);
            }
            if (consecutiveFailures >= 3) {
                log.warn("Sector pass stopped: three consecutive failures — the model is probably unavailable");
                break;
            }
        }
        log.infof("Sector pass finished: queued=%d done=%d failed=%d", queue.size(), done, failed);
    }

    private void runLogo(LocalDateTime now) {
        List<String> queue = repository.eligibleForLogo(config.logoNightlyCap(), now.minusDays(config.logoRetryAfterDays()));
        int done = 0, failed = 0, consecutiveFailures = 0;
        for (String uuid : queue) {
            if (shutdown.isShuttingDown()) break;
            try {
                Optional<LogoEnrichmentStatus> status = logoService.enrich(uuid);
                if (status.isPresent() && status.get() == LogoEnrichmentStatus.FAILED) {
                    failed++;
                    consecutiveFailures++;
                } else {
                    done++;
                    consecutiveFailures = 0;
                }
            } catch (RuntimeException e) {
                failed++;
                consecutiveFailures++;
                log.errorf(e, "Logo hunt crashed for client=%s", uuid);
            }
            if (consecutiveFailures >= 3) {
                log.warn("Logo pass stopped: three consecutive failures — the model is probably unavailable");
                break;
            }
        }
        log.infof("Logo pass finished: queued=%d done=%d failed=%d", queue.size(), done, failed);
    }

    // ------------------------------------------------------------------------
    // Switches
    // ------------------------------------------------------------------------

    private boolean enabled() {
        return config.enabled() && featureFlag.isEnabled();
    }

    private void requireEnabled() {
        if (!config.enabled()) throw new WebApplicationException("Client enrichment is disabled on this environment", 412);
        if (!featureFlag.isEnabled()) throw new WebApplicationException("Client enrichment is switched off (crm.enrichment.enabled)", 412);
    }

    private void requireRegistryAllowed() {
        if (!config.registryCallsAllowedHere()) {
            throw new WebApplicationException("The CVR registry is not called from " + config.environmentId()
                    + " — its key and daily quota are production's", 412);
        }
    }

    public static Optional<Job> parseJob(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(Job.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
