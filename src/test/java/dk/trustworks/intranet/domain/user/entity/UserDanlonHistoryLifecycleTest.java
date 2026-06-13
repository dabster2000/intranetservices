package dk.trustworks.intranet.domain.user.entity;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class UserDanlonHistoryLifecycleTest {

    private static String uuid() { return UUID.randomUUID().toString(); }

    @Test
    @TestTransaction
    void persistsAndReadsLifecycleFields() {
        String user = uuid();
        LocalDate month = LocalDate.of(2026, 3, 1);
        UserDanlonHistory row = new UserDanlonHistory(user, month, "T9001", "tester");
        row.setCompanyUuid("company-x");
        row.setEventType("FIRST_EMPLOYMENT");
        row.persist();

        UserDanlonHistory found = UserDanlonHistory.findRowForMonth(user, month);
        assertNotNull(found);
        assertEquals("company-x", found.getCompanyUuid());
        assertEquals("FIRST_EMPLOYMENT", found.getEventType());
        assertFalse(found.isClosed(), "new row must be OPEN");
    }

    @Test
    @TestTransaction
    void findOpenByDanlonExcludesClosedRows() {
        String openUser = uuid();
        String closedUser = uuid();
        String number = "T9100" + (System.nanoTime() % 1000); // unique-ish per run

        UserDanlonHistory open = new UserDanlonHistory(openUser, LocalDate.of(2026, 4, 1), number, "t");
        open.persist();

        UserDanlonHistory closed = new UserDanlonHistory(closedUser, LocalDate.of(2026, 4, 1), number, "t");
        closed.setClosedDate(LocalDateTime.now());
        closed.setClosedReason("retired");
        closed.persist();

        var open_ = UserDanlonHistory.findOpenByDanlon(number);
        assertEquals(1, open_.size(), "only the OPEN row should match");
        assertEquals(openUser, open_.get(0).getUseruuid());
    }
}
