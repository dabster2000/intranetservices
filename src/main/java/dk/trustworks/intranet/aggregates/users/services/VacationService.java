package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.userservice.model.Vacation;
import dk.trustworks.intranet.userservice.model.enums.VacationType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.UUID;

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
     * @param year     the vacation year to check
     * @return the number of remaining vacation days
     */
    @Transactional
    public double calculateRemainingVacationDays(String useruuid, int year) {
        log.infof("Calculating remaining vacation days for user %s in year %d", useruuid, year);

        // Define the start and end of the vacation year
        LocalDate startOfVacationYear = LocalDate.of(year, Month.SEPTEMBER, 1);
        LocalDate endOfVacationYear = LocalDate.of(year + 1, Month.AUGUST, 31);

        // Fetch all earned vacation entries for the user within the vacation year
        List<Vacation> earnedVacations = Vacation.find("useruuid = ?1 and date >= ?2 and date <= ?3", useruuid, startOfVacationYear, endOfVacationYear).list();

        // Sum up all earned vacation days
        double totalEarnedDays = earnedVacations.stream()
                .mapToDouble(Vacation::getVacationEarned)
                .sum();

        log.infof("Total earned vacation days: %f", totalEarnedDays);

        // Fetch all vacation registrations (used vacation days) within the vacation year
        double totalUsedDays = workService.calculateVacationByUserInMonth(useruuid, startOfVacationYear, endOfVacationYear) / 7.4; // Convert hours to days


        log.infof("Total used vacation days: %f", totalUsedDays);

        // Calculate remaining vacation days
        double remainingDays = totalEarnedDays - totalUsedDays;
        log.infof("Remaining vacation days for user %s in year %d: %f", useruuid, year, remainingDays);

        return remainingDays;
    }

    @Transactional
    public void transferVacationDays(String useruuid, int year, double days) {
        log.infof("Transferring vacation days for user %s", useruuid);
        new Vacation(UUID.randomUUID().toString(), useruuid, VacationType.MIGRATED, LocalDate.of(year, Month.SEPTEMBER, 1), -days).persist();
        new Vacation(UUID.randomUUID().toString(), useruuid, VacationType.MIGRATED, LocalDate.of(year + 1, Month.SEPTEMBER, 1), days).persist();
        log.info("Vacation days transferred successfully.");
    }
}