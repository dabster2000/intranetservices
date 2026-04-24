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
}
