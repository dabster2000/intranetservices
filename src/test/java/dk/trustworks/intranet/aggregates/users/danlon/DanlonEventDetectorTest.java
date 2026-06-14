package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DanlonEventDetectorTest {

    @Inject DanlonEventDetector detector;

    private final List<String> users = new ArrayList<>();
    private String newUser() { String u = UUID.randomUUID().toString(); users.add(u); return u; }

    @AfterEach
    void cleanup() {
        for (String u : users) QuarkusTransaction.requiringNew().run(() -> {
            Salary.delete("useruuid", u);
            UserStatus.delete("useruuid", u);
            UserDanlonHistory.delete("useruuid", u);
        });
        users.clear();
    }

    private String anyCompanyUuid() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Company c = Company.<Company>findAll().firstResult();
            return c == null ? null : c.getUuid();
        });
    }
    private void seedSalary(String user, LocalDate activeFrom, SalaryType type) {
        QuarkusTransaction.requiringNew().run(() -> {
            Salary s = new Salary(activeFrom, 40000, user);
            s.setType(type);
            s.persist();
        });
    }
    private void seedStatus(String user, String companyUuid, StatusType status, LocalDate date) {
        QuarkusTransaction.requiringNew().run(() -> {
            Company c = Company.findById(companyUuid);
            UserStatus s = new UserStatus(ConsultantType.CONSULTANT, status, date, 100, user);
            s.setUuid(UUID.randomUUID().toString());
            s.setCompany(c);
            s.persist();
        });
    }

    @Test
    void firstEmployment_whenNoHistoryNoSalaryNoTermination() {
        String user = newUser();
        assertEquals(Optional.of(DanlonEventType.FIRST_EMPLOYMENT),
                detector.detectMostSpecific(user, LocalDate.of(2026, 2, 1), "cA"));
    }

    @Test
    void salaryTypeChange_whenHourlyToNormal() {
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 2, 1);
        seedSalary(user, month.minusMonths(1), SalaryType.HOURLY);
        seedSalary(user, month, SalaryType.NORMAL);
        assertEquals(Optional.of(DanlonEventType.SALARY_TYPE_CHANGE),
                detector.detectMostSpecific(user, month, "cA"));
    }

    @Test
    void companyTransitionWinsOverSalaryType() {
        String company = anyCompanyUuid();
        if (company == null) return; // graceful skip
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 2, 1);
        // also looks like a salary-type change…
        seedSalary(user, month.minusMonths(1), SalaryType.HOURLY);
        seedSalary(user, month, SalaryType.NORMAL);
        // …but a TERMINATED in a DIFFERENT company this month makes it a company transition (more specific)
        seedStatus(user, company, StatusType.TERMINATED, LocalDate.of(2026, 2, 15));
        assertEquals(Optional.of(DanlonEventType.COMPANY_TRANSITION),
                detector.detectMostSpecific(user, month, "OTHER-company"));
    }

    @Test
    void reEmployment_whenPreviouslyTerminated() {
        String company = anyCompanyUuid();
        if (company == null) return; // graceful skip
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 2, 1);
        seedStatus(user, company, StatusType.TERMINATED, LocalDate.of(2025, 6, 1)); // before the month
        assertEquals(Optional.of(DanlonEventType.RE_EMPLOYMENT),
                detector.detectMostSpecific(user, month, company));
    }
}
