package dk.trustworks.intranet.aggregates.bonus.individual.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IndividualBonusCanonicalizerTest {

    private IndividualBonusCanonicalizer canonicalizer;

    @BeforeEach
    void setUp() {
        canonicalizer = new IndividualBonusCanonicalizer();
        canonicalizer.mapper = new ObjectMapper();
    }

    @Test
    void canonicalize_sortsObjectKeys_preservesArrayOrder_andUsesPlainNormalizedDecimals() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("z", new BigDecimal("1000.000"));
        input.put("a", List.of(new BigDecimal("2.5000"), new BigDecimal("0.000")));

        String json = canonicalizer.canonicalizeMap(input);

        assertEquals("{\"a\":[2.5,0],\"z\":1000}", json);
        assertFalse(json.contains("E+"));
    }

    @Test
    void sha256_isStableLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                canonicalizer.sha256("abc"));
    }
}
