package dk.trustworks.intranet.hrletters.model.enums;

/**
 * The two letter flows carried by {@code hr_letters}.
 *
 * <p>{@code SALARY_REGULATION} is a one-way written notice (lov om
 * ansættelsesbeviser og visse arbejdsvilkår § 8) — auto-drafted when a
 * non-first salary row is saved. {@code VACATION_TRANSFER} is a bilateral
 * written agreement (ferieloven §§ 21–22) — requested by the employee,
 * approved by HR; both consents are stamped into the generated PDF.</p>
 */
public enum HrLetterType {
    SALARY_REGULATION,
    VACATION_TRANSFER
}
