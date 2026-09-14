package dk.trustworks.intranet.aggregates.crm.account.dto;

import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorPlanRefDTO;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorRefDTO;

import java.util.List;

/**
 * Everything the account page's header needs (CRM spec §4.3).
 *
 * <p>{@code owner} is the client's account manager — the Responsible — resolved to a
 * name. It is null on an unowned account, which the header renders as an "Unowned" chip
 * on Active/Strategic and as "Backlog — no owner needed" on Backlog. That difference is
 * the whole point of the band.
 *
 * <p>The three people fields are the WHOLE of who is on an account since V598: owner,
 * supporters, team. There is no fourth store — the {@code ACCOUNT_TEAM} bubble link that
 * used to sit here is gone, and so is the {@code RESPONSIBLE} role row that mirrored the
 * owner.
 */
public record AccountDTO(
        String clientUuid,
        String band,
        PersonDTO owner,
        List<PersonDTO> supportedBy,
        /** The account team (V598). Empty is a real and common answer, not an error. */
        List<PersonDTO> members,
        /** The GTM team — a FOCUS bubble — when set. */
        String gtmBubbleUuid,
        String gtmTeamName,
        /**
         * What Trustworks is to this company, derived per read: customer, former customer,
         * prospect or contact, with the dates and counts behind it (spec §2.1).
         */
        AccountRelationshipDTO relationship,
        /** The sector the client is in, with its lead, and that sector's plan as a chip. */
        SectorRefDTO sector,
        SectorPlanRefDTO sectorPlan,
        String slackSpace,
        /**
         * The channel id the nightly sync resolved {@code slackSpace} to, and why it could
         * not: {@code NOT_FOUND}, {@code NOT_IN_CHANNEL} or {@code ARCHIVED}. Both null on a
         * healthy link, and both null until the first run after the name was set (V594).
         */
        String slackChannelId,
        String slackLinkError,
        String nextStep,
        List<ClientDomainDTO> domains,
        List<BandHistoryDTO> bandHistory,
        /** True when no client_account row exists yet — every value above is the default. */
        boolean isDefault) {
}
