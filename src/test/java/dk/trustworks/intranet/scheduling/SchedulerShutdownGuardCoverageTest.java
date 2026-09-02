package dk.trustworks.intranet.scheduling;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The gate that stops this bug coming back.
 * <p>
 * Every {@code @Scheduled} method that runs during a deploy drain and touches
 * the database dies on a closed {@code EntityManagerFactory} unless it is
 * covered by {@link SchedulerShutdownGuard}. Three such jobs were caught in
 * production on 2026-08-24; they were not special, they were simply the ones
 * whose one-minute cadence happened to land inside the drain window.
 * <p>
 * Rather than try to compute "does this job touch the database?" — expensive,
 * imprecise, and wrong the moment a callee changes — this test inverts the
 * burden: <b>every</b> scheduled method must declare
 * {@code skipExecutionIf = SchedulerShutdownGuard.class}, and anything that
 * genuinely need not be guarded must say so out loud in {@link #ACCEPTED_GAPS}.
 * Skipping a non-database job during a drain is harmless, so "guarded" is the
 * safe default.
 * <p>
 * <b>Reading a failure.</b> If this test fails on a job you just added, add the
 * annotation — that is almost always the right fix. Add to
 * {@link #ACCEPTED_GAPS} only if the job provably touches no persistence unit,
 * and say why.
 * <p>
 * ArchUnit is used rather than a source scanner on purpose: 19 of the 80
 * textual {@code @Scheduled} occurrences under {@code src/main/java} are
 * javadoc or commented-out, and a regex would demand the annotation on dead
 * code. Bytecode sees only the live ones.
 */
class SchedulerShutdownGuardCoverageTest {

    private static final String SCHEDULED = "io.quarkus.scheduler.Scheduled";
    /** {@code @Scheduled} is {@code @Repeatable}; stacking two produces this container. */
    private static final String SCHEDULES = "io.quarkus.scheduler.Schedules";
    /** The {@code skipExecutionIf} default — i.e. "no predicate declared". */
    private static final String NEVER = "io.quarkus.scheduler.Scheduled$Never";

    private static final String GUARD = SchedulerShutdownGuard.class.getName();

    /**
     * Guards against a broken scanner. If a refactor, a bad import filter, or a
     * classpath change made this test discover nothing, it would pass silently
     * and the whole guarantee would evaporate — which is the exact failure mode
     * this gate exists to prevent. The live count was 62 when this was written;
     * the floor sits below that so ordinary churn does not cause false failures,
     * but far above the 0-or-3 a broken scan would return.
     */
    private static final int MINIMUM_EXPECTED_SCHEDULED_METHODS = 50;

    /**
     * Scheduled methods deliberately left unguarded, each with its reason.
     * <p>
     * Everything in the "not yet guarded" block below is pre-existing debt, not
     * a judgement that the job is safe: the fleet-wide pause in
     * {@link SchedulerShutdownGuard#onShutdown} already protects them, and the
     * per-job predicate is the belt that closes the last sub-second race. They
     * are listed so that any NEW scheduled method fails this test.
     */
    private static final Set<String> ACCEPTED_GAPS = new LinkedHashSet<>(Arrays.asList(
            // (1) Genuinely no persistence unit involved: reads Micrometer gauges only.
            "dk.trustworks.intranet.perf.InfraMetricsEmitter#emit",

            // (2) Owned by another session's in-flight change at the time this gate
            //     landed, so not annotated here to avoid a mid-air collision in a
            //     shared checkout. Both are database-touching and both SHOULD get the
            //     annotation — they are covered meanwhile by the fleet-wide pause.
            "dk.trustworks.intranet.perf.PerformanceDigestBatchlet#scheduledRun",
            "dk.trustworks.intranet.recruitmentservice.services.RecruitmentCalendarRepairJob#sweepTimer",

            // (3) Pre-existing debt, NOT a safety judgement. Every one of these is
            //     already protected by the fleet-wide pause in
            //     SchedulerShutdownGuard#onShutdown; what they lack is the per-job belt
            //     that closes the last sub-second race. They are enumerated so that any
            //     NEW @Scheduled method fails this test instead of slipping through.
            "dk.trustworks.intranet.aggregates.bidata.jobs.ChangeLogRetentionBatchlet#scheduledRun",
            "dk.trustworks.intranet.aggregates.bidata.jobs.FactChangeLogBacklogAlertBatchlet#scheduledRun",
            "dk.trustworks.intranet.aggregates.bonus.individual.jobs.MaterializeDueBonusPayoutsJob#materializeMonthlyPayouts",
            "dk.trustworks.intranet.aggregates.bonus.individual.jobs.ReconcileIndividualBonusMonthlyJob#reconcile",
            "dk.trustworks.intranet.aggregates.bugreport.jobs.BugReportCleanupJob#cleanup",
            "dk.trustworks.intranet.aggregates.bugreport.services.AutoFixTaskReaper#reapStaleTasks",
            "dk.trustworks.intranet.aggregates.consultant.jobs.ConsultantProfilePrewarmBatchlet#scheduledRun",
            "dk.trustworks.intranet.aggregates.finance.health.IntercompanyClassificationCheck#scheduledRun",
            "dk.trustworks.intranet.aggregates.finance.health.SalaryGLAnomalyCheck#scheduledRun",
            "dk.trustworks.intranet.aggregates.finance.health.SelfBilledDuplicateRevenueCheck#scheduledRun",
            "dk.trustworks.intranet.aggregates.finance.health.UnmappedGlAccountCheck#scheduledRun",
            "dk.trustworks.intranet.aggregates.finance.jobs.EconomicRevenueImportBatchlet#scheduledRun",
            "dk.trustworks.intranet.aggregates.finance.jobs.OpexDistributionRefreshBatchlet#scheduledRun",
            "dk.trustworks.intranet.aggregates.invoice.batch.PendingReviewCleanupBatchlet#run",
            "dk.trustworks.intranet.aggregates.users.danlon.DanlonReconciliationService#scheduledReconcile",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleBulkMailSend",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleCompetenceAttemptReaper",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleCompetenceDueNotifier",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleCvToolSync",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleEconomicsInvoiceStatusSync",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleEconomicsUploadRetry",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleEmployeeDocumentsRetention",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleExpenseConsume",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleExpenseOrphanDetection",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleExpenseSync",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleFinanceInvoiceSync",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleFinanceLoadEconomics",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleMailSend",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleNextSignStatusSync",
            "dk.trustworks.intranet.batch.BatchScheduler#schedulePracticeReconciliation",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleProjectLock",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleQueuedInternalInvoiceProcessor",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleRecruitmentDigest",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleRecruitmentEveBrief",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleRecruitmentEventCatchup",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleRecruitmentGdprSweep",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleRecruitmentMorningBrief",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleRecruitmentScorecardPrompt",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleRecruitmentSignatureCompletion",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleRecruitmentSlaSweep",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleSlackUserSync",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleTeamDescription",
            "dk.trustworks.intranet.batch.BatchScheduler#scheduleUserResumeUpdate",
            "dk.trustworks.intranet.batch.BatchScheduler#trigger",
            "dk.trustworks.intranet.expenseservice.events.ExpenseCreatedConsumer#expenseSyncJob",
            "dk.trustworks.intranet.expenseservice.health.ExpenseStaleFailureCheck#scheduledRun",
            "dk.trustworks.intranet.expenseservice.jobs.ExpenseStuckDetectionBatchlet#runNightly",
            "dk.trustworks.intranet.recruitmentservice.jobs.S3RetentionCleanupBatchlet#scheduledRun",
            "dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingHealthAlertJob#scheduledRun",
            "dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingMetricsJob#scheduledRun",
            "dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingOrchestrator#evidenceExpiryTimer",
            "dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingOrchestrator#reconcileTimer",
            "dk.trustworks.intranet.security.PermissionCatalogueVerifier#verify",
            "dk.trustworks.intranet.vacationservice.services.VacationAccrualJob#nightly"
    ));

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dk.trustworks.intranet");
    }

    @Test
    @DisplayName("every @Scheduled method is covered by SchedulerShutdownGuard")
    void every_scheduled_method_declares_the_shutdown_guard() {
        List<String> all = new ArrayList<>();
        Set<String> unguarded = new TreeSet<>();

        for (JavaClass clazz : classes) {
            for (JavaMethod method : clazz.getMethods()) {
                for (JavaAnnotation<?> scheduled : scheduledAnnotationsOf(method)) {
                    String key = key(method);
                    all.add(key);
                    if (!isGuarded(scheduled)) {
                        unguarded.add(key);
                    }
                }
            }
        }

        System.out.println("[shutdown-guard gate] live @Scheduled methods discovered: " + all.size()
                + " (guarded: " + (all.size() - unguarded.size())
                + ", accepted gaps: " + ACCEPTED_GAPS.size() + ")");

        assertTrue(all.size() >= MINIMUM_EXPECTED_SCHEDULED_METHODS,
                "Only " + all.size() + " @Scheduled methods were discovered, expected at least "
                        + MINIMUM_EXPECTED_SCHEDULED_METHODS + ". The scanner is probably broken — "
                        + "a gate that finds nothing passes vacuously and protects nothing.");

        unguarded.removeAll(ACCEPTED_GAPS);
        if (!unguarded.isEmpty()) {
            fail("These @Scheduled methods are not covered by " + SchedulerShutdownGuard.class.getSimpleName()
                    + ".\nAdd  skipExecutionIf = SchedulerShutdownGuard.class  to the @Scheduled annotation "
                    + "so a tick during a deploy drain is skipped instead of dying on a closed "
                    + "EntityManagerFactory.\nIf the job provably touches no persistence unit, add it to "
                    + "ACCEPTED_GAPS with a reason.\n\n"
                    + unguarded.stream().map(s -> "    \"" + s + "\",").collect(Collectors.joining("\n"))
                    + "\n");
        }
    }

    @Test
    @DisplayName("the guard stays a single unambiguous bean")
    void nothing_else_may_extend_the_guard() {
        // SchedulerUtils.instantiateBeanOrClass does Arc.container().select(type, Any)
        // and silently news up a throwaway instance when that is unsatisfied, or
        // throws when it is ambiguous. A subclass would break the fix invisibly:
        // the scheduler would consult an instance whose flag the observer never sets.
        List<String> subclasses = classes.stream()
                .filter(c -> !c.getName().equals(GUARD))
                .filter(c -> c.getAllRawSuperclasses().stream().anyMatch(s -> s.getName().equals(GUARD)))
                .map(JavaClass::getName)
                .collect(Collectors.toList());

        assertTrue(subclasses.isEmpty(),
                "Nothing may extend " + SchedulerShutdownGuard.class.getSimpleName()
                        + " — a second bean of that type makes the scheduler's Arc lookup ambiguous "
                        + "and breaks the shutdown guard silently. Found: " + subclasses);
    }

    // ---- helpers ----------------------------------------------------------

    /** Direct {@code @Scheduled}s plus any nested inside a {@code @Schedules} container. */
    private static List<JavaAnnotation<?>> scheduledAnnotationsOf(JavaMethod method) {
        List<JavaAnnotation<?>> found = new ArrayList<>();
        for (JavaAnnotation<?> annotation : method.getAnnotations()) {
            String type = annotation.getRawType().getName();
            if (SCHEDULED.equals(type)) {
                found.add(annotation);
            } else if (SCHEDULES.equals(type)) {
                annotation.get("value")
                        .filter(Object[].class::isInstance)
                        .map(Object[].class::cast)
                        .ifPresent(nested -> Arrays.stream(nested)
                                .filter(JavaAnnotation.class::isInstance)
                                .map(n -> (JavaAnnotation<?>) n)
                                .forEach(found::add));
            }
        }
        return found;
    }

    private static boolean isGuarded(JavaAnnotation<?> scheduled) {
        Optional<Object> declared = scheduled.get("skipExecutionIf");
        if (declared.isEmpty()) {
            return false; // no predicate at all
        }
        Object value = declared.get();
        String name = (value instanceof JavaClass) ? ((JavaClass) value).getName() : String.valueOf(value);
        return GUARD.equals(name) && !NEVER.equals(name);
    }

    private static String key(JavaMethod method) {
        return method.getOwner().getName() + "#" + method.getName();
    }
}
