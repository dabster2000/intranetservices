package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.Vacation;
import dk.trustworks.intranet.userservice.model.VacationPool;
import dk.trustworks.intranet.userservice.model.enums.VacationType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.Month;
import java.util.*;

@JBossLog
@ApplicationScoped
public class VacationService {

    private final WorkService workService;
    private final UserService userService;

    @Inject
    public VacationService(WorkService workService, UserService userService) {
        this.workService = workService;
        this.userService = userService;
    }

    /**
     * Calculates remaining vacation days for a user in a given vacation year.
     *
     * @param useruuid the user's UUID
     * @return the number of remaining vacation days
     */
    @Transactional
    public VacationPool calculateRemainingVacationDays(String useruuid) {
        log.infof("Calculating remaining vacation days for user %s", useruuid);

        User user = userService.findById(useruuid, false);
        LocalDate hireDate = user.getHireDate().withDayOfMonth(1);
        log.info("hireDate = " + hireDate);

        // Define the start and end of the vacation year

        // Fetch all earned vacation entries for the user within the vacation year
        List<Vacation> earnedVacations = Vacation.find("useruuid", useruuid).list();
        log.info("earnedVacations = " + earnedVacations.stream().mapToDouble(Vacation::getVacationEarned).sum());

        VacationPool vacationPool = new VacationPool(hireDate);
        for (Vacation earnedVacation : earnedVacations) {
            vacationPool.addVacationEarned(earnedVacation);
        }

        List<Work> vacationByUser = workService.findWorkVacationByUser(useruuid).stream().filter(Work::isPaidOut).sorted(Comparator.comparing(Work::getRegistered)).toList();
        log.info("vacation used = " + vacationByUser.stream().mapToDouble(Work::getWorkduration).sum() / 7.4);

        for (Work work : vacationByUser) {
            vacationPool.addVacationUsed(work.getRegistered(), work.getWorkduration() / 7.4);
        }

        log.info("vacationPool = " + vacationPool);

        return vacationPool;
    }

    @Transactional
    public void transferVacationDays(String useruuid, int year, double days) {
        log.infof("Transferring vacation days for user %s", useruuid);
        new Vacation(UUID.randomUUID().toString(), useruuid, VacationType.MIGRATED, LocalDate.of(year, Month.SEPTEMBER, 1), -days).persist();
        new Vacation(UUID.randomUUID().toString(), useruuid, VacationType.MIGRATED, LocalDate.of(year + 1, Month.SEPTEMBER, 1), days).persist();
        log.info("Vacation days transferred successfully.");
    }

    public List<Map.Entry<LocalDate, LocalDate>> getActiveVacationPeriods() {
        LocalDate today = LocalDate.now();
        List<Map.Entry<LocalDate, LocalDate>> activePeriods = new ArrayList<>();

        // Calculate the current and past vacation years
        int currentYear = today.getYear();
        LocalDate currentYearStart = LocalDate.of(currentYear, 9, 1);
        LocalDate currentYearEnd = currentYearStart.plusMonths(16).minusDays(1);

        if (today.isBefore(currentYearStart)) {
            // If before 1st September, we are still in the previous vacation year
            currentYear -= 1;
            currentYearStart = LocalDate.of(currentYear, 9, 1);
            currentYearEnd = currentYearStart.plusMonths(16).minusDays(1);
        }

        // Add the current year's active vacation period
        if (!today.isAfter(currentYearEnd)) {
            activePeriods.add(Map.entry(currentYearStart, currentYearEnd));
        }

        // Add the previous year's active vacation period, if applicable
        LocalDate previousYearStart = LocalDate.of(currentYear - 1, 9, 1);
        LocalDate previousYearEnd = previousYearStart.plusMonths(16).minusDays(1);
        if (!today.isBefore(previousYearStart) && today.isBefore(previousYearEnd)) {
            activePeriods.add(Map.entry(previousYearStart, previousYearEnd));
        }

        return activePeriods;
    }

    public static void main(String[] args) {
        VacationService vacationService = new VacationService(null, null);
        List<Map.Entry<LocalDate, LocalDate>> activePeriods = vacationService.getActiveVacationPeriods();

        System.out.println("Active Vacation Periods:");
        activePeriods.forEach(period ->
                System.out.println("From " + period.getKey() + " to " + period.getValue()));
    }
}