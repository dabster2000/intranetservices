package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLine;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    void processLines_nets_corrections_into_voucher_period_and_is_idempotent() {
        // Map TESTCODE so the voucher resolves (issuer may be null in test — irrelevant to this assertion).
        resolver.confirmMapping(source().agreementCompanyUuid, 2104, "TESTCODE", "test-consultant", "it");

        // One voucher (1771): a parseable Faktura line + a "Bogført 2 x" correction sharing it.
        List<SelfBilledImportService.RawLine> raw = List.of(
                new SelfBilledImportService.RawLine(2104, 1771, 990001L, LocalDate.of(2025, 11, 1),
                        "Faktura: 99 - 07-2025 TESTCODE", new BigDecimal("-55963.44")),
                new SelfBilledImportService.RawLine(2104, 1771, 990002L, LocalDate.of(2025, 12, 1),
                        "Bogført 2 x ", new BigDecimal("0.00")));

        int n1 = service.processLines(source(), raw);
        assertEquals(2, n1);

        // F1 fix: the correction sibling inherits the voucher's work period (not null).
        SelfBilledLine correction = SelfBilledLine.findByEntry(990002L);
        assertEquals(Integer.valueOf(2025), correction.workYear, "F1: correction 990002 must inherit voucher work-year from its parseable sibling");
        assertEquals(Integer.valueOf(7), correction.workMonth, "F1: correction 990002 must inherit voucher work-month");
        assertEquals("TESTCODE", correction.code, "F1: correction 990002 must inherit voucher code");

        // Idempotent: re-running keeps one row per entry.
        service.processLines(source(), raw);
        assertEquals(1, SelfBilledLine.count("entryNumber", 990001L));
        assertEquals(1, SelfBilledLine.count("entryNumber", 990002L));
    }
}
