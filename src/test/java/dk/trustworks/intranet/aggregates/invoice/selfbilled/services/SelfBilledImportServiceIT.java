package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.AssignmentSourceType;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledAssignment;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLine;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLineStatus;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SelfBilledImportServiceIT.NoDevServicesProfile.class)
class SelfBilledImportServiceIT {

    public static class NoDevServicesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder"
            );
        }
    }

    @Inject SelfBilledImportService service;
    @Inject SelfBilledCodeResolver resolver;

    private SelfBilledSource source() {
        SelfBilledSource s = new SelfBilledSource();
        s.uuid = "5e1f0001-0000-4000-8000-000000002104";
        s.agreementCompanyUuid = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
        s.accountNumber = 2104;
        s.clientUuid = "2cbb7f5e-9e2b-4edc-870b-e9591dc58891";
        s.enabled = true; s.label = "Vattenfall";
        return s;
    }

    @Test
    @TestTransaction
    void processLines_nets_corrections_stores_booking_date_and_is_idempotent() {
        resolver.confirmMapping(source().agreementCompanyUuid, 2104, "TESTCODE", "test-consultant", "it");

        List<SelfBilledImportService.RawLine> raw = List.of(
                new SelfBilledImportService.RawLine(2104, 1771, 990001L, LocalDate.of(2025, 11, 1),
                        "Faktura: 99 - 07-2025 TESTCODE", new BigDecimal("-55963.44")),
                new SelfBilledImportService.RawLine(2104, 1771, 990002L, LocalDate.of(2025, 12, 1),
                        "Bogført 2 x ", new BigDecimal("0.00")));

        int n1 = service.processLines(source(), raw);
        assertEquals(2, n1);

        SelfBilledLine main = SelfBilledLine.findByEntry(990001L);
        SelfBilledLine corr = SelfBilledLine.findByEntry(990002L);
        // Workflow status (not parse state): captured, non-zero voucher -> UNASSIGNED on every sibling.
        assertEquals(SelfBilledLineStatus.UNASSIGNED, main.status);
        assertEquals(SelfBilledLineStatus.UNASSIGNED, corr.status);
        // Suggestions still voucher-stamped on every sibling (F1 behaviour kept).
        assertEquals(2025, main.workYear); assertEquals(7, main.workMonth);
        assertEquals(2025, corr.workYear); assertEquals(7, corr.workMonth);
        assertEquals("test-consultant", main.consultantUuid);
        // NEW: booking date persisted per line.
        assertEquals(LocalDate.of(2025, 11, 1), main.bookingDate);
        assertEquals(LocalDate.of(2025, 12, 1), corr.bookingDate);

        int n2 = service.processLines(source(), raw);
        assertEquals(2, n2);
        assertEquals(2, SelfBilledLine.count("voucherNumber", 1771));
    }

    @Test
    @TestTransaction
    void recapture_preserves_human_status_and_assignments() {
        List<SelfBilledImportService.RawLine> raw = List.of(
                new SelfBilledImportService.RawLine(2104, 1772, 990010L, LocalDate.of(2025, 10, 1),
                        "Faktura: 100 - 08-2025 TESTCODE", new BigDecimal("-153525.00")));
        service.processLines(source(), raw);

        SelfBilledLine line = SelfBilledLine.findByEntry(990010L);
        line.status = SelfBilledLineStatus.ASSIGNED;                  // simulate a human assignment

        SelfBilledAssignment assignment = new SelfBilledAssignment();
        assignment.uuid = UUID.randomUUID().toString();
        assignment.selfbilledLineUuid = line.uuid;
        assignment.consultantUuid = "test-consultant";
        assignment.workYear = 2025;
        assignment.workMonth = 8;
        assignment.shareAmount = new BigDecimal("-153525.00");
        assignment.assignedBy = "it";
        assignment.assignedAt = LocalDateTime.now();
        assignment.source = AssignmentSourceType.HUMAN;
        assignment.persist();

        service.processLines(source(), raw);                         // re-capture
        assertEquals(SelfBilledLineStatus.ASSIGNED, SelfBilledLine.findByEntry(990010L).status,
                "re-capture must never downgrade a human status");
        assertEquals(1, SelfBilledAssignment.count(), "capture never touches assignments");
        SelfBilledAssignment reloaded = SelfBilledAssignment.findById(assignment.uuid);
        assertNotNull(reloaded);
        assertEquals(0, new BigDecimal("-153525.00").compareTo(reloaded.shareAmount),
                "capture must not alter an existing assignment's share amount");
    }

    @Test
    @TestTransaction
    void human_ignored_survives_stable_recapture_and_reopens_on_net_change() {
        List<SelfBilledImportService.RawLine> raw = List.of(
                new SelfBilledImportService.RawLine(2104, 1774, 990030L, LocalDate.of(2025, 8, 1),
                        "Faktura: 102 - 07-2025 TESTCODE", new BigDecimal("-9200.00")));
        service.processLines(source(), raw);

        SelfBilledLine line = SelfBilledLine.findByEntry(990030L);
        line.status = SelfBilledLineStatus.IGNORED;                   // simulate human "Ignore"

        service.processLines(source(), raw);                          // stable re-capture (net unchanged)
        assertEquals(SelfBilledLineStatus.IGNORED, SelfBilledLine.findByEntry(990030L).status,
                "human IGNORED must survive a re-capture that does not change the voucher net");

        List<SelfBilledImportService.RawLine> rawChanged = List.of(
                raw.get(0),
                new SelfBilledImportService.RawLine(2104, 1774, 990031L, LocalDate.of(2025, 8, 15),
                        "Korrektion", new BigDecimal("4200.00")));
        service.processLines(source(), rawChanged);                   // net changed -> re-opens
        assertEquals(SelfBilledLineStatus.UNASSIGNED, SelfBilledLine.findByEntry(990030L).status,
                "a net change must re-open the voucher for human review");
    }

    @Test
    @TestTransaction
    void net_zero_voucher_is_auto_ignored() {
        List<SelfBilledImportService.RawLine> raw = List.of(
                new SelfBilledImportService.RawLine(2104, 1773, 990020L, LocalDate.of(2025, 9, 1),
                        "Faktura: 101 - 08-2025 TESTCODE", new BigDecimal("-1000.00")),
                new SelfBilledImportService.RawLine(2104, 1773, 990021L, LocalDate.of(2025, 9, 2),
                        "Forkert kunde", new BigDecimal("1000.00")));
        service.processLines(source(), raw);
        assertEquals(SelfBilledLineStatus.IGNORED, SelfBilledLine.findByEntry(990020L).status);
        assertEquals(SelfBilledLineStatus.IGNORED, SelfBilledLine.findByEntry(990021L).status);
    }
}
