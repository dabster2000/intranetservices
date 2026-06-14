package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.aggregates.users.danlon.DanlonEventType;
import dk.trustworks.intranet.domain.user.entity.DanlonAssignmentProposal;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
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
class StatusServiceDanlonFlipTest {

    @Inject StatusService statusService;

    private final List<String> users = new ArrayList<>();
    private String newUser() { String u = UUID.randomUUID().toString(); users.add(u); return u; }

    @AfterEach
    void cleanup() {
        for (String u : users) QuarkusTransaction.requiringNew().run(() -> {
            DanlonAssignmentProposal.delete("useruuid", u);
            UserDanlonHistory.delete("useruuid", u);
            UserStatus.delete("useruuid", u);
        });
        users.clear();
    }

    @Test
    void creatingFirstActiveStatus_raisesProposal_mintsNothing() {
        String company = QuarkusTransaction.requiringNew().call(() -> {
            Company c = Company.<Company>findAll().firstResult();
            return c == null ? null : c.getUuid();
        });
        if (company == null) return; // graceful skip
        String user = newUser();
        LocalDate date = LocalDate.of(2026, 2, 10);

        UserStatus status = new UserStatus(ConsultantType.CONSULTANT, StatusType.ACTIVE, date, 100, user);
        status.setUuid(UUID.randomUUID().toString());
        status.setCompany(Company.findById(company));
        statusService.create(status);

        // AC1: a PENDING FIRST_EMPLOYMENT proposal exists, and NO history row was minted.
        DanlonAssignmentProposal p = QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingForSlot(user, company, date.withDayOfMonth(1), DanlonEventType.FIRST_EMPLOYMENT));
        assertNotNull(p, "status create must raise a FIRST_EMPLOYMENT proposal");
        Long minted = QuarkusTransaction.requiringNew().call(() -> UserDanlonHistory.count("useruuid", user));
        assertEquals(0L, minted, "no Danløn row may be auto-minted (AC1)");
    }
}
