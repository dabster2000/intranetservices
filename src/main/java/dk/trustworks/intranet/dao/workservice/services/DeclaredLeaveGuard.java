package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.aggregates.availability.config.DeclaredAvailabilityPolicy;
import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.SICKNESS;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;

/**
 * The server-side bound on a leave registration for the declaring population
 * (JK Team 2.0 WP2, spec §4.2.2): on a day where the person is declaring
 * ({@code STUDENT ∧ day ≥ floor ∧ LIVE}), vacation or sickness hours may not exceed the
 * day's capacity. The client-side rule is convenience; this refusal is the control.
 *
 * <p>Deliberately <em>not</em> in {@code TimesheetWorkValidationService}: that is the
 * Phase-4 agreement-rule engine, resolves a client contract per row and skips internal
 * tasks — which is exactly what the leave tasks are. This guard sits in the work save path
 * instead and is inert in {@code SHADOW} mode, where a junior's 7.4 h sick day must not be
 * rejected against a 3 h phantom day. Full-timers keep their client-only 3.7 / 7.4 rule.
 *
 * <p>The bound is the declaration itself when one exists (the source the resolver writes
 * {@code fact_user_day.gross_available_hours} from — reading it directly avoids racing the
 * asynchronous recalculation), else the fact row's gross hours, which for an undeclared
 * live day is 0.
 */
@JBossLog
@ApplicationScoped
public class DeclaredLeaveGuard {

    @Inject
    DeclaredAvailabilityPolicy policy;

    @Inject
    UserService userService;

    /** Throws a structured 400 when {@code work} is a leave row above the declared day's capacity. */
    public void enforce(Work work) {
        if (work == null || work.getTaskuuid() == null || work.getRegistered() == null || work.getUseruuid() == null) {
            return;
        }
        if (!isLeaveTask(work.getTaskuuid())) {
            return;
        }
        // SHADOW: nothing to enforce, and no lookups paid for.
        if (!policy.isLive()) {
            return;
        }
        LocalDate day = work.getRegistered();
        User user = User.findById(work.getUseruuid());
        if (user == null) {
            return;
        }
        user.setStatuses(userService.findUserStatuses(user.getUuid()));
        UserStatus status = user.getUserStatus(day);
        if (status == null || !policy.isDeclaring(status.getType(), day)) {
            return;
        }
        BigDecimal capacity = capacityFor(user, day);
        String problem = check(true, work.getTaskuuid(), work.getWorkduration(), capacity);
        if (problem != null) {
            log.infof("Leave registration refused: user=%s day=%s task=%s hours=%.2f capacity=%s",
                    user.getUuid(), day, work.getTaskuuid(), work.getWorkduration(), capacity);
            throw new WebApplicationException(problem, Response.Status.BAD_REQUEST);
        }
    }

    /** The declaration when one exists, else the fact row's gross hours (0 when neither). */
    BigDecimal capacityFor(User user, LocalDate day) {
        Optional<BigDecimal> declared = UserDeclaredAvailability.findForDay(user.getUuid(), day)
                .map(UserDeclaredAvailability::getHours);
        if (declared.isPresent()) {
            return declared.get();
        }
        return BiDataPerDay.<BiDataPerDay>find("documentDate = ?1 and user = ?2", day, user)
                .firstResultOptional()
                .map(row -> row.grossAvailableHours == null ? BigDecimal.ZERO : row.grossAvailableHours)
                .orElse(BigDecimal.ZERO);
    }

    public static boolean isLeaveTask(String taskuuid) {
        return VACATION.equals(taskuuid) || SICKNESS.equals(taskuuid);
    }

    /**
     * Pure rule. {@code null} when the registration is allowed, otherwise the message the
     * timesheet shows — naming the actual limit and, for an undeclared day, telling the
     * junior to declare the day first.
     */
    public static String check(boolean declaring, String taskuuid, double hours, BigDecimal capacity) {
        if (!declaring || !isLeaveTask(taskuuid) || hours <= 0) {
            return null;
        }
        double cap = capacity == null ? 0.0 : capacity.doubleValue();
        String kind = SICKNESS.equals(taskuuid) ? "Sick leave" : "Vacation";
        if (cap <= 0) {
            return "This day has no declared hours. Declare the day on your profile first (Profile → Availability), then register the absence.";
        }
        if (hours > cap + 1e-9) {
            return kind + " cannot exceed your declared " + formatHours(cap) + "-hour day.";
        }
        return null;
    }

    private static String formatHours(double hours) {
        if (hours == Math.rint(hours)) {
            return String.valueOf((long) hours);
        }
        return String.valueOf(Math.round(hours * 100) / 100.0).replace('.', ',');
    }
}
