package dk.trustworks.intranet.aggregates.accounting.services;

import dk.trustworks.intranet.aggregates.accounting.dto.HourlyAbsenceDTO;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.SICKNESS;

/**
 * The sick-hours payroll worksheet (JK Team 2.0 WP2, spec §4.2.3).
 *
 * <p>Hourly pay reads exactly one task ({@code Studentermedhjælper}); sick hours registered on
 * the sickness task reach payroll only because HR keys them into Danløn. This is the input
 * that manual step never had: per month, per hourly-paid employee, the sick hours, the days
 * they fall on and the DKK value at that person's hourly rate.
 *
 * <p>Nothing here writes. In particular {@code paid_out} is never stamped on sick rows —
 * that column means "sent to Danløn by the salary export", and the vacation ledger's
 * reconciliation cut keys on it.
 */
@JBossLog
@ApplicationScoped
public class HourlyAbsenceService {

    private static final String[] EMPLOYED_STATUSES = {
            StatusType.ACTIVE.toString(),
            StatusType.PAID_LEAVE.toString(),
            StatusType.MATERNITY_LEAVE.toString(),
            StatusType.NON_PAY_LEAVE.toString(),
    };

    @Inject
    UserService userService;

    @Inject
    WorkService workService;

    /**
     * @param month       any day in the month
     * @param companyuuid restrict to one company (the salary page is company-scoped), or
     *                    {@code null} for every company
     */
    public List<HourlyAbsenceDTO> forMonth(LocalDate month, String companyuuid) {
        LocalDate start = month.withDayOfMonth(1);
        LocalDate end = start.plusMonths(1);
        LocalDate endOfMonth = end.minusDays(1);

        String[] types = Arrays.stream(ConsultantType.values()).map(Enum::toString).toArray(String[]::new);
        List<User> employed = userService.findUsersByDateAndStatusListAndTypes(endOfMonth, EMPLOYED_STATUSES, types, false);

        List<HourlyAbsenceDTO> out = new ArrayList<>();
        for (User user : employed) {
            Salary salary = user.getSalary(endOfMonth);
            if (salary == null || salary.getType() != SalaryType.HOURLY) {
                continue;
            }
            UserStatus status = user.getUserStatus(endOfMonth);
            String userCompany = status == null || status.getCompany() == null ? null : status.getCompany().getUuid();
            if (companyuuid != null && !companyuuid.equalsIgnoreCase(userCompany)) {
                continue;
            }
            List<WorkFull> sick = workService.findByPeriodAndUserAndTasks(start, end, user.getUuid(), SICKNESS);
            out.add(summarise(user.getUuid(), user.getFirstname() + " " + user.getLastname(), userCompany,
                    salary.getSalary(), sick));
        }
        out.sort(Comparator.comparing(HourlyAbsenceDTO::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Pure aggregation: hours per day (several rows on one day are summed), total and value. */
    public static HourlyAbsenceDTO summarise(String useruuid, String name, String companyuuid, int hourlyRate,
                                             List<WorkFull> sickRows) {
        TreeMap<LocalDate, Double> byDay = new TreeMap<>();
        for (WorkFull row : sickRows) {
            if (row.getRegistered() == null || row.getWorkduration() <= 0) continue;
            byDay.merge(row.getRegistered(), row.getWorkduration(), Double::sum);
        }
        List<HourlyAbsenceDTO.Day> days = new ArrayList<>(byDay.size());
        double total = 0.0;
        for (var entry : byDay.entrySet()) {
            double hours = Math.round(entry.getValue() * 100) / 100.0;
            days.add(new HourlyAbsenceDTO.Day(entry.getKey(), hours));
            total += hours;
        }
        total = Math.round(total * 100) / 100.0;
        double value = Math.round(total * hourlyRate * 100) / 100.0;
        return new HourlyAbsenceDTO(useruuid, name, companyuuid, hourlyRate, total, days, value);
    }
}
