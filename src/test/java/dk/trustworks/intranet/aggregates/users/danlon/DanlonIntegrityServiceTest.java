package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.aggregates.users.danlon.dto.DanlonIntegrityReport;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
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
class DanlonIntegrityServiceTest {

    @Inject DanlonIntegrityService service;

    private final List<String> users = new ArrayList<>();
    private String newUser() { String u = UUID.randomUUID().toString(); users.add(u); return u; }

    @AfterEach
    void cleanup() {
        for (String u : users) QuarkusTransaction.requiringNew().run(() -> UserDanlonHistory.delete("useruuid", u));
        users.clear();
    }

    @Test
    void duplicateDanlonAcrossUsersIsReported() {
        String u1 = newUser();
        String u2 = newUser();
        String shared = "TDUPTEST" + (System.nanoTime() % 100000);
        QuarkusTransaction.requiringNew().run(() -> {
            new UserDanlonHistory(u1, LocalDate.of(2026, 1, 1), shared, "t").persist();
            new UserDanlonHistory(u2, LocalDate.of(2026, 1, 1), shared, "t").persist();
        });

        DanlonIntegrityReport report = service.buildReport();
        assertNotNull(report.duplicates());
        assertTrue(report.duplicates().stream().anyMatch(d ->
                d.danlon().equals(shared) && d.holders().size() >= 2),
                "the shared number must appear as a duplicate with >=2 holders");
    }

    @Test
    void zeroPlaceholderIsReportedAsNonConforming() {
        String u = newUser();
        QuarkusTransaction.requiringNew().run(() ->
                new UserDanlonHistory(u, LocalDate.of(2026, 1, 1), "0", "t").persist());

        DanlonIntegrityReport report = service.buildReport();
        assertTrue(report.nonConforming().stream().anyMatch(n -> n.useruuid().equals(u) && "0".equals(n.danlon())),
                "the '0' placeholder must be reported as non-conforming");
    }

    @Test
    void reportListsAreNeverNull() {
        DanlonIntegrityReport report = service.buildReport();
        assertNotNull(report.duplicates());
        assertNotNull(report.missingIdActives());
        assertNotNull(report.nonConforming());
    }
}
