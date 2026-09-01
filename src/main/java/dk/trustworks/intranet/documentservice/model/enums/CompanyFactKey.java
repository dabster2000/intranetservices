package dk.trustworks.intranet.documentservice.model.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The seeded company-fact vocabulary (template-clauses spec §4.9).
 * <p>
 * NOT a closed set: {@code company_facts.fact_key} accepts any key
 * matching {@link #KEY_PATTERN}, so a new {@code {{COMPANY_*}}}
 * placeholder can be backed by a new fact without a code change. This
 * enum exists for the Settings → Selskaber editor (labels, ordering,
 * which keys every company is expected to fill) and for the legacy
 * keyword fallback when a placeholder has no explicit
 * {@code source_field}.
 */
public enum CompanyFactKey {

    LEGAL_NAME("Juridisk navn"),
    SHORT_NAME("Kort navn"),
    /**
     * Stored, never computed — the Danish genitive is irregular around a
     * trailing S: "Trustworks Technologys" but "Trustworks A/S'".
     */
    NAME_GENITIVE("Navn i ejefald"),
    CVR("CVR-nummer"),
    ADDRESS("Adresse"),
    PENSION_PROVIDER("Pensionsselskab"),
    PENSION_COMPANY_PCT("Pension, arbejdsgiverandel (%)"),
    PENSION_EMPLOYEE_PCT("Pension, egetbidrag (%)"),
    HEALTH_INSURANCE_PROVIDER("Sundhedsforsikring"),
    LUNCH_PRICE("Frokostordning, pris"),
    SIGNATORY_NAME("Underskriver, navn"),
    SIGNATORY_EMAIL("Underskriver, e-mail");

    /** Uppercase key shape shared with placeholder keys. */
    public static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,49}$");

    /** Placeholder-key prefix that marks a COMPANY fact reference. */
    public static final String PLACEHOLDER_PREFIX = "COMPANY_";

    private final String danishLabel;

    CompanyFactKey(String danishLabel) {
        this.danishLabel = danishLabel;
    }

    public String danishLabel() {
        return danishLabel;
    }

    public static List<CompanyFactKey> seeded() {
        return Arrays.asList(values());
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    /**
     * The fact key a COMPANY-source placeholder refers to when it has no
     * explicit {@code source_field}: the placeholder key minus the
     * {@code COMPANY_} prefix, with the handful of legacy aliases the
     * pre-facts templates used ({@code COMPANY_NAME}/{@code COMPANY_NAVN}
     * meant the legal name).
     */
    public static Optional<String> factKeyForPlaceholder(String placeholderKey) {
        if (placeholderKey == null) {
            return Optional.empty();
        }
        String upper = placeholderKey.toUpperCase(Locale.ROOT);
        String stripped = upper.startsWith(PLACEHOLDER_PREFIX)
                ? upper.substring(PLACEHOLDER_PREFIX.length())
                : upper;
        String aliased = switch (stripped) {
            case "NAME", "NAVN" -> LEGAL_NAME.name();
            case "ADRESSE" -> ADDRESS.name();
            case "CVR_NUMBER", "CVR_NR", "CVRNR" -> CVR.name();
            default -> stripped;
        };
        return isValidKey(aliased) ? Optional.of(aliased) : Optional.empty();
    }
}
