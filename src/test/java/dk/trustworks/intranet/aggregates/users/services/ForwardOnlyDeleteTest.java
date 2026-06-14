package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.domain.user.entity.DanlonAssignmentProposal;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ForwardOnlyDeleteTest {

    @Inject StatusService statusService;
    @Inject SalaryService salaryService;

    private final List<String> users = new ArrayList<>();
    private String newUser() { String u = UUID.randomUUID().toString(); users.add(u); return u; }

    @AfterEach
    void cleanup() {
        for (String u : users) QuarkusTransaction.requiringNew().run(() -> {
            DanlonAssignmentProposal.delete("useruuid", u);
            UserDanlonHistory.delete("useruuid", u);
            UserStatus.delete("useruuid", u);
            Salary.delete("useruuid", u);
        });
        users.clear();
    }

    private String companyUuid() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Company c = Company.<Company>findAll().firstResult();
            return c == null ? null : c.getUuid();
        });
    }

    @Test
    void deletingStatus_doesNotHardDelete_raisesCloseProposal() {
        String company = companyUuid();
        if (company == null) return; // graceful skip
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 4, 1);

        String[] ids = QuarkusTransaction.requiringNew().call(() -> {
            UserStatus st = new UserStatus(ConsultantType.CONSULTANT, StatusType.ACTIVE, month, 100, user);
            st.setUuid(UUID.randomUUID().toString());
            st.setCompany(Company.findById(company));
            st.persist();
            UserDanlonHistory row = new UserDanlonHistory(user, month, "T7777", "hr-uuid");
            row.setCompanyUuid(company); row.setEventType("RE_EMPLOYMENT"); row.persist();
            return new String[]{ st.getUuid(), row.getUuid() };
        });
        String statusUuid = ids[0], rowUuid = ids[1];

        statusService.delete(statusUuid);

        // AC7: the minted row is NOT physically removed…
        UserDanlonHistory row = QuarkusTransaction.requiringNew().call(() -> UserDanlonHistory.findById(rowUuid));
        assertNotNull(row, "minted row must be retained (forward-only)");
        assertFalse(row.isClosed(), "row stays OPEN until HR approves the CLOSE");
        // …and a CLOSE proposal targets it.
        DanlonAssignmentProposal close = QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingCloseForTarget(rowUuid));
        assertNotNull(close, "a PENDING CLOSE proposal must be raised");
    }

    @Test
    void deletingNonexistentStatus_doesNotThrow() {  // N9
        assertDoesNotThrow(() -> statusService.delete("does-not-exist-" + UUID.randomUUID()));
    }

    @Test
    void deletingNormalSalary_raisesCloseProposal_retainsRow() {
        String company = companyUuid();
        if (company == null) return; // graceful skip
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 5, 1);

        String[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Salary sal = new Salary(month, 40000, user);
            sal.setType(SalaryType.NORMAL);
            sal.setUuid(UUID.randomUUID().toString());
            sal.persist();
            UserDanlonHistory row = new UserDanlonHistory(user, month, "T7778", "hr-uuid");
            row.setCompanyUuid(company); row.setEventType("SALARY_TYPE_CHANGE"); row.persist();
            return new String[]{ sal.getUuid(), row.getUuid() };
        });

        salaryService.delete(ids[0]);

        UserDanlonHistory row = QuarkusTransaction.requiringNew().call(() -> UserDanlonHistory.findById(ids[1]));
        assertNotNull(row, "minted row retained");
        assertNotNull(QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingCloseForTarget(ids[1])), "CLOSE proposal raised");
    }
}
