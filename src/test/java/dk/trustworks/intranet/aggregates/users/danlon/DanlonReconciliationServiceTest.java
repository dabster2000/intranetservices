package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.aggregates.users.danlon.dto.ReconciliationResult;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DanlonReconciliationServiceTest {

    @Inject DanlonReconciliationService reconciliation;

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

    private String anyCompanyUuid() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Company c = Company.<Company>findAll().firstResult();
            return c == null ? null : c.getUuid();
        });
    }

    private void seedActiveStatus(String user, String companyUuid, LocalDate month) {
        QuarkusTransaction.requiringNew().run(() -> {
            Company c = Company.findById(companyUuid);
            UserStatus s = new UserStatus(ConsultantType.CONSULTANT, StatusType.ACTIVE, month, 100, user);
            s.setUuid(UUID.randomUUID().toString());
            s.setCompany(c);
            s.persist();
        });
    }

    @Test
    void raisesMintProposalForActiveWithNoDanlon() {
        String company = anyCompanyUuid();
        if (company == null) return; // graceful skip — no seed companies in this DB
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 2, 1);
        seedActiveStatus(user, company, month);

        ReconciliationResult r = reconciliation.reconcileCompanyMonth(company, month);
        assertTrue(r.proposalsRaised() >= 1, "should raise at least the missing-ID active");

        DanlonAssignmentProposal p = QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingForSlot(user, company, month, DanlonEventType.FIRST_EMPLOYMENT));
        assertNotNull(p, "a FIRST_EMPLOYMENT MINT proposal must exist for the active-with-no-danlon user");
        assertEquals("system-reconciliation", p.getDetectedBy());
    }

    @Test
    void withdrawsStaleCloseWhenEmploymentIsActiveAgain() {
        String company = anyCompanyUuid();
        if (company == null) return; // graceful skip
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 3, 1);

        // The deletion that raised the CLOSE has been undone: an ACTIVE status now exists again,
        // and the targeted row is still OPEN (close not yet approved).
        seedActiveStatus(user, company, month);
        String closePid = QuarkusTransaction.requiringNew().call(() -> {
            UserDanlonHistory row = new UserDanlonHistory(user, month, "T8000", "t");
            row.setCompanyUuid(company); row.setEventType("RE_EMPLOYMENT"); row.persist();
            DanlonAssignmentProposal p = new DanlonAssignmentProposal();
            p.setUuid(UUID.randomUUID().toString());
            p.setUseruuid(user); p.setCompanyUuid(company); p.setEffectiveMonth(month);
            p.setEventType(DanlonEventType.RE_EMPLOYMENT); p.setIntent(ProposalIntent.CLOSE);
            p.setStatus(ProposalStatus.PENDING); p.setSuggestedNumber("T8000");
            p.setTargetHistoryUuid(row.getUuid());
            p.setDetectedDate(LocalDateTime.now()); p.setDetectedBy("system-delete-detector");
            p.persist();
            return p.getUuid();
        });

        ReconciliationResult r = reconciliation.reconcileCompanyMonth(company, month);
        assertTrue(r.proposalsWithdrawn() >= 1, "should withdraw the stale CLOSE");

        DanlonAssignmentProposal after = QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findById(closePid));
        assertEquals(ProposalStatus.WITHDRAWN, after.getStatus());
    }
}
