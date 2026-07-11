package dk.trustworks.intranet.contracts.model.enums;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Backend-computed lifecycle status for framework agreements (contract types)
 * and their rules. Computed at DTO-mapping time against "today" in
 * Europe/Copenhagen — never persisted.
 *
 * <p>Derivation order (first match wins; end dates are <b>exclusive</b>):
 * <ol>
 *   <li>{@code !active} → {@link #ARCHIVED} (agreements) / {@link #DISABLED} (rules)</li>
 *   <li>{@code end != null && !today.isBefore(end)} → {@link #EXPIRED}</li>
 *   <li>{@code validFrom != null && today.isBefore(validFrom)} → {@link #SCHEDULED}</li>
 *   <li>otherwise → {@link #ACTIVE}</li>
 * </ol>
 *
 * <p>Agreements use ACTIVE/SCHEDULED/EXPIRED/ARCHIVED; pricing rules use
 * ACTIVE/SCHEDULED/EXPIRED/DISABLED; validation rules (no date columns) use
 * only ACTIVE/DISABLED. See the framework-agreements redesign spec §4.
 *
 * <p>Note: this status describes availability for <b>contract creation/edit</b>
 * (agreements) or applicability windows (rules). An EXPIRED/ARCHIVED agreement
 * still prices invoices for its existing contracts — by explicit decision.
 */
public enum LifecycleStatus {

    ACTIVE, SCHEDULED, EXPIRED, ARCHIVED, DISABLED;

    public static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    /** "Today" as seen by the status model — Europe/Copenhagen. */
    public static LocalDate today() {
        return LocalDate.now(COPENHAGEN);
    }

    /**
     * Derive the status of a framework agreement (contract type definition).
     *
     * @param active     the soft-delete flag
     * @param validFrom  first day the agreement is open to new contracts (inclusive, nullable)
     * @param validUntil first day the agreement is no longer open to new contracts (exclusive, nullable)
     */
    public static LifecycleStatus forAgreement(boolean active, LocalDate validFrom, LocalDate validUntil) {
        return forAgreement(active, validFrom, validUntil, today());
    }

    /** Test-friendly overload of {@link #forAgreement(boolean, LocalDate, LocalDate)}. */
    public static LifecycleStatus forAgreement(boolean active, LocalDate validFrom, LocalDate validUntil, LocalDate today) {
        return derive(active, validFrom, validUntil, today, ARCHIVED);
    }

    /**
     * Derive the status of a pricing rule step.
     *
     * @param active    the soft-delete flag (the real pricing off-switch)
     * @param validFrom first invoice date the rule applies to (inclusive, nullable)
     * @param validTo   first invoice date the rule no longer applies to (exclusive, nullable)
     */
    public static LifecycleStatus forPricingRule(boolean active, LocalDate validFrom, LocalDate validTo) {
        return forPricingRule(active, validFrom, validTo, today());
    }

    /** Test-friendly overload of {@link #forPricingRule(boolean, LocalDate, LocalDate)}. */
    public static LifecycleStatus forPricingRule(boolean active, LocalDate validFrom, LocalDate validTo, LocalDate today) {
        return derive(active, validFrom, validTo, today, DISABLED);
    }

    /** Derive the status of a validation rule — no date columns exist, so only ACTIVE/DISABLED. */
    public static LifecycleStatus forValidationRule(boolean active) {
        return active ? ACTIVE : DISABLED;
    }

    private static LifecycleStatus derive(boolean active, LocalDate from, LocalDate endExclusive,
                                          LocalDate today, LifecycleStatus inactiveStatus) {
        if (!active) {
            return inactiveStatus;
        }
        if (endExclusive != null && !today.isBefore(endExclusive)) {
            return EXPIRED;
        }
        if (from != null && today.isBefore(from)) {
            return SCHEDULED;
        }
        return ACTIVE;
    }
}
