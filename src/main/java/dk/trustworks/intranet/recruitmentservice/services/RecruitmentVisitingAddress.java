package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * The address a candidate is told to turn up at.
 *
 * <p>This is the <em>visiting</em> address — the door with the reception
 * iPad behind it — and it is deliberately a separate concept from the
 * registered/postal address that appears in the expense geofence
 * ({@code ExpenseAIValidationService}), the bulk-mail footer
 * ({@code MailResource}) and {@code company_facts.ADDRESS}. Those three
 * answer "where is the company registered"; this one answers "where do I
 * ring the bell", and they are not the same building. Do not merge them.
 *
 * <h3>Where the value lives</h3>
 * One {@code app_settings} row, {@code recruitment.interview.visiting-address}
 * in category {@code recruitment} — the same storage, the same settings page
 * and the same recruiter-tier write path as
 * {@link RecruitmentAiVoiceCard#SETTING_KEY} and
 * {@link RecruitmentEmailService#SETTING_REPLY_TO_FALLBACK}. Recruitment has
 * no company dimension at interview time ({@code RecruitmentPosition} has no
 * company column and {@code candidate.targetCompanyUuid} is nullable and set
 * near offer/onboarding), so a per-company lookup would resolve to nothing
 * for most interviews: one HR-editable value is the correct grain.
 *
 * <h3>Three states, on purpose</h3>
 * <ul>
 *   <li><b>Row absent</b> — never configured: {@link #DEFAULT_ADDRESS}
 *       applies. The row IS seeded (V550), but a missing row must still be a
 *       working feature: staging's nightly prod→staging {@code app_settings}
 *       copy can take the seed away again.</li>
 *   <li><b>Row with text</b> — the configured address applies.</li>
 *   <li><b>Row with an empty value</b> — an explicit opt-out: invitations
 *       carry no address and no arrival instructions at all, exactly as they
 *       did before this feature. Mirrors the blank-Reply-To opt-out beside
 *       it.</li>
 * </ul>
 *
 * <h3>Why the name must match the intranet directory</h3>
 * The reception kiosk ({@code /guest}) makes the visitor pick the employee
 * they are meeting off the live employee list, matched on
 * {@code firstname lastname}. That is why the invitation names the
 * interviewers with {@link RecruitmentCalendarService#displayName(dk.trustworks.intranet.domain.user.entity.User)}
 * — the same two fields the kiosk searches — and why the address and the
 * names are one change and not two.
 */
@ApplicationScoped
public class RecruitmentVisitingAddress {

    /** The single {@code app_settings} key holding the address. */
    public static final String SETTING_KEY = "recruitment.interview.visiting-address";

    /** Same category as the sibling recruitment settings on the page. */
    private static final String SETTING_CATEGORY = "recruitment";

    /**
     * Hard cap, matching {@code recruitment_interviews.location} — this is
     * one line on a calendar invitation, not a wayfinding essay.
     */
    public static final int MAX_LENGTH = 200;

    /**
     * The built-in address. Editable on {@code /recruitment/settings}; this
     * constant is what an installation with no row writes by.
     */
    public static final String DEFAULT_ADDRESS = "Hausergade 3, 1128 København K";

    @Inject
    AppSettingService appSettingService;

    /**
     * The address invitations should carry right now: the stored value when
     * one exists, otherwise {@link #DEFAULT_ADDRESS}.
     *
     * @return the address, or {@code null} when the stored value is blank —
     *         the explicit "print no address" opt-out
     */
    public String effectiveAddress() {
        Optional<String> stored = appSettingService.findByKey(SETTING_KEY)
                .map(AppSetting::getSettingValue);
        if (stored.isEmpty()) {
            return defaultAddress();
        }
        String value = stored.get().trim();
        return value.isEmpty() ? null : value;
    }

    /** The built-in address, trimmed the same way a stored one is. */
    public static String defaultAddress() {
        return DEFAULT_ADDRESS.trim();
    }

    /**
     * What the settings page shows in its field: the same value
     * {@link #effectiveAddress()} resolves to, with the opt-out rendered as
     * an empty string rather than null.
     */
    public String editableAddress() {
        String effective = effectiveAddress();
        return effective == null ? "" : effective;
    }

    /** True while no row exists — the page can say "this is the built-in address". */
    public boolean isDefault() {
        return appSettingService.findByKey(SETTING_KEY).isEmpty();
    }

    /**
     * Persist a new address. An empty value is legal and means "invitations
     * carry no address and no arrival instructions".
     *
     * @throws BusinessRuleViolation when the address exceeds {@link #MAX_LENGTH}
     */
    public void update(String value, String actorUserUuid) {
        String sanitized = sanitize(value);
        if (sanitized.length() > MAX_LENGTH) {
            throw new BusinessRuleViolation(
                    "The visiting address must be at most " + MAX_LENGTH + " characters");
        }
        appSettingService.saveSetting(SETTING_KEY, sanitized, SETTING_CATEGORY, actorUserUuid);
    }

    /**
     * Flatten to one line and drop control characters.
     * <p>
     * The address is merged into an HTML calendar body (where the renderer
     * escapes it and turns a newline into {@code <br>}) AND into the plain
     * {@code .ics} body and the built-in fallback text, where a stray
     * newline would break the line structure the reader depends on. One
     * line in every surface is the only shape that is right in all of them.
     */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").trim();
    }
}
