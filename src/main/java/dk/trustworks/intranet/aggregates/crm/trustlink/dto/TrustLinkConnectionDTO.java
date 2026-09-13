package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One person at a client organisation as TrustLink sees them, straight off the wire.
 *
 * <p>This is the transport shape of an item in {@code POST /api/connections/search} and
 * nothing else. It is deliberately not the persisted shape: {@code trustlink_connection}
 * stores one row per (client, person) pair because a TrustLink company name can be aliased
 * by more than one Intra client, and the connected trustworkers become their own rows.
 * Keeping the two apart means a change in TrustLink's payload never silently reshapes what
 * is at rest.
 *
 * <p><strong>PII.</strong> Every field here is third-party personal data — the name, job
 * title and LinkedIn profile of somebody who does not work for Trustworks and has not been
 * asked. It is shown behind {@code accounts:read}, it is excluded from the prod → staging
 * copy, and it must never appear in a log line. Log counts, never items.
 *
 * <p>{@code tier} is TrustLink's own connection-strength grade. The sync only ingests
 * tier 5 — the tier that means a real, personal connection — and the sync service verifies
 * that on every item it accepts, because the API answers a filter it does not understand
 * by ignoring it rather than by failing.
 *
 * @param connectedTrustworkers the Trustworks people TrustLink says know this person; may
 *                              be empty, and the names are TrustLink's spelling, not Intra's
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrustLinkConnectionDTO(
        String personId,
        String fullName,
        String position,
        int tier,
        String companyName,
        String companyId,
        boolean isCustomer,
        String linkedInUrl,
        List<ConnectedTrustworker> connectedTrustworkers,
        int coffeeCount,
        int commentCount) {

    /** Never null, so callers can iterate the edges without a guard. */
    public List<ConnectedTrustworker> connectedTrustworkers() {
        return connectedTrustworkers == null ? List.of() : connectedTrustworkers;
    }

    /**
     * One edge: a Trustworks person knows this external person, and when the connection
     * was made.
     *
     * <p>{@code connectedOn} arrives as a LOCAL datetime with no zone and a zero time
     * ({@code 2013-03-01T00:00:00}), which is TrustLink saying "this day", not "this
     * instant". It is parsed as a {@link LocalDateTime} and stored as a DATE precisely so
     * that nothing downstream is tempted to apply a timezone to a LinkedIn connection
     * anniversary from 2013. It may be null; an undated connection is still a connection.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConnectedTrustworker(String name, LocalDateTime connectedOn) {

        /** The date to persist, or null when TrustLink did not record one. */
        public LocalDate connectedOnDate() {
            return connectedOn == null ? null : connectedOn.toLocalDate();
        }
    }
}
