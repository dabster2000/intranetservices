package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, DB-free math for splitting a partner's sales bonus between the Trustworks companies whose
 * consultants delivered the partner's sales (unit-tested without CDI). Two steps:
 * <ol>
 *   <li>{@link #attributeBonus}: one APPROVED bonus row → company amounts. The row's
 *       {@code computedAmount} (which already carries fee lines, CALCULATED adjustments, the invoice
 *       discount and the credit-note sign) is distributed pro-rata over the selected consultant-line
 *       amounts per company. A row without consultant lines goes entirely to the fallback company —
 *       the company that issued the invoice.</li>
 *   <li>{@link #split}: the partner's per-company basis (Σ of step 1 over the fiscal year) → shares of
 *       the sales bonus, rounded to 2 dp with the rounding remainder absorbed by the largest share so
 *       the amounts sum exactly to the bonus. A partner without basis is attributed 100% to their own
 *       company.</li>
 * </ol>
 */
public final class PartnerBonusCompanySplitMath {

    /**
     * Below this absolute total (DKK) a basis is treated as empty: a bonus row whose consultant lines
     * net to (almost) nothing falls back to the issuing company, and a partner whose fiscal-year basis
     * is (almost) nothing or negative falls back to their own company.
     */
    public static final double MIN_ATTRIBUTABLE_BASIS = 1.0;

    private PartnerBonusCompanySplitMath() {}

    /** One company's share of a partner's sales bonus, by company UUID (the caller resolves names). */
    public record Share(String companyUuid, double basisAmount, double sharePct, double bonusAmount) {}

    /**
     * Distribute one bonus row's {@code computedAmount} over companies pro-rata to the selected
     * consultant-line amounts ({@code Σ hours × rate × pct/100} per company). Signs are preserved: a
     * credit note's negative {@code computedAmount} reduces each company's basis in proportion.
     *
     * @param computedAmount             the row's server-computed bonus amount
     * @param consultantSelectedByCompany selected consultant-line amount per company UUID
     * @param fallbackCompanyUuid        receives the whole amount when there are no consultant lines
     */
    public static Map<String, Double> attributeBonus(double computedAmount,
                                                     Map<String, Double> consultantSelectedByCompany,
                                                     String fallbackCompanyUuid) {
        Map<String, Double> result = new LinkedHashMap<>();
        double total = sum(consultantSelectedByCompany);
        if (consultantSelectedByCompany == null || consultantSelectedByCompany.isEmpty()
                || Math.abs(total) < MIN_ATTRIBUTABLE_BASIS) {
            result.put(fallbackCompanyUuid, computedAmount);
            return result;
        }
        for (Map.Entry<String, Double> e : consultantSelectedByCompany.entrySet()) {
            result.merge(e.getKey(), computedAmount * (e.getValue() / total), Double::sum);
        }
        return result;
    }

    /**
     * Split {@code salesBonus} by the partner's per-company basis. Shares are sorted by amount
     * (descending) and sum exactly to {@code round2(salesBonus)}.
     *
     * @param salesBonus     the partner's sales bonus for the fiscal year
     * @param basisByCompany the partner's attributed basis per company UUID
     * @param ownCompanyUuid the partner's own company — receives 100% when the basis is empty,
     *                       below {@link #MIN_ATTRIBUTABLE_BASIS} or negative
     */
    public static List<Share> split(double salesBonus, Map<String, Double> basisByCompany, String ownCompanyUuid) {
        double bonus = round2(salesBonus);
        double basisTotal = sum(basisByCompany);
        if (basisByCompany == null || basisByCompany.isEmpty() || basisTotal < MIN_ATTRIBUTABLE_BASIS) {
            return List.of(new Share(ownCompanyUuid, round2(basisTotal), 1.0, bonus));
        }

        List<Share> shares = new ArrayList<>(basisByCompany.size());
        double allocated = 0.0;
        for (Map.Entry<String, Double> e : basisByCompany.entrySet()) {
            double pct = e.getValue() / basisTotal;
            double amount = round2(bonus * pct);
            allocated = round2(allocated + amount);
            shares.add(new Share(e.getKey(), round2(e.getValue()), round4(pct), amount));
        }

        double remainder = round2(bonus - allocated);
        if (remainder != 0.0) {
            int largest = 0;
            for (int i = 1; i < shares.size(); i++) {
                if (Math.abs(shares.get(i).basisAmount()) > Math.abs(shares.get(largest).basisAmount())) largest = i;
            }
            Share s = shares.get(largest);
            shares.set(largest, new Share(s.companyUuid(), s.basisAmount(), s.sharePct(),
                    round2(s.bonusAmount() + remainder)));
        }

        shares.sort(Comparator.comparingDouble(Share::bonusAmount).reversed());
        return shares;
    }

    private static double sum(Map<String, Double> m) {
        if (m == null) return 0.0;
        double s = 0.0;
        for (Double v : m.values()) s += v == null ? 0.0 : v;
        return s;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
