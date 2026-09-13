package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Trustworks colleague as TrustLink knows them — one element of {@code GET
 * /api/trustworkers} (61 rows today).
 *
 * <p>This is the input to the name-to-{@code User} ladder, and it is a weaker input than it
 * looks: {@code email} is null for 21 of the 61 rows, and the ones that are set carry
 * inconsistent casing ({@code Rasmus.Rask.Andersen@trustworks.dk}). That is the whole
 * reason the matcher has four rungs instead of one e-mail lookup, and the reason rungs 3
 * and 4 exist at all.
 *
 * <p>{@code name} is TrustLink's spelling of the person, not Intra's. It is stored verbatim
 * on the edge so that an unmatched trustworker is still shown by name rather than dropped —
 * losing "Marie Dorthea · 242 tier-5 connections" because a name did not resolve would
 * throw away the signal this feature exists for.
 *
 * @param tier5ConnectionCount how many tier-5 connections TrustLink credits them with;
 *                             useful for sanity-checking a run, not persisted
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrustLinkTrustworkerDTO(
        String id,
        String name,
        String email,
        boolean isActive,
        int tier5ConnectionCount) {
}
