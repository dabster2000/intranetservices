package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The single precedence cascade shared by StatusService and SalaryService
 * (spec §6). Returns the ONE most-specific Danløn event for a user/month/
 * company, so whichever side (status or salary write) triggers detection,
 * both converge on the same event → one proposal. Pure detection: reads
 * only; never mints.
 *
 * Precedence: COMPANY_TRANSITION > SALARY_TYPE_CHANGE > RE_EMPLOYMENT > FIRST_EMPLOYMENT.
 */
@JBossLog
@ApplicationScoped
public class DanlonEventDetector {

    @Transactional
    public Optional<DanlonEventType> detectMostSpecific(String useruuid, LocalDate month, String companyUuid) {
        LocalDate monthStart = month.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);

        // 1. COMPANY_TRANSITION (most specific): TERMINATED in a different company within this month.
        boolean terminatedElsewhere = UserStatus.count(
                "useruuid = ?1 and status = ?2 and company.uuid != ?3 and statusdate >= ?4 and statusdate <= ?5",
                useruuid, StatusType.TERMINATED, companyUuid, monthStart, monthEnd) > 0;
        if (terminatedElsewhere) return Optional.of(DanlonEventType.COMPANY_TRANSITION);

        // 2. SALARY_TYPE_CHANGE: salary effective this month is NORMAL and previous month was HOURLY.
        Salary current = salaryAsOf(useruuid, monthStart);
        Salary previous = salaryAsOf(useruuid, monthStart.minusMonths(1));
        if (current != null && current.getType() == SalaryType.NORMAL
                && previous != null && previous.getType() == SalaryType.HOURLY) {
            return Optional.of(DanlonEventType.SALARY_TYPE_CHANGE);
        }

        // 3. RE_EMPLOYMENT: any previous TERMINATED before this month.
        boolean previouslyTerminated = UserStatus.count(
                "useruuid = ?1 and status = ?2 and statusdate < ?3", useruuid, StatusType.TERMINATED, monthStart) > 0;
        if (previouslyTerminated) return Optional.of(DanlonEventType.RE_EMPLOYMENT);

        // 4. FIRST_EMPLOYMENT: brand-new employee with no Danløn history at all.
        if (!UserDanlonHistory.hasHistory(useruuid)) return Optional.of(DanlonEventType.FIRST_EMPLOYMENT);

        return Optional.empty();
    }

    private Salary salaryAsOf(String useruuid, LocalDate date) {
        return Salary.<Salary>find("useruuid = ?1 and activefrom <= ?2 order by activefrom desc", useruuid, date)
                .firstResult();
    }
}
