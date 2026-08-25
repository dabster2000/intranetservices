package dk.trustworks.intranet.hrletters.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterType;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit test (no DB / no @QuarkusTest) for the pure helpers behind the
 * HR letters flows: vacation-year derivation, day validation, the generated
 * document filenames and the display name the console shows.
 */
class HrLetterServiceTest {

    // ── Vacation year derivation ───────────────────────────────────────────

    @Test
    void defaultFromYear_isTheVacationYearWhoseHoldingPeriodEndsThisDecember() {
        // The vacation year 1 Sep 2025 – 31 Aug 2026 holds until 31 Dec 2026,
        // so anywhere in calendar 2026 the transferable year starts in 2025.
        assertEquals(2025, HrLetterService.defaultFromStartYear(LocalDate.of(2026, 12, 2)));
        assertEquals(2025, HrLetterService.defaultFromStartYear(LocalDate.of(2026, 1, 15)));
        // The December-2025 example agreement: from 2024/2025 to 2025/2026.
        assertEquals(2024, HrLetterService.defaultFromStartYear(LocalDate.of(2025, 12, 2)));
    }

    @Test
    void vacationYearLabel_rendersStartSlashEnd() {
        assertEquals("2024/2025", HrLetterService.vacationYearLabel(2024));
    }

    @Test
    void fromYearValidation_allowsOneYearOfSlack_rejectsBeyond() {
        LocalDate today = LocalDate.of(2026, 12, 2);
        assertDoesNotThrow(() -> HrLetterService.validateFromYear(2025, today));
        assertDoesNotThrow(() -> HrLetterService.validateFromYear(2024, today));
        assertDoesNotThrow(() -> HrLetterService.validateFromYear(2026, today));
        assertThrows(BadRequestException.class, () -> HrLetterService.validateFromYear(2023, today));
        assertThrows(BadRequestException.class, () -> HrLetterService.validateFromYear(2027, today));
    }

    // ── Day validation & rendering ─────────────────────────────────────────

    @Test
    void transferDays_acceptHalfDayStepsUpToTen() {
        assertDoesNotThrow(() -> HrLetterService.validateTransferDays(0.5));
        assertDoesNotThrow(() -> HrLetterService.validateTransferDays(2));
        assertDoesNotThrow(() -> HrLetterService.validateTransferDays(9.5));
        assertDoesNotThrow(() -> HrLetterService.validateTransferDays(10));
    }

    @Test
    void transferDays_rejectZeroNegativeQuarterAndBeyondTen() {
        assertThrows(BadRequestException.class, () -> HrLetterService.validateTransferDays(0));
        assertThrows(BadRequestException.class, () -> HrLetterService.validateTransferDays(-1));
        assertThrows(BadRequestException.class, () -> HrLetterService.validateTransferDays(2.25));
        assertThrows(BadRequestException.class, () -> HrLetterService.validateTransferDays(10.5));
    }

    @Test
    void formatDays_wholeNumbersBare_halvesWithDanishComma() {
        assertEquals("2", HrLetterService.formatDays(2.0));
        assertEquals("2,5", HrLetterService.formatDays(2.5));
        assertEquals("10", HrLetterService.formatDays(10));
    }

    // ── Filenames ──────────────────────────────────────────────────────────

    @Test
    void letterFilename_mirrorsTheExistingCorpusNaming() {
        LocalDate date = LocalDate.of(2025, 12, 2);
        assertEquals("2025-12-02_SALARY_loenregulering.pdf",
                HrLetterService.letterFilename(HrLetterType.SALARY_REGULATION, "Lønregulering.pdf", date));
        assertEquals("2025-12-02_VACATION_ferieoverfoersel.pdf",
                HrLetterService.letterFilename(HrLetterType.VACATION_TRANSFER, "Ferieoverførsel", date));
    }

    @Test
    void letterFilename_survivesBlankDocumentNames() {
        LocalDate date = LocalDate.of(2026, 3, 1);
        assertEquals("2026-03-01_SALARY_salary.pdf",
                HrLetterService.letterFilename(HrLetterType.SALARY_REGULATION, null, date));
        assertEquals("2026-03-01_VACATION_vacation.pdf",
                HrLetterService.letterFilename(HrLetterType.VACATION_TRANSFER, "  ", date));
    }

    // ── Display name ───────────────────────────────────────────────────────

    @Test
    void displayName_joinsFirstAndLast() {
        assertEquals("Alma Bech", HrLetterService.displayName(user("Alma", "Bech", "alma.bech")));
    }

    @Test
    void displayName_neverPrintsTheWordNull() {
        // User.getFullname() concatenates unconditionally — "Alma null".
        assertEquals("Alma", HrLetterService.displayName(user("Alma", null, "alma.bech")));
        assertEquals("Bech", HrLetterService.displayName(user(null, "Bech", "alma.bech")));
    }

    @Test
    void displayName_fallsBackToUsername_thenToNull() {
        assertEquals("alma.bech", HrLetterService.displayName(user("  ", "", "alma.bech")));
        assertNull(HrLetterService.displayName(user(null, null, null)));
        assertNull(HrLetterService.displayName(user("", "", "   ")));
        assertNull(HrLetterService.displayName(null));
    }

    private static User user(String firstname, String lastname, String username) {
        User user = new User();
        user.setFirstname(firstname);
        user.setLastname(lastname);
        user.setUsername(username);
        return user;
    }
}
