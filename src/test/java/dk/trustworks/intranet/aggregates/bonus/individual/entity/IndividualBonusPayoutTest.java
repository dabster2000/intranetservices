package dk.trustworks.intranet.aggregates.bonus.individual.entity;

import dk.trustworks.intranet.aggregates.bonus.individual.model.MaterializedPayoutCommand;
import dk.trustworks.intranet.aggregates.bonus.individual.model.PayoutKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure unit test for the reproducibility snapshot factory {@code IndividualBonusPayout.snapshot} — no
 * Quarkus boot required. Verifies the effective spec + resolved inputs are frozen field-for-field so a
 * past payout stays replayable after the rule is later edited/soft-deleted.
 */
class IndividualBonusPayoutTest {

    @Test
    void snapshot_freezesSpecAndInputsFromCommand() {
        String specJson = "{\"basis\":\"OWN_INVOICED_REVENUE\",\"schedule\":{\"cadence\":\"YEARLY\"}}";
        MaterializedPayoutCommand cmd = new MaterializedPayoutCommand(
                "rule-1", "user-9", "Produktionsbonus",
                LocalDate.of(2027, 7, 1), PayoutKind.YEARLY, BigDecimal.valueOf(675_000),
                false, "individual:rule-1:yearly:2026_2027",
                specJson, BigDecimal.valueOf(3_000_000), 12);

        IndividualBonusPayout snap = IndividualBonusPayout.snapshot(cmd);

        assertNotNull(snap.getUuid(), "a fresh uuid must be minted");
        assertEquals("individual:rule-1:yearly:2026_2027", snap.getSourceReference());
        assertEquals("rule-1", snap.getRuleUuid());
        assertEquals("user-9", snap.getUserUuid());
        assertEquals(LocalDate.of(2027, 7, 1), snap.getMonth());
        assertEquals("YEARLY", snap.getKind());
        assertEquals(0, snap.getAmount().compareTo(BigDecimal.valueOf(675_000)));
        assertEquals(specJson, snap.getSpecJson());
        assertEquals(0, snap.getBasisAmount().compareTo(BigDecimal.valueOf(3_000_000)));
        assertEquals(12, snap.getMonthsEmployed());
        assertNotNull(snap.getCreatedAt(), "created_at must be stamped");
    }

    @Test
    void snapshot_mapsFinalSettlementKindName() {
        MaterializedPayoutCommand cmd = new MaterializedPayoutCommand(
                "rule-2", "user-2", "Produktionsbonus",
                LocalDate.of(2027, 3, 1), PayoutKind.FINAL_SETTLEMENT, BigDecimal.valueOf(50_000),
                false, "individual:rule-2:trueup:2026_2027",
                "{}", BigDecimal.ZERO, 8);

        assertEquals("FINAL_SETTLEMENT", IndividualBonusPayout.snapshot(cmd).getKind());
    }
}
