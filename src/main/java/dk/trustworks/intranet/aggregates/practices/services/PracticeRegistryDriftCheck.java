package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Startup drift assertion — the MVP's key safety mechanism (spec §3.3).
 *
 * <p>The practice registry ({@code SELECT code FROM practice WHERE type='PRACTICE'
 * AND active=1}) is the authoritative "core" practice set. The revenue/utilization
 * engine still hardcodes that set in a handful of constants for Part 1. This check
 * compares the two on every boot so the registry and the code can no longer diverge
 * silently: any mismatch logs at ERROR with the exact per-set diff and (if a Slack
 * ops channel is configured) posts one alert.
 *
 * <p><b>Branch note:</b> Only {@link UtilizationCalculationHelper#BILLABLE_PRACTICES}
 * exists in this checkout. The spec also names
 * {@code PracticeRevenueAllocationService.CORE_PRACTICES},
 * {@code CxoPracticeContributionService.CORE} and
 * {@code CxoPracticeStaffingService.PRACTICES}; those classes are not present on this
 * branch, so they are not wired in here. When the {@code aggregates/practices} engine
 * lands, add them to {@link #hardcodedSets()} — the diff plumbing is set-agnostic.
 *
 * <p>Defensive: if the {@code practice} table is missing (e.g. this boots before V418
 * applies), the check logs WARN and skips rather than failing startup.
 */
@JBossLog
@ApplicationScoped
public class PracticeRegistryDriftCheck {

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @Inject
    ManagedExecutor managedExecutor;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    /** One-shot startup probe on a worker thread (mirrors IntercompanyClassificationCheck). */
    void onStart(@Observes StartupEvent ev) {
        log.info("PracticeRegistryDriftCheck: scheduling one-shot registry drift check on worker thread");
        managedExecutor.submit(this::runOnce);
    }

    void runOnce() {
        try {
            Set<String> registryCore = loadRegistryCore();
            if (registryCore == null) {
                log.warn("PracticeRegistryDriftCheck: practice table unavailable — skipping drift check");
                return;
            }

            List<SetDrift> drifts = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : hardcodedSets().entrySet()) {
                SetDrift drift = diff(entry.getKey(), registryCore, entry.getValue());
                if (drift.hasDrift()) drifts.add(drift);
            }

            if (drifts.isEmpty()) {
                log.infof("PracticeRegistryDriftCheck: registry core %s matches all %d hardcoded set(s) — no drift",
                        registryCore, hardcodedSets().size());
                return;
            }

            for (SetDrift drift : drifts) {
                log.errorf("practice-registry-drift: set=%s registryOnly=%s hardcodedOnly=%s (registryCore=%s)",
                        drift.setName(), drift.registryOnly(), drift.hardcodedOnly(), registryCore);
            }
            fireSlackAlert(registryCore, drifts);
        } catch (Exception e) {
            log.errorf(e, "PracticeRegistryDriftCheck failed unexpectedly");
        }
    }

    /**
     * Active core PRACTICE codes from the registry, or {@code null} if the table is
     * missing/unreadable (defensive — do not fail startup).
     *
     * <p>REQUIRED, not SUPPORTS: on the startup worker thread there is no surrounding
     * transaction, and executing the native query without one intermittently fails
     * with "ResultSet is closed" while other startup jobs churn the connection pool.
     */
    @Transactional(Transactional.TxType.REQUIRED)
    Set<String> loadRegistryCore() {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                            "SELECT code FROM practice WHERE type = 'PRACTICE' AND active = 1")
                    .getResultList();
            Set<String> codes = new LinkedHashSet<>(rows.size());
            for (Object row : rows) {
                if (row != null) codes.add(String.valueOf(row));
            }
            return codes;
        } catch (RuntimeException ex) {
            log.warnf(ex, "PracticeRegistryDriftCheck: registry read failed — treating as unavailable");
            return null;
        }
    }

    /** The hardcoded core-practice sets to reconcile against the registry. */
    static Map<String, Set<String>> hardcodedSets() {
        Map<String, Set<String>> sets = new LinkedHashMap<>();
        sets.put("UtilizationCalculationHelper.BILLABLE_PRACTICES", UtilizationCalculationHelper.BILLABLE_PRACTICES);
        return sets;
    }

    /**
     * Diffs a hardcoded set against the registry core. {@code registryOnly} are codes
     * the registry has that the hardcoded set is missing; {@code hardcodedOnly} are
     * codes the hardcoded set has that the registry does not. Sorted for stable output.
     */
    static SetDrift diff(String setName, Set<String> registryCore, Set<String> hardcoded) {
        Set<String> registryOnly = new TreeSet<>(registryCore);
        registryOnly.removeAll(hardcoded);
        Set<String> hardcodedOnly = new TreeSet<>(hardcoded);
        hardcodedOnly.removeAll(registryCore);
        return new SetDrift(setName, registryOnly, hardcodedOnly);
    }

    private void fireSlackAlert(Set<String> registryCore, List<SetDrift> drifts) {
        StringBuilder msg = new StringBuilder(":warning: *Practice registry drift detected* — ")
                .append(drifts.size())
                .append(" hardcoded set(s) diverge from the registry core ")
                .append(registryCore)
                .append(":\n");
        for (SetDrift drift : drifts) {
            msg.append(String.format("• %s: registryOnly=%s, hardcodedOnly=%s%n",
                    drift.setName(), drift.registryOnly(), drift.hardcodedOnly()));
        }
        msg.append("Action: reconcile the hardcoded core-practice set(s) with the practice registry ")
                .append("(SELECT code FROM practice WHERE type='PRACTICE' AND active=1).");
        slackService.sendMessage(opsAlertChannel, msg.toString(), "mother");
    }

    /** A per-set diff carried from {@link #diff} to the log/Slack output. */
    record SetDrift(String setName, Set<String> registryOnly, Set<String> hardcodedOnly) {
        boolean hasDrift() {
            return !registryOnly.isEmpty() || !hardcodedOnly.isEmpty();
        }
    }
}
