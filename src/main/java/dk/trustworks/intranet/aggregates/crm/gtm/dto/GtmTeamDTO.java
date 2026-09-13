package dk.trustworks.intranet.aggregates.crm.gtm.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;

import java.util.List;

/**
 * One GTM team on the GTM teams tab (sectors spec §6.1): a {@code FOCUS} bubble with its
 * lead, members, the sectors it covers, the accounts that point at it, and the
 * Strategic/Active accounts in its sectors that have no team yet.
 *
 * @param accounts          accounts whose {@code client_account.gtm_bubble_uuid} is this
 *                          bubble, with their Responsible and Supported-by; Backlog
 *                          accounts are listed without roles
 * @param suggestedAccounts Strategic/Active accounts in the covered sectors with no GTM
 *                          team, capped
 */
public record GtmTeamDTO(
        String uuid,
        String name,
        String description,
        String slackChannel,
        PersonDTO lead,
        PersonDTO coLead,
        List<PersonDTO> members,
        List<String> sectors,
        List<GtmAccountDTO> accounts,
        List<GtmAccountDTO> suggestedAccounts) {

    public record GtmAccountDTO(
            String clientUuid,
            String clientName,
            String band,
            String segment,
            PersonDTO owner,
            List<PersonDTO> supportedBy) {
    }
}
