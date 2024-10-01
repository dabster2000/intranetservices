package dk.trustworks.intranet.aggregates.bidata.repositories;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.time.LocalDate;

@ApplicationScoped
public class BiDataPerDayRepository implements PanacheRepository<BiDataPerDay> {

    public void insertOrUpdateSalary(String userUuid, LocalDate documentDate, int year, int month, int day, int salary) {
        String sql = "INSERT INTO bi_data_per_day (useruuid, document_date, year, month, day, salary, last_update) " +
                     "VALUES (:useruuid, :documentDate, :year, :month, :day, :salary, NOW()) " +
                     "ON DUPLICATE KEY UPDATE salary = VALUES(salary), last_update = NOW()";

        Query query = getEntityManager().createNativeQuery(sql);
        query.setParameter("useruuid", userUuid);
        query.setParameter("documentDate", documentDate);
        query.setParameter("year", year);
        query.setParameter("month", month);
        query.setParameter("day", day);
        query.setParameter("salary", salary);

        query.executeUpdate();
    }

    public void insertOrUpdateWork(String userUuid, LocalDate documentDate, int year, int month, int day, double workHours) {
        String sql = "INSERT INTO bi_data_per_day (useruuid, document_date, year, month, day, registered_billable_hours, last_update) " +
                     "VALUES (:useruuid, :documentDate, :year, :month, :day, :workHours, NOW()) " +
                     "ON DUPLICATE KEY UPDATE registered_billable_hours = VALUES(registered_billable_hours), last_update = NOW()";

        Query query = getEntityManager().createNativeQuery(sql);
        query.setParameter("useruuid", userUuid);
        query.setParameter("documentDate", documentDate);
        query.setParameter("year", year);
        query.setParameter("month", month);
        query.setParameter("day", day);
        query.setParameter("workHours", BigDecimal.valueOf(workHours));

        query.executeUpdate();
    }

    public void insertOrUpdateRevenue(String userUuid, LocalDate registeredDate, int year, int monthValue, int dayOfMonth, double revenue) {
        String sql = "INSERT INTO bi_data_per_day (" +
                "useruuid, document_date, year, month, day, registered_amount, last_update) " +
                "VALUES (:useruuid, :documentDate, :year, :month, :day, :revenue, NOW()) " +
                "ON DUPLICATE KEY UPDATE " +
                "registered_amount = VALUES(registered_amount), " +
                "last_update = NOW()";

        Query query = getEntityManager().createNativeQuery(sql);
        query.setParameter("useruuid", userUuid);
        query.setParameter("documentDate", registeredDate);
        query.setParameter("year", year);
        query.setParameter("month", monthValue);
        query.setParameter("day", dayOfMonth);
        query.setParameter("revenue", BigDecimal.valueOf(revenue));

        query.executeUpdate();
    }

    public void insertOrUpdateData(String userUuid, String documentDate, int year, int month, int day, String companyUuid,
                                   BigDecimal grossAvailableHours, BigDecimal unavailableHours, BigDecimal vacationHours,
                                   BigDecimal sickHours, BigDecimal maternityLeaveHours, BigDecimal nonPaydLeaveHours,
                                   BigDecimal paidLeaveHours, String consultantType, String statusType, boolean isTwBonusEligible) {

        String sql = "INSERT INTO bi_data_per_day (" +
                "useruuid, document_date, year, month, day, " +
                "companyuuid, gross_available_hours, unavailable_hours, vacation_hours, " +
                "sick_hours, maternity_leave_hours, non_payd_leave_hours, paid_leave_hours, " +
                "consultant_type, status_type, is_tw_bonus_eligible, last_update) " +
                "VALUES (:useruuid, :documentDate, :year, :month, :day, :companyUuid, " +
                ":grossAvailableHours, :unavailableHours, :vacationHours, :sickHours, " +
                ":maternityLeaveHours, :nonPaydLeaveHours, :paidLeaveHours, :consultantType, " +
                ":statusType, :isTwBonusEligible, NOW()) " +
                "ON DUPLICATE KEY UPDATE " +
                "companyuuid = VALUES(companyuuid), " +
                "gross_available_hours = VALUES(gross_available_hours), " +
                "unavailable_hours = VALUES(unavailable_hours), " +
                "vacation_hours = VALUES(vacation_hours), " +
                "sick_hours = VALUES(sick_hours), " +
                "maternity_leave_hours = VALUES(maternity_leave_hours), " +
                "non_payd_leave_hours = VALUES(non_payd_leave_hours), " +
                "paid_leave_hours = VALUES(paid_leave_hours), " +
                "consultant_type = VALUES(consultant_type), " +
                "status_type = VALUES(status_type), " +
                "is_tw_bonus_eligible = VALUES(is_tw_bonus_eligible), " +
                "last_update = NOW()";

        Query query = getEntityManager().createNativeQuery(sql);
        query.setParameter("useruuid", userUuid);
        query.setParameter("documentDate", documentDate);
        query.setParameter("year", year);
        query.setParameter("month", month);
        query.setParameter("day", day);
        query.setParameter("companyUuid", companyUuid);
        query.setParameter("grossAvailableHours", grossAvailableHours);
        query.setParameter("unavailableHours", unavailableHours);
        query.setParameter("vacationHours", vacationHours);
        query.setParameter("sickHours", sickHours);
        query.setParameter("maternityLeaveHours", maternityLeaveHours);
        query.setParameter("nonPaydLeaveHours", nonPaydLeaveHours);
        query.setParameter("paidLeaveHours", paidLeaveHours);
        query.setParameter("consultantType", consultantType);
        query.setParameter("statusType", statusType);
        query.setParameter("isTwBonusEligible", isTwBonusEligible);

        query.executeUpdate();
    }

    public void insertOrUpdateBudgetHours(String userUuid, String documentDate, int year, int month, int day, BigDecimal budgetHours) {
        String sql = "INSERT INTO bi_data_per_day (" +
                "useruuid, document_date, year, month, day, budget_hours, last_update) " +
                "VALUES (:useruuid, :documentDate, :year, :month, :day, :budgetHours, NOW()) " +
                "ON DUPLICATE KEY UPDATE " +
                "budget_hours = VALUES(budget_hours), " +
                "last_update = NOW()";

        Query query = getEntityManager().createNativeQuery(sql);
        query.setParameter("useruuid", userUuid);
        query.setParameter("documentDate", documentDate);
        query.setParameter("year", year);
        query.setParameter("month", month);
        query.setParameter("day", day);
        query.setParameter("budgetHours", budgetHours);

        query.executeUpdate();
    }
}
