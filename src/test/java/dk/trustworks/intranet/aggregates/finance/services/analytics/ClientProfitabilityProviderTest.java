package dk.trustworks.intranet.aggregates.finance.services.analytics;

import dk.trustworks.intranet.aggregates.finance.dto.ClientConsultantDetailDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientProfitabilityRowDTO;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ClientProfitabilityProviderTest.Profile.class)
class ClientProfitabilityProviderTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder"
            );
        }
    }

    private static final String INTERNAL_CLIENT_UUID = "d58bb00b-4474-4250-84eb-d8f77548ddac";
    private static final double EPSILON = 1.0; // DKK rounding tolerance

    @Inject
    ClientProfitabilityProvider provider;

    private String[] ttmKeys() {
        LocalDate now = LocalDate.now().withDayOfMonth(1);
        LocalDate from = now.minusMonths(12);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMM");
        return new String[]{from.format(fmt), now.format(fmt)};
    }

    @Test
    void getClientProfitability_returnsRowsWithAllFieldsPopulated() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability(keys[0], keys[1], null);

        assertNotNull(rows);
        assertFalse(rows.isEmpty(), "Expected at least one client with TTM revenue");

        ClientProfitabilityRowDTO first = rows.getFirst();
        assertNotNull(first.clientId());
        assertNotNull(first.clientName());
    }

    @Test
    void getClientProfitability_excludesInternalClient() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability(keys[0], keys[1], null);

        assertTrue(rows.stream().noneMatch(r -> INTERNAL_CLIENT_UUID.equals(r.clientId())),
                "Internal intercompany client must be excluded");
    }

    @Test
    void getClientProfitability_driversAreNonNegative() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability(keys[0], keys[1], null);

        for (ClientProfitabilityRowDTO r : rows) {
            assertTrue(r.rateGapDkk() >= 0,
                    "rateGap must be >= 0 for " + r.clientName() + " (got " + r.rateGapDkk() + ")");
            assertTrue(r.unusedContractDkk() >= 0,
                    "unusedContract must be >= 0 for " + r.clientName() + " (got " + r.unusedContractDkk() + ")");
        }
    }

    @Test
    void getClientProfitability_targetProfitEqualsActualPlusDrivers() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability(keys[0], keys[1], null);

        for (ClientProfitabilityRowDTO r : rows) {
            double expected = r.actualProfitDkk() + r.rateGapDkk() + r.unusedContractDkk();
            assertEquals(expected, r.targetProfitDkk(), EPSILON,
                    "target_profit invariant violated for " + r.clientName());
        }
    }

    @Test
    void getClientProfitability_rowsSortedByActualProfitAscending() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability(keys[0], keys[1], null);

        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).actualProfitDkk() <= rows.get(i).actualProfitDkk(),
                    "Rows must be sorted by actualProfit ASC");
        }
    }

    @Test
    void getClientProfitability_emptyCompanyFilterReturnsAllCompanies() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rowsNull = provider.getClientProfitability(keys[0], keys[1], null);
        List<ClientProfitabilityRowDTO> rowsEmpty = provider.getClientProfitability(keys[0], keys[1], java.util.Set.of());
        assertEquals(rowsNull.size(), rowsEmpty.size(), "null and empty companyIds must behave identically");
    }

    @Test
    void getClientProfitability_invalidKeysReturnsEmpty() {
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability("190001", "190002", null);
        assertNotNull(rows);
        assertTrue(rows.isEmpty(), "Ancient TTM window should return no rows");
    }

    @Test
    void getConsultantsForClient_returnsConsultantsForKnownClient() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability(keys[0], keys[1], null);
        assertFalse(rows.isEmpty());
        // Pick the client with the most consultants to maximise the chance of data
        ClientProfitabilityRowDTO target = rows.stream()
                .max(Comparator.comparingInt(ClientProfitabilityRowDTO::consultantCount))
                .orElseThrow();

        List<ClientConsultantDetailDTO> consultants = provider.getConsultantsForClient(
                target.clientId(), keys[0], keys[1], null);

        assertNotNull(consultants);
        if (target.consultantCount() > 0) {
            assertFalse(consultants.isEmpty(),
                    "Client " + target.clientName() + " has consultantCount=" + target.consultantCount() + " but detail is empty");
        }
        for (ClientConsultantDetailDTO c : consultants) {
            assertNotNull(c.useruuid());
            assertNotNull(c.careerLevel());
            assertTrue(c.breakEvenRateDkk() >= 0);
            assertTrue(c.hoursBooked() >= 0);
            assertTrue(c.hoursContracted() >= 0);
            assertTrue(c.unusedHours() >= 0);
            assertEquals(Math.max(0, c.hoursContracted() - c.hoursBooked()), c.unusedHours(), 0.01);
        }
    }

    @Test
    void getConsultantsForClient_unknownClientReturnsEmpty() {
        String[] keys = ttmKeys();
        List<ClientConsultantDetailDTO> consultants = provider.getConsultantsForClient(
                "00000000-0000-0000-0000-000000000000", keys[0], keys[1], null);
        assertNotNull(consultants);
        assertTrue(consultants.isEmpty());
    }

    /**
     * Regression guard for the {@code work.rate = 0} data problem that used to silence
     * the rate-gap lever completely. Now driven by {@code work_full} (which resolves
     * contract rates), the portfolio-wide rate gap must be positive as long as any
     * consultant bills below their career-level MVR.
     */
    @Test
    void getClientProfitability_rateGapIsNonZeroSomewhereInPortfolio() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability(keys[0], keys[1], null);
        if (rows.isEmpty()) return; // empty DB — nothing to assert

        double totalRateGap = rows.stream().mapToDouble(ClientProfitabilityRowDTO::rateGapDkk).sum();
        assertTrue(totalRateGap > 0,
                "Portfolio-wide rate gap should be > 0 when any consultant bills below MVR. " +
                "A total of 0 suggests the provider regressed to querying work.rate (always 0) " +
                "instead of work_full (contract-resolved rate).");
    }

    /**
     * Regression guard for the consultant drill-down: with work_full, consultants
     * who logged billable hours must appear with positive hoursBooked rather than
     * the previous 0/contracted-only behaviour.
     */
    @Test
    void getConsultantsForClient_hoursBookedIsPopulatedForSomeone() {
        String[] keys = ttmKeys();
        List<ClientProfitabilityRowDTO> rows = provider.getClientProfitability(keys[0], keys[1], null);
        if (rows.isEmpty()) return;

        // Look at the top few clients by consultant count so that at least one has billable work.
        long anyPositive = rows.stream()
                .sorted(Comparator.comparingInt(ClientProfitabilityRowDTO::consultantCount).reversed())
                .limit(5)
                .flatMap(r -> provider.getConsultantsForClient(r.clientId(), keys[0], keys[1], null).stream())
                .filter(c -> c.hoursBooked() > 0)
                .count();

        assertTrue(anyPositive > 0,
                "At least one consultant on a top client should have hoursBooked > 0 after the " +
                "work_full switch. A total of 0 suggests the work_agg subquery still filters on " +
                "work.rate (always 0).");
    }
}
