package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.AliasSource;

import java.time.LocalDateTime;

/**
 * One TrustLink company name mapped onto one Intra client, as the alias editor sees it.
 *
 * <p>The editor needs {@code source} and {@code enabled} visible, not just the name,
 * because the two together are the only explanation a user gets for why a company keeps
 * coming back or stubbornly stays away: an {@code AUTO} row was derived by last night's
 * matcher and may be re-derived, while a {@code MANUAL} row is untouchable by the job —
 * including a {@code MANUAL} row with {@code enabled = false}, which is how a person says
 * "this company is not this client" in a way that survives re-seeding.
 *
 * <p>{@code createdBy} is a user uuid or null for rows the seeder wrote. It is here so the
 * editor can say who asserted a hand-made mapping; a mapping nobody can account for is a
 * mapping nobody dares remove.
 *
 * @param companyName TrustLink's exact spelling. The search API is case-sensitive and does
 *                    no prefix or contains matching, so "novo nordisk" finds nothing —
 *                    which is why names are chosen from the typeahead, not typed freely
 */
public record TrustLinkAliasDTO(
        String uuid,
        String clientUuid,
        String companyName,
        AliasSource source,
        boolean enabled,
        LocalDateTime createdAt,
        String createdBy) {
}
