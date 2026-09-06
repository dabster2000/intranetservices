package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

/**
 * One company's share of a partner's sales bonus for a fiscal year (partner-bonus admin dashboard).
 *
 * <p>The partner's still-fundable sales basis (APPROVED, un-consumed {@code invoice_bonuses} rows in
 * the fiscal-year window) is attributed per invoice line to the company employing the consultant who
 * delivered the line, resolved at invoice date — the same source as the 0/80/100% line defaults and
 * the approval-grid company badges. Fee and discount lines without a consultant, CALCULATED items and
 * the invoice discount are spread pro-rata over the invoice's consultant companies; an invoice with no
 * consultant line at all is attributed to the company that issued it. The partner's sales bonus is
 * then split by the resulting mix. A partner without basis is attributed 100% to their own company.</p>
 *
 * @param companyUuid         company UUID ({@code "unknown"} when unresolvable)
 * @param companyName         company display name
 * @param companyAbbreviation short code (TW, TWT, TWC)
 * @param basisAmount         the partner's attributed sales basis for this company, DKK (2 dp)
 * @param sharePct            {@code basisAmount / partner basis total}, 0..1 (4 dp); negative when the
 *                            company's lines net to a credit
 * @param bonusAmount         this company's share of the partner's sales bonus, DKK (2 dp); one
 *                            partner's amounts sum exactly to the partner's sales bonus
 */
public record CompanyBonusShareDTO(
        String companyUuid,
        String companyName,
        String companyAbbreviation,
        double basisAmount,
        double sharePct,
        double bonusAmount
) {}
