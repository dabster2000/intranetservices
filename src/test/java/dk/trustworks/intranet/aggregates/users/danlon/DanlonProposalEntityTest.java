package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.domain.user.entity.DanlonAssignmentProposal;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DanlonProposalEntityTest {

    private static String uuid() { return UUID.randomUUID().toString(); }

    private DanlonAssignmentProposal pending(String user, String company, LocalDate month, DanlonEventType ev) {
        DanlonAssignmentProposal p = new DanlonAssignmentProposal();
        p.setUuid(uuid());
        p.setUseruuid(user);
        p.setCompanyUuid(company);
        p.setEffectiveMonth(month);
        p.setEventType(ev);
        p.setIntent(ProposalIntent.MINT);
        p.setStatus(ProposalStatus.PENDING);
        p.setDetectedDate(LocalDateTime.now());
        p.setDetectedBy("test");
        return p;
    }

    @Test
    void enumRoundTripAndSlotFinder() {
        String user = uuid();
        LocalDate month = LocalDate.of(2026, 5, 1);
        try {
            QuarkusTransaction.requiringNew().run(() -> pending(user, "c1", month, DanlonEventType.FIRST_EMPLOYMENT).persist());

            DanlonAssignmentProposal found = QuarkusTransaction.requiringNew().call(() ->
                    DanlonAssignmentProposal.findPendingForSlot(user, "c1", month, DanlonEventType.FIRST_EMPLOYMENT));
            assertNotNull(found);
            assertEquals(ProposalIntent.MINT, found.getIntent());
            assertEquals(DanlonEventType.FIRST_EMPLOYMENT, found.getEventType());
            assertEquals(ProposalStatus.PENDING, found.getStatus());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> DanlonAssignmentProposal.delete("useruuid", user));
        }
    }

    @Test
    void dbRejectsTwoPendingProposalsForSameSlot() {
        String user = uuid();
        LocalDate month = LocalDate.of(2026, 6, 1);
        try {
            QuarkusTransaction.requiringNew().run(() -> pending(user, "c1", month, DanlonEventType.FIRST_EMPLOYMENT).persist());
            // second PENDING for the SAME slot must violate uq_danlon_proposal_open_slot
            assertThrows(Exception.class, () ->
                    QuarkusTransaction.requiringNew().run(() -> pending(user, "c1", month, DanlonEventType.FIRST_EMPLOYMENT).persist()));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> DanlonAssignmentProposal.delete("useruuid", user));
        }
    }
}
