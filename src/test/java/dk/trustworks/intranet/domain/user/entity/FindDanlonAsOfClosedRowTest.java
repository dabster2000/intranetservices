package dk.trustworks.intranet.domain.user.entity;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class FindDanlonAsOfClosedRowTest {

    private static String uuid() { return UUID.randomUUID().toString(); }

    @Test
    @TestTransaction
    void closedRowIsExcluded_priorValidNumberReturned() {
        String user = uuid();
        LocalDate jan = LocalDate.of(2026, 1, 1);
        LocalDate feb = LocalDate.of(2026, 2, 1);

        // Jan: T100 (OPEN). Feb: T200, then CLOSED (e.g. a wrongly-minted A->B move that was reverted).
        new UserDanlonHistory(user, jan, "T100", "t").persist();
        UserDanlonHistory febRow = new UserDanlonHistory(user, feb, "T200", "t");
        febRow.setClosedDate(LocalDateTime.now());
        febRow.setClosedReason("status deleted");
        febRow.persist();

        // As of Feb, the closed T200 must NOT be returned; the prior valid T100 must be.
        assertEquals("T100", UserDanlonHistory.findDanlonAsOf(user, feb));
        // As of Jan, T100 as before.
        assertEquals("T100", UserDanlonHistory.findDanlonAsOf(user, jan));
    }

    @Test
    @TestTransaction
    void allRowsClosed_returnsNull() {
        String user = uuid();
        LocalDate jan = LocalDate.of(2026, 1, 1);
        UserDanlonHistory row = new UserDanlonHistory(user, jan, "T300", "t");
        row.setClosedDate(LocalDateTime.now());
        row.persist();

        assertNull(UserDanlonHistory.findDanlonAsOf(user, jan));
    }
}
