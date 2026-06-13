package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.domain.user.entity.DanlonAssignmentProposal;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
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
class DanlonAssignmentServiceProposeTest {

    @Inject DanlonAssignmentService service;

    private final List<String> users = new ArrayList<>();
    private String newUser() { String u = UUID.randomUUID().toString(); users.add(u); return u; }

    @AfterEach
    void cleanup() {
        for (String u : users) {
            QuarkusTransaction.requiringNew().run(() -> {
                DanlonAssignmentProposal.delete("useruuid", u);
                UserDanlonHistory.delete("useruuid", u);
            });
        }
        users.clear();
    }

    private void persistHistory(String user, LocalDate month, String number, String company, String eventType, boolean closed) {
        QuarkusTransaction.requiringNew().run(() -> {
            UserDanlonHistory h = new UserDanlonHistory(user, month, number, "t");
            h.setCompanyUuid(company);
            h.setEventType(eventType);
            if (closed) h.setClosedDate(LocalDateTime.now());
            h.persist();
        });
    }

    private long pendingCount(String user) {
        return QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.count("useruuid = ?1 AND status = ?2", user, ProposalStatus.PENDING));
    }

    @Test
    void freshSlot_createsMintProposal() {
        String user = newUser();
        ProposalOutcome out = service.proposeIfNeeded(user, LocalDate.of(2026, 2, 10), DanlonEventType.FIRST_EMPLOYMENT, "cA");
        assertEquals(ProposalOutcome.CREATED, out);
        DanlonAssignmentProposal p = QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingForSlot(user, "cA", LocalDate.of(2026, 2, 1), DanlonEventType.FIRST_EMPLOYMENT));
        assertNotNull(p, "a PENDING MINT proposal must exist");
        assertEquals(ProposalIntent.MINT, p.getIntent());
        assertNotNull(p.getSuggestedNumber());
        assertTrue(p.getSuggestedNumber().startsWith("T"));
        // AC1: NO history row was inserted.
        assertNull(QuarkusTransaction.requiringNew().call(() ->
                UserDanlonHistory.findRowForMonth(user, LocalDate.of(2026, 2, 1))));
    }

    @Test
    void idempotent_repeatedCallsYieldOnePendingProposal() {
        String user = newUser();
        assertEquals(ProposalOutcome.CREATED,
                service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.FIRST_EMPLOYMENT, "cA"));
        assertEquals(ProposalOutcome.ALREADY_PROPOSED,
                service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.FIRST_EMPLOYMENT, "cA"));
        assertEquals(1, pendingCount(user));
    }

    @Test
    void openMatchingRow_isAlreadyMinted_noProposal() {
        String user = newUser();
        persistHistory(user, LocalDate.of(2026, 2, 1), "T500", "cA", "FIRST_EMPLOYMENT", false);
        ProposalOutcome out = service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.FIRST_EMPLOYMENT, "cA");
        assertEquals(ProposalOutcome.ALREADY_MINTED, out);
        assertEquals(0, pendingCount(user));
    }

    @Test
    void openRowDifferentSlot_isConflict() {
        String user = newUser();
        persistHistory(user, LocalDate.of(2026, 2, 1), "T501", "cA", "FIRST_EMPLOYMENT", false);
        // A genuinely different company+event in the same month → CONFLICT (Approach-A one-row-per-month bound).
        ProposalOutcome out = service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.COMPANY_TRANSITION, "cB");
        assertEquals(ProposalOutcome.CONFLICT, out);
        assertEquals(0, pendingCount(user));
    }

    @Test
    void closedMatchingRow_proposesReopen_keepingSameNumber() {
        String user = newUser();
        persistHistory(user, LocalDate.of(2026, 2, 1), "T777", "cA", "COMPANY_TRANSITION", true);
        ProposalOutcome out = service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.COMPANY_TRANSITION, "cA");
        assertEquals(ProposalOutcome.REOPEN_PROPOSED, out);
        DanlonAssignmentProposal p = QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingForSlot(user, "cA", LocalDate.of(2026, 2, 1), DanlonEventType.COMPANY_TRANSITION));
        assertNotNull(p);
        assertEquals(ProposalIntent.REOPEN, p.getIntent());
        assertEquals("T777", p.getSuggestedNumber(), "reopen keeps the same number");
        assertNotNull(p.getTargetHistoryUuid());
        // second call is a no-op
        assertEquals(ProposalOutcome.ALREADY_PROPOSED,
                service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.COMPANY_TRANSITION, "cA"));
    }

    @Test
    void legacyNullRow_isLenientMatch_noFalseConflict() {
        String user = newUser();
        // Legacy OPEN row with NULL company/event must NOT raise CONFLICT for a detected event.
        persistHistory(user, LocalDate.of(2026, 2, 1), "T9", null, null, false);
        ProposalOutcome out = service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.SALARY_TYPE_CHANGE, "cA");
        assertEquals(ProposalOutcome.ALREADY_MINTED, out);
    }
}
