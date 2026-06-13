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
class DanlonAssignmentServiceApproveTest {

    @Inject DanlonAssignmentService service;

    private final List<String> users = new ArrayList<>();
    private String newUser() { String u = UUID.randomUUID().toString(); users.add(u); return u; }

    @AfterEach
    void cleanup() {
        for (String u : users) QuarkusTransaction.requiringNew().run(() -> {
            DanlonAssignmentProposal.delete("useruuid", u);
            UserDanlonHistory.delete("useruuid", u);
        });
        users.clear();
    }

    private String createMintProposal(String user, LocalDate month, String company) {
        service.proposeIfNeeded(user, month, DanlonEventType.FIRST_EMPLOYMENT, company);
        return QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingForSlot(user, company, month.withDayOfMonth(1), DanlonEventType.FIRST_EMPLOYMENT).getUuid());
    }
    private long historyRowsForMonth(String user, LocalDate month) {
        return QuarkusTransaction.requiringNew().call(() ->
                UserDanlonHistory.count("useruuid = ?1 AND activeDate = ?2", user, month.withDayOfMonth(1)));
    }

    @Test
    void approveMint_insertsExactlyOneOpenRowWithConfirmedNumber() {
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 2, 1);
        String pid = createMintProposal(user, month, "cA");

        UserDanlonHistory row = service.approveProposal(pid, "T2222", "hr-uuid");
        assertEquals("T2222", row.getDanlon());
        assertEquals("cA", row.getCompanyUuid());
        assertEquals("FIRST_EMPLOYMENT", row.getEventType());
        assertFalse(row.isClosed());
        assertEquals(1, historyRowsForMonth(user, month));

        DanlonAssignmentProposal p = QuarkusTransaction.requiringNew().call(() -> DanlonAssignmentProposal.findById(pid));
        assertEquals(ProposalStatus.APPROVED, p.getStatus());
        assertEquals(row.getUuid(), p.getMintedHistoryUuid());
        assertEquals("hr-uuid", p.getResolvedBy());
    }

    @Test
    void approveMint_blankConfirmed_usesSuggestedNumber() {
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 2, 1);
        String pid = createMintProposal(user, month, "cA");
        String suggested = QuarkusTransaction.requiringNew().<DanlonAssignmentProposal>call(() -> DanlonAssignmentProposal.findById(pid)).getSuggestedNumber();

        UserDanlonHistory row = service.approveProposal(pid, "  ", "hr-uuid");
        assertEquals(suggested, row.getDanlon());
    }

    @Test
    void approveMint_dupGuard_rejectsNumberOpenForAnotherUser() {  // AC5
        String userX = newUser();
        String userY = newUser();
        LocalDate month = LocalDate.of(2026, 2, 1);
        // userX already OPEN on T3000
        QuarkusTransaction.requiringNew().run(() -> {
            UserDanlonHistory h = new UserDanlonHistory(userX, month, "T3000", "t");
            h.setCompanyUuid("cA"); h.setEventType("FIRST_EMPLOYMENT"); h.persist();
        });
        String pidY = createMintProposal(userY, month, "cB");
        DanlonProposalException ex = assertThrows(DanlonProposalException.class,
                () -> service.approveProposal(pidY, "T3000", "hr-uuid"));
        assertTrue(ex.getMessage().contains("T3000"));
        assertEquals(0, historyRowsForMonth(userY, month), "nothing minted for Y");
    }

    @Test
    void mintCloseReopen_yieldsSameNumberAndOneRow_acrossToggles() {  // AC6
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 3, 1);
        String pid = createMintProposal(user, month, "cA");
        UserDanlonHistory minted = service.approveProposal(pid, "T4444", "hr");
        String rowUuid = minted.getUuid();

        for (int i = 0; i < 3; i++) {
            // close
            ProposalOutcome closeOut = service.proposeClose(rowUuid, "status deleted #" + i);
            assertEquals(ProposalOutcome.CLOSE_PROPOSED, closeOut);
            String closePid = QuarkusTransaction.requiringNew().call(() ->
                    DanlonAssignmentProposal.findPendingCloseForTarget(rowUuid).getUuid());
            service.approveProposal(closePid, null, "hr");
            UserDanlonHistory afterClose = QuarkusTransaction.requiringNew().call(() -> UserDanlonHistory.findById(rowUuid));
            assertTrue(afterClose.isClosed(), "row must be CLOSED after approve(CLOSE)");
            assertEquals("T4444", afterClose.getDanlon());

            // reopen (re-detect same slot finds the CLOSED row)
            ProposalOutcome reopenOut = service.proposeIfNeeded(user, month, DanlonEventType.FIRST_EMPLOYMENT, "cA");
            assertEquals(ProposalOutcome.REOPEN_PROPOSED, reopenOut);
            String reopenPid = QuarkusTransaction.requiringNew().call(() ->
                    DanlonAssignmentProposal.findPendingForSlot(user, "cA", month, DanlonEventType.FIRST_EMPLOYMENT).getUuid());
            service.approveProposal(reopenPid, null, "hr");
            UserDanlonHistory afterReopen = QuarkusTransaction.requiringNew().call(() -> UserDanlonHistory.findById(rowUuid));
            assertFalse(afterReopen.isClosed(), "row must be OPEN after approve(REOPEN)");
            assertEquals("T4444", afterReopen.getDanlon(), "reopen keeps the same number");
        }
        // Throughout, exactly one history row exists for the month — no new numbers, no duplicate rows.
        assertEquals(1, historyRowsForMonth(user, month));
    }

    @Test
    void reject_marksRejected_noRowMinted() {
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 2, 1);
        String pid = createMintProposal(user, month, "cA");
        service.rejectProposal(pid, "duplicate of manual entry", "hr");
        DanlonAssignmentProposal p = QuarkusTransaction.requiringNew().call(() -> DanlonAssignmentProposal.findById(pid));
        assertEquals(ProposalStatus.REJECTED, p.getStatus());
        assertEquals("duplicate of manual entry", p.getResolutionNote());
        assertEquals(0, historyRowsForMonth(user, month));
    }

    @Test
    void approveMissingProposal_throwsLoudly() {
        assertThrows(DanlonProposalException.class, () -> service.approveProposal("does-not-exist", "T1", "hr"));
    }

    @Test
    void proposeClose_outcomes() {
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 5, 1);
        // a system-minted OPEN row
        String rowUuid = QuarkusTransaction.requiringNew().call(() -> {
            UserDanlonHistory h = new UserDanlonHistory(user, month, "T6000", "t");
            h.setCompanyUuid("cA"); h.setEventType("RE_EMPLOYMENT"); h.persist();
            return h.getUuid();
        });
        assertEquals(ProposalOutcome.CLOSE_PROPOSED, service.proposeClose(rowUuid, "deleted"));
        assertEquals(ProposalOutcome.ALREADY_PROPOSED, service.proposeClose(rowUuid, "deleted again"));
        assertEquals(ProposalOutcome.SKIPPED, service.proposeClose("missing-row", "x"));

        // a legacy/manual row with no event_type is never auto-closed
        String legacyUuid = QuarkusTransaction.requiringNew().call(() -> {
            UserDanlonHistory h = new UserDanlonHistory(newUser(), month, "T6001", "admin@trustworks.dk"); h.persist();
            return h.getUuid();
        });
        assertEquals(ProposalOutcome.SKIPPED, service.proposeClose(legacyUuid, "x"));
    }

    @Test
    void listPending_returnsViewsWithNameAndCurrentNumber() {
        String user = newUser();
        LocalDate month = LocalDate.of(2026, 7, 1);
        createMintProposal(user, month, "cA");
        var views = QuarkusTransaction.requiringNew().call(() -> service.listPending("cA", month));
        assertEquals(1, views.size());
        assertEquals(ProposalIntent.MINT, views.get(0).intent());
        assertNotNull(views.get(0).employeeName());
    }
}
