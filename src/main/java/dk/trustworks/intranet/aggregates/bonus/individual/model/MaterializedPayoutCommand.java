package dk.trustworks.intranet.aggregates.bonus.individual.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything the materialisation writer needs to persist one payout AND its reproducibility snapshot in
 * a single transaction: the target lump sum (userUuid, amount, month, ruleName, pension, sourceReference)
 * plus the frozen audit inputs (ruleUuid, kind, the effective specJson, the resolved basisAmount and
 * monthsEmployed). Immutable value object — assembled by {@code IndividualBonusPayoutService} from the
 * projection and its per-payout inputs.
 *
 * @param ruleUuid        the rule that produced this payout
 * @param userUuid        the paid employee
 * @param ruleName        used as the lump sum description
 * @param month           day-1 of the payout month
 * @param kind            ADVANCE / MONTHLY / YEARLY / TRUEUP / FINAL_SETTLEMENT
 * @param amount          gross DKK (already sign-gated: only positive amounts reach the writer)
 * @param pension         false → Danløn "41 Bonus"; true → with-pension bucket
 * @param sourceReference stable idempotency key (unique on both salary_lump_sum and individual_bonus_payout)
 * @param specJson        the effective rule spec JSON at payout time — the reproducibility anchor
 * @param basisAmount     the resolved basis (e.g. FY production) that drove the earned amount
 * @param monthsEmployed  months active in the earning FY (pro-rating input)
 */
public record MaterializedPayoutCommand(
        String ruleUuid,
        String userUuid,
        String ruleName,
        LocalDate month,
        PayoutKind kind,
        BigDecimal amount,
        boolean pension,
        String sourceReference,
        String specJson,
        BigDecimal basisAmount,
        Integer monthsEmployed
) {
}
