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
 */
public record AccountDTO(
        String clientUuid,
        String band,
        PersonDTO owner,
        List<PersonDTO> supportedBy,
        /** The GTM team — a FOCUS bubble — and the client's own ACCOUNT_TEAM bubble, when set. */
        String gtmBubbleUuid,
        String gtmTeamName,
        String accountTeamBubbleUuid,
        String accountTeamName,
        /** The sector the client is in, with its lead, and that sector's plan as a chip. */
        SectorRefDTO sector,
        SectorPlanRefDTO sectorPlan,
        String slackSpace,
        String nextStep,
        List<ClientDomainDTO> domains,
        List<BandHistoryDTO> bandHistory,
        /** True when no client_account row exists yet — every value above is the default. */
        boolean isDefault) {
}
