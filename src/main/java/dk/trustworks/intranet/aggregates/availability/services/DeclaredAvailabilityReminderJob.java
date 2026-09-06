package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.config.DeclaredAvailabilityPolicy;
import dk.trustworks.intranet.aggregates.availability.model.DeclaredAvailabilityReminderClaim;
import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability;
import dk.trustworks.intranet.aggregates.availability.services.DeclaredAvailabilityReminderSelector.Due;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Monday 08:00: one Slack DM per junior whose declared horizon ends within
 * {@value #HORIZON_DAYS} days (spec §4.1.3). The coverage strip on the Team Dashboard lists
 * the same people for the leads, keyed on the same number.
 *
 * <p>Ships dark ({@code feature.declared-availability.reminder-enabled}, default false):
 * employee DMs cannot be exercised on staging, whose {@code slackusername} column is nulled
 * by the prod→staging sync while its Slack token is production's.
 *
 * <p>Belted with {@link SchedulerShutdownGuard} (build-enforced by
 * {@code SchedulerShutdownGuardCoverageTest}); cross-task safety comes from the per-person,
 * per-week claim row, exactly as {@code ExpenseEmployeeNotifier} does it.
 */
@JBossLog
@ApplicationScoped
public class DeclaredAvailabilityReminderJob {

    public static final int HORIZON_DAYS = 14;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy");

    @Inject
    DeclaredAvailabilityPolicy policy;

    @Inject
    UserService userService;

    @Inject
    SlackService slackService;

    /** Same base URL every outbound Slack deep link is built from. */
    @ConfigProperty(name = "quarkus.application.base-url")
    String applicationBaseUrl;

    /** What one sweep did. */
    public record SweepSummary(int candidates, int due, int sent, int failures) {}

    @Scheduled(cron = "0 0 8 ? * MON",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void weeklyReminder() {
        if (!policy.reminderEnabled()) {
            log.debug("declared-availability reminder skipped: disabled");
            return;
        }
        try {
            SweepSummary summary = runSweep(LocalDate.now());
            log.infof("declared-availability reminder: %d juniors, %d due, %d sent, %d failures",
                    summary.candidates(), summary.due(), summary.sent(), summary.failures());
        } catch (Exception e) {
            log.error("declared-availability reminder sweep failed", e);
        }
    }

    /** One pass, a function of its argument so it is reproducible and assertable. */
    public SweepSummary runSweep(LocalDate today) {
        // Working juniors only: someone on leave is not asked to plan their weeks.
        List<User> juniors = QuarkusTransaction.requiringNew().call(
                () -> userService.findWorkingUsersByDate(today, ConsultantType.STUDENT));
        Map<String, User> byUuid = new LinkedHashMap<>();
        Map<String, LocalDate> horizons = new LinkedHashMap<>();
        QuarkusTransaction.requiringNew().run(() -> {
            for (User junior : juniors) {
                byUuid.put(junior.getUuid(), junior);
                horizons.put(junior.getUuid(),
                        UserDeclaredAvailability.findHorizon(junior.getUuid(), today).orElse(null));
            }
        });

        List<Due> due = DeclaredAvailabilityReminderSelector.selectDue(today, horizons, HORIZON_DAYS);
        String weekKey = weekKeyOf(today);
        int sent = 0;
        int failures = 0;
        for (Due item : due) {
            User user = byUuid.get(item.useruuid());
            if (user == null || user.getSlackusername() == null || user.getSlackusername().isBlank()) {
                continue;
            }
            if (!claim(item.useruuid(), weekKey)) {
                continue;
            }
            try {
                slackService.sendMessage(user, reminderText(user, item));
                sent++;
            } catch (Exception e) {
                failures++;
                log.warnf(e, "declared-availability reminder: DM to %s failed", user.getUsername());
            }
        }
        log.infof("declared-availability reminder %s: %d juniors, %d due, %d sent, %d failures",
                weekKey, juniors.size(), due.size(), sent, failures);
        return new SweepSummary(juniors.size(), due.size(), sent, failures);
    }

    /** Copy is English (UI chrome); the day is the person's own horizon. */
    String reminderText(User user, Due item) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(user.getFirstname()).append(" :wave: ");
        if (item.horizon() == null) {
            sb.append("You have no declared working days ahead on the intranet, so you currently count as unavailable.");
        } else {
            sb.append("Your declared availability on the intranet runs out on ")
                    .append(DAY_FORMAT.format(item.horizon()))
                    .append(" (").append(item.daysLeft()).append(" days from now).");
        }
        sb.append(" Please declare the coming weeks on your profile");
        String link = profileAvailabilityUrl();
        if (link != null) {
            sb.append(": ").append(link);
        } else {
            sb.append(" (Profile → Availability).");
        }
        return sb.toString();
    }

    /** {@code /profile?tab=availability} — the declaration grid. */
    String profileAvailabilityUrl() {
        if (applicationBaseUrl == null || applicationBaseUrl.isBlank()) {
            log.warn("declared-availability reminder: no application base URL configured — link omitted");
            return null;
        }
        String base = applicationBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/profile?tab=availability";
    }

    boolean claim(String useruuid, String weekKey) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                DeclaredAvailabilityReminderClaim row = new DeclaredAvailabilityReminderClaim();
                row.uuid = UUID.randomUUID().toString();
                row.useruuid = useruuid;
                row.weekKey = weekKey;
                row.claimedAt = LocalDateTime.now();
                row.persistAndFlush();
            });
            return true;
        } catch (Exception e) {
            // A duplicate key means another task already sent this person's reminder this week.
            log.debugf("declared-availability reminder: %s already claimed in %s (%s)", useruuid, weekKey, e.getMessage());
            return false;
        }
    }

    /** ISO week key of a run, e.g. {@code 2026-W36}. */
    static String weekKeyOf(LocalDate day) {
        int week = day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = day.get(IsoFields.WEEK_BASED_YEAR);
        return String.format("%d-W%02d", year, week);
    }
}
