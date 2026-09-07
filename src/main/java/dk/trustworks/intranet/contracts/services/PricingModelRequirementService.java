package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Determines assignment requirements without exposing salary amounts or salary history. */
@ApplicationScoped
public class PricingModelRequirementService {

    @Inject
    EntityManager em;

    /** Assignment dates are inclusive; each salary lasts until the next salary starts. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean isRequired(String userUuid, LocalDate startDate, LocalDate endDate) {
        if (userUuid == null || userUuid.isBlank() || startDate == null || endDate == null
                || startDate.isAfter(endDate)) {
            throw new BadRequestException("User, start date and end date are required, with start date on or before end date");
        }
        // Only dates and types are selected. Missing salary records mean no HOURLY period;
        // database failures deliberately propagate rather than making the field optional.
        List<SalaryPeriod> periods = em.createQuery("""
                SELECT s.activefrom, s.type FROM Salary s
                WHERE s.useruuid = :userUuid AND s.activefrom <= :endDate
                ORDER BY s.activefrom
                """, Object[].class)
                .setParameter("userUuid", userUuid)
                .setParameter("endDate", endDate)
                .getResultList().stream()
                .map(row -> new SalaryPeriod((LocalDate) row[0], (SalaryType) row[1]))
                .toList();
        return overlapsHourly(periods, startDate, endDate);
    }

    static boolean overlapsHourly(List<SalaryPeriod> periods, LocalDate startDate, LocalDate endDate) {
        List<SalaryPeriod> sorted = periods.stream()
                .filter(period -> period.activeFrom() != null)
                .sorted(Comparator.comparing(SalaryPeriod::activeFrom))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            SalaryPeriod period = sorted.get(i);
            LocalDate nextStart = i + 1 < sorted.size() ? sorted.get(i + 1).activeFrom() : null;
            if (period.type() == SalaryType.HOURLY && !period.activeFrom().isAfter(endDate)
                    && (nextStart == null || nextStart.isAfter(startDate))) {
                return true;
            }
        }
        return false;
    }

    record SalaryPeriod(LocalDate activeFrom, SalaryType type) { }
}
