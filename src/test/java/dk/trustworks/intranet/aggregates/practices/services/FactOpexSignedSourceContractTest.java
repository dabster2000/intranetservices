package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FactOpexSignedSourceContractTest {

    @Test
    void v408RetainsAllFourNetNegativeBranchesAndOtherwiseMatchesV383() throws IOException {
        String v383 = Files.readString(Path.of(
                "src/main/resources/db/migration/V383__fact_opex_signed.sql"));
        String v408 = Files.readString(Path.of(
                "src/main/resources/db/migration/V408__Retain_net_negative_fact_opex_buckets.sql"));

        assertEquals(4, occurrences(v408, "AND oa.opex_amount_dkk <> 0"));
        assertFalse(v408.contains("AND oa.opex_amount_dkk > 0"));

        String expected = sqlFromCreate(v383).replace(
                "AND oa.opex_amount_dkk > 0", "AND oa.opex_amount_dkk <> 0");
        assertEquals(normalizeSql(expected), normalizeSql(sqlFromCreate(v408)));
    }

    private static int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static String sqlFromCreate(String migration) {
        return migration.substring(migration.indexOf("CREATE OR REPLACE ALGORITHM"));
    }

    private static String normalizeSql(String sql) {
        return Arrays.stream(sql.split("\\R"))
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
    }
}
