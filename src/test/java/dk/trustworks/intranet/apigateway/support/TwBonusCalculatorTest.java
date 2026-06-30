package dk.trustworks.intranet.apigateway.support;

import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO.CompanyBonusDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO.EmployeeBonusDTO;
import dk.trustworks.intranet.apigateway.support.TwBonusCalculator.PoolMeta;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worked-example reconciliation for {@link TwBonusCalculator}.
 * Plain JUnit 5 — no Quarkus, no DB. All money assertions are TO THE KRONE
 * against the frozen fixtures (§ "Worked-example fixtures").
 */
class TwBonusCalculatorTest {

    private static final int FY = 2025;

    // ---- Input builders ---------------------------------------------------

    /** 12 equal monthly weights that sum (approximately, via 12×) to {@code total}. */
    private static double[] evenMonths(double total) {
        double[] months = new double[12];
        Arrays.fill(months, total / 12.0);
        return months;
    }

    /** 12 monthly weights all equal to {@code perMonth}. */
    private static double[] flatMonths(double perMonth) {
        double[] months = new double[12];
        Arrays.fill(months, perMonth);
        return months;
    }

    /** 12 multipliers all equal to {@code mult}. */
    private static double[] flatMults(double mult) {
        double[] m = new double[12];
        Arrays.fill(m, mult);
        return m;
    }

    private static String[] flatLevels(String name) {
        String[] n = new String[12];
        Arrays.fill(n, name);
        return n;
    }

    private static PoolMeta poolMeta(double pool) {
        // bonusPercent 10 ⇒ profitBeforeTax = pool*10; extraPool 0 keeps pool == profit*0.1.
        return new PoolMeta(pool * 10.0, 10.0, 0.0);
    }

    private static EmployeeBonusDTO employee(TwBonusCalculationDTO dto, String uuid) {
        return dto.getEmployees().stream()
                .filter(e -> e.getUseruuid().equals(uuid))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing employee " + uuid));
    }

    private static CompanyBonusDTO company(TwBonusCalculationDTO dto, String uuid) {
        return dto.getCompanies().stream()
                .filter(c -> c.getCompanyUuid().equals(uuid))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing company " + uuid));
    }

    // ---- 4.1 single company "A", pool 1_000_000 --------------------------

    @Test
    void singleCompany_appliesNonNullSumMultipliers_toTheKrone() {
        Map<String, Map<String, double[]>> userCompanyMonths = new LinkedHashMap<>();
        Map<String, double[]> userMultipliers = new HashMap<>();
        Map<String, String[]> userLevelNames = new HashMap<>();
        Map<String, String> fullNames = new HashMap<>();

        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "alice", "Alice", "A", evenMonths(600_000), 1.0, "CONSULTANT");
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "bob", "Bob", "A", evenMonths(800_000), 1.5, "ENGAGEMENT_MANAGER");
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "carol", "Carol", "A", evenMonths(1_000_000), 2.0, "MANAGER");
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "dave", "Dave", "A", evenMonths(500_000), 3.0, "ENGAGEMENT_DIRECTOR");
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "eve", "Eve", "A", evenMonths(1_200_000), 0.0, "PARTNER");

        Map<String, Double> poolByCompany = Map.of("A", 1_000_000.0);
        Map<String, String> companyNames = Map.of("A", "Alpha");
        Map<String, PoolMeta> poolMetaByCompany = Map.of("A", poolMeta(1_000_000));

        TwBonusCalculationDTO dto = TwBonusCalculator.calculate(
                FY, userCompanyMonths, userMultipliers, userLevelNames,
                fullNames, poolByCompany, companyNames, poolMetaByCompany);

        // Per-employee payouts.
        assertEquals(206_897, employee(dto, "alice").getTotalPayout());
        assertEquals(413_793, employee(dto, "bob").getTotalPayout());
        assertEquals(689_655, employee(dto, "carol").getTotalPayout());
        assertEquals(517_241, employee(dto, "dave").getTotalPayout());

        // Eve: 0× → base 0, payout 0, careerIneligible, still present.
        EmployeeBonusDTO eve = employee(dto, "eve");
        assertEquals(0, eve.getTotalPayout());
        assertEquals(0.0, eve.getTotalWeightSum());
        assertTrue(eve.isCareerIneligible());

        // Eligible base weights (delta tolerates float division 800000/12 etc.;
        // all KRONE-level outputs below are Math.round'd and asserted exactly).
        assertEquals(600_000.0, employee(dto, "alice").getTotalWeightSum(), 1e-6);
        assertEquals(800_000.0, employee(dto, "bob").getTotalWeightSum(), 1e-6);
        assertFalse(employee(dto, "carol").isCareerIneligible());

        // Company aggregates.
        CompanyBonusDTO a = company(dto, "A");
        assertEquals(2_900_000.0, a.getTotalWeight(), 1e-6);
        assertEquals(827_586, a.getCareerExtra());
        assertEquals(1_827_586, a.getTotalPayout());
        assertEquals(4, a.getEligibleCount());
        assertEquals(0.34482758620689655, a.getFactor(), 1e-7);

        // Run totals.
        assertEquals("A", dto.getWinningCompanyUuid());
        assertEquals(0.34482758620689655, dto.getAppliedFactor(), 1e-7);
        assertEquals(1_827_586.0, dto.getProjectedTotalPayout());

        // Representative metadata.
        assertEquals(2.0, employee(dto, "carol").getMultiplier());
        assertEquals("MANAGER", employee(dto, "carol").getRepresentativeCareerLevel());
    }

    // ---- 4.3 cross-company: A (winner) + B (all 1×) ----------------------

    @Test
    void crossCompany_winnerFactorAppliedToBoth_toTheKrone() {
        Map<String, Map<String, double[]>> userCompanyMonths = new LinkedHashMap<>();
        Map<String, double[]> userMultipliers = new HashMap<>();
        Map<String, String[]> userLevelNames = new HashMap<>();
        Map<String, String> fullNames = new HashMap<>();

        // Company A — identical to 4.1 (base 2_900_000, eff 5_300_000).
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "alice", "Alice", "A", evenMonths(600_000), 1.0, "CONSULTANT");
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "bob", "Bob", "A", evenMonths(800_000), 1.5, "ENGAGEMENT_MANAGER");
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "carol", "Carol", "A", evenMonths(1_000_000), 2.0, "MANAGER");
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "dave", "Dave", "A", evenMonths(500_000), 3.0, "ENGAGEMENT_DIRECTOR");

        // Company B — pool 500_000, base 2_000_000, all 1× (eff 2_000_000).
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "frank", "Frank", "B", evenMonths(1_200_000), 1.0, "CONSULTANT");
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "grace", "Grace", "B", evenMonths(800_000), 1.0, "CONSULTANT");

        Map<String, Double> poolByCompany = Map.of("A", 1_000_000.0, "B", 500_000.0);
        Map<String, String> companyNames = Map.of("A", "Alpha", "B", "Beta");
        Map<String, PoolMeta> poolMetaByCompany =
                Map.of("A", poolMeta(1_000_000), "B", poolMeta(500_000));

        TwBonusCalculationDTO dto = TwBonusCalculator.calculate(
                FY, userCompanyMonths, userMultipliers, userLevelNames,
                fullNames, poolByCompany, companyNames, poolMetaByCompany);

        // A wins; F = 0.3448275862.
        assertEquals("A", dto.getWinningCompanyUuid());
        assertEquals(0.34482758620689655, dto.getAppliedFactor(), 1e-7);

        CompanyBonusDTO a = company(dto, "A");
        assertEquals(827_586, a.getCareerExtra());
        assertEquals(1_827_586, a.getTotalPayout());

        CompanyBonusDTO b = company(dto, "B");
        assertEquals(0.25, b.getFactor(), 1e-7);
        assertEquals(0, b.getCareerExtra());
        assertEquals(689_655, b.getTotalPayout());
    }

    // ---- Mid-year promotion TO 0× (Frank) --------------------------------

    @Test
    void promotionToZero_keepsEligibleMonths_notCareerIneligible() {
        double[] mults = {2, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0, 0};
        String[] levels = {"MANAGER", "MANAGER", "MANAGER", "MANAGER", "MANAGER", "MANAGER",
                "MANAGER", "MANAGER", "MANAGER", "PARTNER", "PARTNER", "PARTNER"};

        Map<String, Map<String, double[]>> userCompanyMonths = new LinkedHashMap<>();
        Map<String, double[]> userMultipliers = new HashMap<>();
        Map<String, String[]> userLevelNames = new HashMap<>();
        Map<String, String> fullNames = new HashMap<>();

        Map<String, double[]> frankCompanies = new LinkedHashMap<>();
        frankCompanies.put("A", flatMonths(50_000));
        userCompanyMonths.put("frank", frankCompanies);
        userMultipliers.put("frank", mults);
        userLevelNames.put("frank", levels);
        fullNames.put("frank", "Frank");

        Map<String, Double> poolByCompany = Map.of("A", 1_000_000.0);
        Map<String, String> companyNames = Map.of("A", "Alpha");
        Map<String, PoolMeta> poolMetaByCompany = Map.of("A", poolMeta(1_000_000));

        TwBonusCalculationDTO dto = TwBonusCalculator.calculate(
                FY, userCompanyMonths, userMultipliers, userLevelNames,
                fullNames, poolByCompany, companyNames, poolMetaByCompany);

        EmployeeBonusDTO frank = employee(dto, "frank");
        assertEquals(450_000.0, frank.getTotalWeightSum());        // base = 9 * 50_000
        assertEquals(900_000.0, frank.getEffectiveWeightSum());    // eff  = 9 * 50_000 * 2
        assertFalse(frank.isCareerIneligible());
    }

    // ---- Mid-year promotion FROM 0× --------------------------------------

    @Test
    void promotionFromZero_includesPostPromotionMonths() {
        double[] mults = {0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2};
        String[] levels = {"PARTNER", "PARTNER", "PARTNER", "MANAGER", "MANAGER", "MANAGER",
                "MANAGER", "MANAGER", "MANAGER", "MANAGER", "MANAGER", "MANAGER"};

        Map<String, Map<String, double[]>> userCompanyMonths = new LinkedHashMap<>();
        Map<String, double[]> userMultipliers = new HashMap<>();
        Map<String, String[]> userLevelNames = new HashMap<>();
        Map<String, String> fullNames = new HashMap<>();

        Map<String, double[]> companiesMap = new LinkedHashMap<>();
        companiesMap.put("A", flatMonths(50_000));
        userCompanyMonths.put("gina", companiesMap);
        userMultipliers.put("gina", mults);
        userLevelNames.put("gina", levels);
        fullNames.put("gina", "Gina");

        Map<String, Double> poolByCompany = Map.of("A", 1_000_000.0);
        Map<String, String> companyNames = Map.of("A", "Alpha");
        Map<String, PoolMeta> poolMetaByCompany = Map.of("A", poolMeta(1_000_000));

        TwBonusCalculationDTO dto = TwBonusCalculator.calculate(
                FY, userCompanyMonths, userMultipliers, userLevelNames,
                fullNames, poolByCompany, companyNames, poolMetaByCompany);

        EmployeeBonusDTO gina = employee(dto, "gina");
        assertEquals(450_000.0, gina.getTotalWeightSum());
        assertEquals(900_000.0, gina.getEffectiveWeightSum());
        assertFalse(gina.isCareerIneligible());
    }

    // ---- All-year 0× -----------------------------------------------------

    @Test
    void allYearZero_isCareerIneligible_butStillPresent() {
        Map<String, Map<String, double[]>> userCompanyMonths = new LinkedHashMap<>();
        Map<String, double[]> userMultipliers = new HashMap<>();
        Map<String, String[]> userLevelNames = new HashMap<>();
        Map<String, String> fullNames = new HashMap<>();

        // One eligible employee keeps company A alive.
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "alice", "Alice", "A", evenMonths(600_000), 1.0, "CONSULTANT");
        // Partner all-year 0× with salary.
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "pat", "Pat", "A", flatMonths(80_000), 0.0, "PARTNER");

        Map<String, Double> poolByCompany = Map.of("A", 1_000_000.0);
        Map<String, String> companyNames = Map.of("A", "Alpha");
        Map<String, PoolMeta> poolMetaByCompany = Map.of("A", poolMeta(1_000_000));

        TwBonusCalculationDTO dto = TwBonusCalculator.calculate(
                FY, userCompanyMonths, userMultipliers, userLevelNames,
                fullNames, poolByCompany, companyNames, poolMetaByCompany);

        EmployeeBonusDTO pat = employee(dto, "pat");
        assertNotNull(pat);
        assertTrue(pat.isCareerIneligible());
        assertEquals(0, pat.getTotalPayout());
        assertEquals(0.0, pat.getTotalWeightSum());
        assertEquals(0.0, pat.getMultiplier());
        assertEquals("", pat.getRepresentativeCareerLevel());
    }

    // ---- Zero-base company omitted ---------------------------------------

    @Test
    void zeroBaseCompanyIsOmitted_whileEligibleCompanyRetained() {
        Map<String, Map<String, double[]>> userCompanyMonths = new LinkedHashMap<>();
        Map<String, double[]> userMultipliers = new HashMap<>();
        Map<String, String[]> userLevelNames = new HashMap<>();
        Map<String, String> fullNames = new HashMap<>();

        // Company A — eligible (kept).
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "alice", "Alice", "A", evenMonths(600_000), 1.0, "CONSULTANT");
        // Company Z — only employee is all-0× (base 0 ⇒ omitted).
        addUser(userCompanyMonths, userMultipliers, userLevelNames, fullNames,
                "zoe", "Zoe", "Z", flatMonths(70_000), 0.0, "PARTNER");

        Map<String, Double> poolByCompany = Map.of("A", 1_000_000.0, "Z", 800_000.0);
        Map<String, String> companyNames = Map.of("A", "Alpha", "Z", "Zeta");
        Map<String, PoolMeta> poolMetaByCompany =
                Map.of("A", poolMeta(1_000_000), "Z", poolMeta(800_000));

        TwBonusCalculationDTO dto = TwBonusCalculator.calculate(
                FY, userCompanyMonths, userMultipliers, userLevelNames,
                fullNames, poolByCompany, companyNames, poolMetaByCompany);

        // Z must NOT appear; A is retained.
        assertTrue(dto.getCompanies().stream().noneMatch(c -> c.getCompanyUuid().equals("Z")));
        assertTrue(dto.getCompanies().stream().anyMatch(c -> c.getCompanyUuid().equals("A")));
        assertEquals(1, dto.getCompanies().size());

        // Zoe still present as an employee with payout 0.
        EmployeeBonusDTO zoe = employee(dto, "zoe");
        assertTrue(zoe.isCareerIneligible());
        assertEquals(0, zoe.getTotalPayout());

        assertEquals("A", dto.getWinningCompanyUuid());
    }

    // ---- No companies at all (defensive) ---------------------------------

    @Test
    void emptyInput_yieldsNoWinnerNoPayout() {
        TwBonusCalculationDTO dto = TwBonusCalculator.calculate(
                FY, new LinkedHashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());

        assertTrue(dto.getCompanies().isEmpty());
        assertTrue(dto.getEmployees().isEmpty());
        assertNull(dto.getWinningCompanyUuid());
        assertEquals(0.0, dto.getAppliedFactor());
        assertEquals(0.0, dto.getProjectedTotalPayout());
    }

    // ---- Helper to register a single-company user with flat metadata. -----

    private static void addUser(
            Map<String, Map<String, double[]>> userCompanyMonths,
            Map<String, double[]> userMultipliers,
            Map<String, String[]> userLevelNames,
            Map<String, String> fullNames,
            String uuid, String name, String company,
            double[] months, double mult, String levelName) {
        Map<String, double[]> byCompany =
                userCompanyMonths.computeIfAbsent(uuid, k -> new LinkedHashMap<>());
        byCompany.put(company, months);
        userMultipliers.put(uuid, flatMults(mult));
        userLevelNames.put(uuid, flatLevels(levelName));
        fullNames.put(uuid, name);
    }
}
