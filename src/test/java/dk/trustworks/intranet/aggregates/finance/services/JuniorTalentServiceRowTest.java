package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.JuniorTalentDTO.JuniorRow;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO.MemberCapacity;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO.WeekCell;
import dk.trustworks.intranet.aggregates.userprofile.model.UserProfileExtension;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §4.7.1 (2): availability = declared − contract budget − internal, with the junior's own
 * declared week as the denominator; undeclared days are reported, never shown as 100 % or 0 %
 * free (D7); orlov renders as on leave, not as a percentage. §4.7.3: no education text leaks.
 */
class JuniorTalentServiceRowTest {

    private static WeekCell week(LocalDate start, Double declared, int undeclared, double contract, double internal) {
        return new WeekCell(start, declared, undeclared, 0, 0, 0, 0, 0, contract, internal,
                declared == null ? 0 : Math.max(declared - contract - internal, 0));
    }

    private static final LocalDate W1 = LocalDate.of(2026, 9, 7);
    private static final LocalDate W2 = LocalDate.of(2026, 9, 14);

    @Test
    void freePercentUsesTheDeclaredWeekAsDenominator() {
        MemberCapacity m = new MemberCapacity("u1", "Casper", "Hansen", List.of(
                week(W1, 20.0, 0, 8.0, 4.0),
                week(W2, 20.0, 0, 8.0, 0.0)));
        JuniorRow row = JuniorTalentService.row(m, null, List.of(), "Junior Team", List.of("Novo"), false);

        assertEquals(40.0, row.declaredHours(), 1e-9);
        assertEquals(16.0, row.contractBudgetHours(), 1e-9);
        assertEquals(4.0, row.internalBudgetHours(), 1e-9);
        assertEquals(20.0, row.freeHours(), 1e-9);
        assertEquals(50.0, row.freePercent(), 1e-9);
        assertEquals(0, row.undeclaredWorkingDays());
        assertFalse(row.onLeave());
        assertEquals(List.of("Novo"), row.currentClients());
    }

    @Test
    void anUndeclaredWindowIsNoPlanNotAHundredPercentFree() {
        MemberCapacity m = new MemberCapacity("u1", "A", "B", List.of(
                week(W1, null, 5, 7.4, 0.0),
                week(W2, null, 5, 0.0, 0.0)));
        JuniorRow row = JuniorTalentService.row(m, null, List.of(), null, List.of(), false);

        assertNull(row.declaredHours());
        assertNull(row.freePercent());
        assertEquals(0.0, row.freeHours(), 1e-9);
        assertEquals(10, row.undeclaredWorkingDays());
    }

    @Test
    void partiallyDeclaredWindowCountsTheOpenDays() {
        MemberCapacity m = new MemberCapacity("u1", "A", "B", List.of(
                week(W1, 12.0, 2, 4.0, 0.0),
                week(W2, null, 5, 0.0, 0.0)));
        JuniorRow row = JuniorTalentService.row(m, null, List.of(), null, List.of(), false);
        assertEquals(12.0, row.declaredHours(), 1e-9);
        assertEquals(7, row.undeclaredWorkingDays());
        assertEquals(8.0, row.freeHours(), 1e-9);
    }

    @Test
    void onLeaveHasNoPercentageAndOverbookedIsNeverNegative() {
        MemberCapacity m = new MemberCapacity("u1", "A", "B", List.of(week(W1, 10.0, 0, 30.0, 0.0)));
        JuniorRow leave = JuniorTalentService.row(m, null, List.of(), null, List.of(), true);
        assertTrue(leave.onLeave());
        assertNull(leave.freePercent());
        assertEquals(0.0, leave.freeHours(), 1e-9);
    }

    @Test
    void profileFactsRideAlongButEducationTextNever() {
        UserProfileExtension p = new UserProfileExtension();
        p.setEducation("Cand.merc. CBS — secret detail");
        p.setStudyLevel("KANDIDAT");
        p.setExpectedGraduation(LocalDate.of(2027, 6, 30));
        p.setPrimaryDiscipline("DEV");
        MemberCapacity m = new MemberCapacity("u1", "A", "B", List.of(week(W1, 10.0, 0, 0.0, 0.0)));
        JuniorRow row = JuniorTalentService.row(m, p, List.of(), null, List.of(), false);

        assertEquals("KANDIDAT", row.studyLevel());
        assertEquals(LocalDate.of(2027, 6, 30), row.expectedGraduation());
        assertEquals("DEV", row.primaryDiscipline());
        assertFalse(row.toString().contains("secret detail"));
    }
}
