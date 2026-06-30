package dk.trustworks.intranet.apigateway.support;

import dk.trustworks.intranet.apigateway.support.CareerMultiplierResolver.Representative;
import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests — no Quarkus, no DB. Covers fiscal-month mapping, MONTH-END
 * level resolution (incl. mid-month activeFrom), and the §3 representative rule.
 */
class CareerMultiplierResolverTest {

    private static final String FRANK = "frank-uuid";

    private static UserCareerLevel level(String useruuid, LocalDate activeFrom, CareerLevel careerLevel) {
        return new UserCareerLevel(useruuid, activeFrom, null, careerLevel);
    }

    // ---- fiscalMonthToCalendar ----

    @Test void fiscalMonthToCalendar_mapsFiscalOrderToCalendar() {
        // FY = 2024 → Jul2024 .. Jun2025
        assertArrayEquals(new int[]{2024, 7}, CareerMultiplierResolver.fiscalMonthToCalendar(2024, 0));
        assertArrayEquals(new int[]{2024, 12}, CareerMultiplierResolver.fiscalMonthToCalendar(2024, 5));
        assertArrayEquals(new int[]{2025, 1}, CareerMultiplierResolver.fiscalMonthToCalendar(2024, 6));
        assertArrayEquals(new int[]{2025, 6}, CareerMultiplierResolver.fiscalMonthToCalendar(2024, 11));
    }

    // ---- month-end resolution: mid-month activeFrom ----

    @Test void midMonthActiveFrom_isActiveForThatMonthButNotEarlier() {
        // activeFrom = 2025-03-15 → resolved at month-end 2025-03-31 (active for March),
        // but NOT for February (resolved at 2025-02-28).
        List<UserCareerLevel> sorted = CareerMultiplierResolver.sortAscending(List.of(
                level(FRANK, LocalDate.of(2025, 3, 15), CareerLevel.MANAGER)));

        // February of FY2024 → calendar 2025-02.
        assertNull(CareerMultiplierResolver.levelAtMonthEnd(sorted, 2025, 2),
                "mid-March record must NOT be active at end of February");
        // March of FY2024 → calendar 2025-03.
        assertEquals(CareerLevel.MANAGER, CareerMultiplierResolver.levelAtMonthEnd(sorted, 2025, 3),
                "mid-March record MUST be active at end of March");
    }

    // ---- greatest activeFrom ≤ monthEnd wins ----

    @Test void greatestActiveFromBeforeMonthEndWins() {
        List<UserCareerLevel> sorted = CareerMultiplierResolver.sortAscending(List.of(
                level(FRANK, LocalDate.of(2024, 7, 1), CareerLevel.MANAGER),
                level(FRANK, LocalDate.of(2025, 4, 1), CareerLevel.PARTNER)));

        // End of March 2025: only the MANAGER record applies.
        assertEquals(CareerLevel.MANAGER, CareerMultiplierResolver.levelAtMonthEnd(sorted, 2025, 3));
        // End of April 2025: the PARTNER record (greatest activeFrom ≤ monthEnd) wins.
        assertEquals(CareerLevel.PARTNER, CareerMultiplierResolver.levelAtMonthEnd(sorted, 2025, 4));
        // End of June 2025: still PARTNER.
        assertEquals(CareerLevel.PARTNER, CareerMultiplierResolver.levelAtMonthEnd(sorted, 2025, 6));
    }

    @Test void nullActiveFromRecordsAreDropped() {
        List<UserCareerLevel> sorted = CareerMultiplierResolver.sortAscending(List.of(
                level(FRANK, null, CareerLevel.MANAGER),
                level(FRANK, LocalDate.of(2024, 7, 1), CareerLevel.CONSULTANT)));
        assertEquals(1, sorted.size());
        assertEquals(CareerLevel.CONSULTANT, CareerMultiplierResolver.levelAtMonthEnd(sorted, 2024, 7));
    }

    // ---- no record at all ----

    @Test void noRecord_levelIsNull_multiplierIsOne() {
        List<UserCareerLevel> sorted = CareerMultiplierResolver.sortAscending(List.of());
        assertNull(CareerMultiplierResolver.levelAtMonthEnd(sorted, 2024, 7));

        double[] mults = CareerMultiplierResolver.monthlyMultipliers(sorted, 2024);
        String[] names = CareerMultiplierResolver.monthlyLevelNames(sorted, 2024);
        for (int m = 0; m < 12; m++) {
            assertEquals(1.0, mults[m], "month " + m + " multiplier");
            assertEquals("", names[m], "month " + m + " level name");
        }
    }

    // ---- Frank: MANAGER Jul–Mar, PARTNER Apr–Jun → monthly mults [2×9, 0×3] ----

    @Test void frank_monthlyMultipliers_managerThenPartner() {
        List<UserCareerLevel> sorted = CareerMultiplierResolver.sortAscending(List.of(
                level(FRANK, LocalDate.of(2024, 7, 1), CareerLevel.MANAGER),
                level(FRANK, LocalDate.of(2025, 4, 1), CareerLevel.PARTNER)));

        double[] mults = CareerMultiplierResolver.monthlyMultipliers(sorted, 2024);
        assertArrayEquals(
                new double[]{2, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0, 0},
                mults);

        String[] names = CareerMultiplierResolver.monthlyLevelNames(sorted, 2024);
        for (int m = 0; m < 9; m++) {
            assertEquals("MANAGER", names[m], "month " + m);
        }
        for (int m = 9; m < 12; m++) {
            assertEquals("PARTNER", names[m], "month " + m);
        }
    }

    // ---- representative fallback (Frank): latest weighted month is 0× → fall back to MANAGER ----

    @Test void representative_frankFallsBackToManager() {
        double[] weights = new double[12];
        for (int m = 0; m < 12; m++) {
            weights[m] = 50_000;
        }
        double[] mults = {2, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0, 0};
        String[] names = {
                "MANAGER", "MANAGER", "MANAGER", "MANAGER", "MANAGER", "MANAGER",
                "MANAGER", "MANAGER", "MANAGER", "PARTNER", "PARTNER", "PARTNER"};

        Representative rep = CareerMultiplierResolver.representative(weights, mults, names);
        assertEquals("MANAGER", rep.levelName());
        assertEquals(2.0, rep.multiplier());
    }

    // ---- representative happy path: latest weighted month is eligible ----

    @Test void representative_usesLatestWeightedEligibleMonth() {
        double[] weights = new double[12];
        for (int m = 0; m < 12; m++) {
            weights[m] = 50_000;
        }
        // Promotion FROM 0×: PARTNER Jul–Sep then MANAGER Oct–Jun.
        double[] mults = {0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2};
        String[] names = {
                "PARTNER", "PARTNER", "PARTNER", "MANAGER", "MANAGER", "MANAGER",
                "MANAGER", "MANAGER", "MANAGER", "MANAGER", "MANAGER", "MANAGER"};

        Representative rep = CareerMultiplierResolver.representative(weights, mults, names);
        assertEquals("MANAGER", rep.levelName());
        assertEquals(2.0, rep.multiplier());
    }

    // ---- representative: all-0× while weighted → ("", 0.0) ----

    @Test void representative_allZeroMultiplier_isEmptyAndZero() {
        double[] weights = new double[12];
        for (int m = 0; m < 12; m++) {
            weights[m] = 50_000;
        }
        double[] mults = new double[12]; // all 0
        String[] names = {
                "PARTNER", "PARTNER", "PARTNER", "PARTNER", "PARTNER", "PARTNER",
                "PARTNER", "PARTNER", "PARTNER", "PARTNER", "PARTNER", "PARTNER"};

        Representative rep = CareerMultiplierResolver.representative(weights, mults, names);
        assertEquals("", rep.levelName());
        assertEquals(0.0, rep.multiplier());
    }

    @Test void representative_noWeightAtAll_isEmptyAndZero() {
        double[] weights = new double[12]; // all 0
        double[] mults = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        String[] names = new String[12];
        for (int m = 0; m < 12; m++) {
            names[m] = "CONSULTANT";
        }
        Representative rep = CareerMultiplierResolver.representative(weights, mults, names);
        assertEquals("", rep.levelName());
        assertEquals(0.0, rep.multiplier());
    }
}
