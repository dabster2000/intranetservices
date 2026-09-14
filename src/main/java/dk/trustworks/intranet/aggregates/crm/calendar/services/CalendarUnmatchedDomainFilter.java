package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;

import java.util.Locale;
import java.util.Set;

/**
 * Which unattributed meeting domains are worth suggesting as a company (spec §2.5).
 *
 * <p>Pure and static, so the DB-free tier that gates every deploy holds the whole rule. It
 * is the one thing standing between "companies colleagues keep meeting" and a list of
 * gmail.com, every conference booking system and every recruitment agency's mail relay.
 *
 * <p>The freemail and own-domain deny-list is {@link AccountService#DENIED_DOMAINS}, the
 * same one that refuses to let a person claim {@code gmail.com} as a client's domain. It is
 * shared rather than copied because two spellings of "domains that identify nobody" would
 * drift, and the drift shows up as somebody being offered "add hotmail.com as a company".
 */
final class CalendarUnmatchedDomainFilter {

    /**
     * Domains that carry meetings but never identify the company on the other side of the
     * table: calendar and scheduling tools that add themselves as attendees.
     */
    private static final Set<String> TOOLING_DOMAINS = Set.of(
            "resource.calendar.google.com", "calendar.google.com", "group.calendar.google.com",
            "teams.microsoft.com", "zoom.us", "calendly.com", "resource.calendar.microsoft.com");

    private CalendarUnmatchedDomainFilter() {
    }

    /** Whether a domain nobody claims is worth showing somebody. */
    static boolean isSuggestable(String domain) {
        if (domain == null) {
            return false;
        }
        String value = domain.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || !value.contains(".") || value.length() > AccountService.MAX_DOMAIN_CHARS) {
            return false;
        }
        if (AccountService.DENIED_DOMAINS.contains(value)) {
            return false;
        }
        if (TOOLING_DOMAINS.contains(value)) {
            return false;
        }
        // A subdomain of our own tenant is still us: onmicrosoft.com addresses and
        // mail.trustworks.dk are both Trustworks, and neither is a company to add.
        return !value.endsWith(".trustworks.dk") && !value.endsWith(".onmicrosoft.com");
    }
}
