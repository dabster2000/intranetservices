package dk.trustworks.intranet.contracts.audit;

import dk.trustworks.intranet.contracts.model.ContractTypeAudit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the audit listener's classification/diff helpers and the read
 * service's limit clamping. No Quarkus, no database.
 */
class ContractTypeAuditLogicTest {

    @Nested
    class ClassifyOperation {

        @Test
        void activeTrueToFalse_isDelete() {
            assertEquals(ContractTypeAudit.Operation.DELETE, ContractTypeAuditListener.classifyOperation(true, false));
        }

        @Test
        void activeFalseToTrue_isRestore() {
            assertEquals(ContractTypeAudit.Operation.RESTORE, ContractTypeAuditListener.classifyOperation(false, true));
        }

        @Test
        void activeUnchanged_isUpdate() {
            assertEquals(ContractTypeAudit.Operation.UPDATE, ContractTypeAuditListener.classifyOperation(true, true));
            assertEquals(ContractTypeAudit.Operation.UPDATE, ContractTypeAuditListener.classifyOperation(false, false));
        }
    }

    @Nested
    class NormalizeChangedBy {

        @Test
        void nullBlankAndAnonymous_becomeNull() {
            assertNull(ContractTypeAuditListener.normalizeChangedBy(null));
            assertNull(ContractTypeAuditListener.normalizeChangedBy(""));
            assertNull(ContractTypeAuditListener.normalizeChangedBy("   "));
            assertNull(ContractTypeAuditListener.normalizeChangedBy("anonymous"));
        }

        @Test
        void uuidAndSystemPrincipals_passThrough() {
            assertEquals("7948bd8f-1111-2222-3333-444455556666",
                    ContractTypeAuditListener.normalizeChangedBy("7948bd8f-1111-2222-3333-444455556666"));
            assertEquals("system:autofix-worker",
                    ContractTypeAuditListener.normalizeChangedBy("system:autofix-worker"));
        }
    }

    @Nested
    class DiffBuilding {

        @Test
        void unchangedValues_produceNoEntry() {
            List<String> changes = new ArrayList<>();
            ContractTypeAuditListener.addChange(changes, "name", "Same", "Same");
            ContractTypeAuditListener.addChange(changes, "validFrom", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));
            ContractTypeAuditListener.addChange(changes, "priority", 10, 10);
            ContractTypeAuditListener.addChange(changes, "amount", null, null);
            assertTrue(changes.isEmpty());
        }

        @Test
        void changedValues_areFormattedAsOldArrowNew() {
            List<String> changes = new ArrayList<>();
            ContractTypeAuditListener.addChange(changes, "active", true, false);
            ContractTypeAuditListener.addChange(changes, "name", "Old name", "New name");
            ContractTypeAuditListener.addChange(changes, "validTo", null, LocalDate.of(2027, 6, 30));
            assertEquals(List.of(
                    "active: true -> false",
                    "name: 'Old name' -> 'New name'",
                    "validTo: null -> 2027-06-30"
            ), changes);
        }

        @Test
        void bigDecimals_compareNumerically_ignoringDbScalePadding() {
            // DB returns DECIMAL(10,4) as 5.0000; entity holds 5.0 — not a change.
            List<String> changes = new ArrayList<>();
            ContractTypeAuditListener.addChange(changes, "percent", new BigDecimal("5.0000"), new BigDecimal("5.0"));
            assertTrue(changes.isEmpty());

            ContractTypeAuditListener.addChange(changes, "percent", new BigDecimal("5.0000"), new BigDecimal("7.5"));
            assertEquals(List.of("percent: 5 -> 7.5"), changes);
        }

        @Test
        void nullToValueAndValueToNull_areRecorded() {
            List<String> changes = new ArrayList<>();
            ContractTypeAuditListener.addChange(changes, "paramKey", null, "trapperabat");
            ContractTypeAuditListener.addChange(changes, "amount", new BigDecimal("100.00"), null);
            assertEquals(List.of(
                    "paramKey: null -> 'trapperabat'",
                    "amount: 100 -> null"
            ), changes);
        }
    }

    @Nested
    class Truncation {

        @Test
        void shortAndNullSummaries_areUntouched() {
            assertNull(ContractTypeAuditListener.truncate(null));
            assertEquals("active: true -> false", ContractTypeAuditListener.truncate("active: true -> false"));
        }

        @Test
        void longSummaries_areCappedAt1000Chars() {
            String summary = "x".repeat(1500);
            String truncated = ContractTypeAuditListener.truncate(summary);
            assertEquals(1000, truncated.length());
            assertTrue(truncated.endsWith("…"));
        }
    }

    @Nested
    class LimitClamping {

        @Test
        void defaultAndInRangeValues_passThrough() {
            assertEquals(100, ContractTypeAuditService.clampLimit(100));
            assertEquals(1, ContractTypeAuditService.clampLimit(1));
            assertEquals(500, ContractTypeAuditService.clampLimit(500));
        }

        @Test
        void outOfRangeValues_areClamped() {
            assertEquals(1, ContractTypeAuditService.clampLimit(0));
            assertEquals(1, ContractTypeAuditService.clampLimit(-25));
            assertEquals(500, ContractTypeAuditService.clampLimit(501));
            assertEquals(500, ContractTypeAuditService.clampLimit(Integer.MAX_VALUE));
        }
    }
}
