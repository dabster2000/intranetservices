package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.PartnerBonusCompanySplitMath.Share;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for the partner sales-bonus company split (no CDI, no DB).
 */
class PartnerBonusCompanySplitMathTest {

    private static final String TW = "tw";
    private static final String TWT = "twt";
    private static final String TWC = "twc";

    private static Map<String, Double> map(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    // ------------------------------------------------------------- attributeBonus

    /** The 80% cross-company haircut is already in the selected amounts: TW 100k at 100%, TWT 100k at 80%. */
    @Test
    void attributeBonus_spreadsComputedAmountProRataOverConsultantCompanies() {
        // computedAmount 181,000 = 180,000 consultant lines + 5,000 fee line - 4,000 CALCULATED discount
        Map<String, Double> out = PartnerBonusCompanySplitMath.attributeBonus(
                181_000.0, map(TW, 100_000, TWT, 80_000), TW);

        assertEquals(2, out.size());
        assertEquals(181_000.0 * 100.0 / 180.0, out.get(TW), 0.001);
        assertEquals(181_000.0 * 80.0 / 180.0, out.get(TWT), 0.001);
        assertEquals(181_000.0, out.get(TW) + out.get(TWT), 0.001);
    }

    @Test
    void attributeBonus_withoutConsultantLines_goesToFallbackCompany() {
        assertEquals(map(TWT, 20_000), PartnerBonusCompanySplitMath.attributeBonus(20_000.0, map(), TWT));
        assertEquals(map(TWT, 20_000), PartnerBonusCompanySplitMath.attributeBonus(20_000.0, null, TWT));
    }

    @Test
    void attributeBonus_consultantLinesNettingToNothing_goesToFallbackCompany() {
        Map<String, Double> out = PartnerBonusCompanySplitMath.attributeBonus(
                7_000.0, map(TW, 500.0, TWT, -499.5), TWC);
        assertEquals(map(TWC, 7_000), out);
    }

    @Test
    void attributeBonus_creditNote_keepsNegativeSignPerCompany() {
        Map<String, Double> out = PartnerBonusCompanySplitMath.attributeBonus(
                -1_000.0, map(TW, 750, TWT, 250), TW);
        assertEquals(-750.0, out.get(TW), 0.001);
        assertEquals(-250.0, out.get(TWT), 0.001);
    }

    // ------------------------------------------------------------- split

    @Test
    void split_dividesBonusByBasisMix_sortedDescending() {
        List<Share> shares = PartnerBonusCompanySplitMath.split(
                100_000.0, map(TWT, 1_000_000, TW, 2_000_000), TW);

        assertEquals(2, shares.size());
        assertEquals(TW, shares.get(0).companyUuid());
        assertEquals(66_666.67, shares.get(0).bonusAmount(), 0.0001);
        assertEquals(0.6667, shares.get(0).sharePct(), 0.0001);
        assertEquals(2_000_000.0, shares.get(0).basisAmount(), 0.0001);
        assertEquals(TWT, shares.get(1).companyUuid());
        assertEquals(33_333.33, shares.get(1).bonusAmount(), 0.0001);
        assertEquals(0.3333, shares.get(1).sharePct(), 0.0001);
        assertEquals(100_000.0, shares.get(0).bonusAmount() + shares.get(1).bonusAmount(), 0.0001);
    }

    /** 100 / 3 = 33.33 each leaves 0.01; the largest basis absorbs it so the amounts sum exactly. */
    @Test
    void split_roundingRemainder_isAbsorbedByLargestShare() {
        List<Share> shares = PartnerBonusCompanySplitMath.split(
                100.0, map(TW, 1_000.0, TWT, 1_000.5, TWC, 1_000.0), TW);

        double sum = shares.stream().mapToDouble(Share::bonusAmount).sum();
        assertEquals(100.0, sum, 0.0001);
        Share twt = shares.stream().filter(s -> s.companyUuid().equals(TWT)).findFirst().orElseThrow();
        assertEquals(33.34, twt.bonusAmount(), 0.0001);
        assertEquals(TWT, shares.get(0).companyUuid(), "largest share sorts first");
    }

    @Test
    void split_withoutBasis_attributesEverythingToOwnCompany() {
        List<Share> empty = PartnerBonusCompanySplitMath.split(45_000.0, map(), TWT);
        assertEquals(List.of(new Share(TWT, 0.0, 1.0, 45_000.0)), empty);

        List<Share> nul = PartnerBonusCompanySplitMath.split(45_000.0, null, TWT);
        assertEquals(List.of(new Share(TWT, 0.0, 1.0, 45_000.0)), nul);
    }

    @Test
    void split_withNegativeOrNegligibleBasis_attributesEverythingToOwnCompany() {
        List<Share> negative = PartnerBonusCompanySplitMath.split(45_000.0, map(TW, 10.0, TWT, -5_000.0), TWC);
        assertEquals(1, negative.size());
        assertEquals(TWC, negative.get(0).companyUuid());
        assertEquals(1.0, negative.get(0).sharePct(), 0.0001);
        assertEquals(45_000.0, negative.get(0).bonusAmount(), 0.0001);
        assertEquals(-4_990.0, negative.get(0).basisAmount(), 0.0001);

        List<Share> tiny = PartnerBonusCompanySplitMath.split(45_000.0, map(TW, 0.4), TWC);
        assertEquals(TWC, tiny.get(0).companyUuid());
    }

    /** A company whose lines net to a credit carries a negative share; the others still sum to the bonus. */
    @Test
    void split_negativeCompanyBasis_yieldsNegativeShareButExactTotal() {
        List<Share> shares = PartnerBonusCompanySplitMath.split(
                10_000.0, map(TW, 1_200_000, TWT, -200_000), TW);

        Share tw = shares.get(0);
        Share twt = shares.get(1);
        assertEquals(12_000.0, tw.bonusAmount(), 0.0001);
        assertEquals(-2_000.0, twt.bonusAmount(), 0.0001);
        assertEquals(-0.2, twt.sharePct(), 0.0001);
        assertTrue(tw.bonusAmount() + twt.bonusAmount() == 10_000.0);
    }
}
