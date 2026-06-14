package dk.trustworks.intranet.domain.user.entity;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DanlonReportFindersTest {

    private static String uuid() { return UUID.randomUUID().toString(); }

    @Test
    @TestTransaction
    void hasOpenEventInMonth_matchesEventTypeAndExcludesClosed() {
        String user = uuid();
        LocalDate month = LocalDate.of(2026, 2, 1);
        UserDanlonHistory row = new UserDanlonHistory(user, month, "T700", "hr-uuid");
        row.setEventType("COMPANY_TRANSITION");
        row.persist();

        assertTrue(UserDanlonHistory.hasOpenEventInMonth(user, month, "COMPANY_TRANSITION"));
        assertTrue(UserDanlonHistory.hasOpenEventInMonth(user, month, "SALARY_TYPE_CHANGE", "COMPANY_TRANSITION"));
        assertFalse(UserDanlonHistory.hasOpenEventInMonth(user, month, "RE_EMPLOYMENT"));

        row.setClosedDate(LocalDateTime.now());
        row.persist();
        assertFalse(UserDanlonHistory.hasOpenEventInMonth(user, month, "COMPANY_TRANSITION"),
                "closed rows must not count");
    }

    @Test
    @TestTransaction
    void findOpenEventRow_returnsOpenMatchOnly() {
        String user = uuid();
        LocalDate month = LocalDate.of(2026, 3, 1);
        UserDanlonHistory row = new UserDanlonHistory(user, month, "T800", "hr-uuid");
        row.setEventType("FIRST_EMPLOYMENT");
        row.persist();

        UserDanlonHistory found = UserDanlonHistory.findOpenEventRow(user, month, "FIRST_EMPLOYMENT");
        assertNotNull(found);
        assertEquals("T800", found.getDanlon());
        assertNull(UserDanlonHistory.findOpenEventRow(user, month, "RE_EMPLOYMENT"));
    }
}
