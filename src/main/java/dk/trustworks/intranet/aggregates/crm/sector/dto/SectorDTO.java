package dk.trustworks.intranet.aggregates.crm.sector.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * The sector page (sectors spec §6.2): the card plus everything the Overview and Attention
 * tabs need. The accounts themselves are not here — the page's Accounts tab reads the same
 * accounts list every other surface does, filtered to the segment.
 *
 * @param flags         what needs attention, one row per account and kind
 * @param recentChanges what moved across the sector in the last 30 days — band moves, new
 *                      leads, signals, reviews
 * @param ownerWorkload how many Strategic/Active accounts each owner runs in the sector
 * @param signals       the NEW signals across the sector, waiting for a decision
 * @param consultants   distinct people on running contracts in the sector — what a
 *                      CONSULTANTS objective reads
 * @param viewerIsManagement whether the person reading this (X-Requested-By) holds a
 *                      management role by person — the same rule that lets them name the
 *                      sector lead and decide any signal. The page renders those controls
 *                      from this flag; the writes re-check the rule themselves.
 */
public record SectorDTO(
        SectorSummaryDTO summary,
        List<SectorFlagDTO> flags,
        List<SectorChangeDTO> recentChanges,
        List<OwnerLoadDTO> ownerWorkload,
        List<SectorSignalDTO> signals,
        List<PersonDTO> consultants,
        boolean viewerIsManagement) {

    /**
     * @param kind PLAN_MISSING, PLAN_OVERDUE, QUIET, UNOWNED, CONTRACT_EXPIRING_NO_EXTENSION
     *             or SIGNAL_WAITING
     * @param refUuid the contract or signal the flag is about, when there is one
     */
    public record SectorFlagDTO(
            String kind,
            String clientUuid,
            String clientName,
            String band,
            String detail,
            LocalDate date,
            String refUuid) {
    }

    /** One row of "what changed", already composed; {@code source} matches the account feed's vocabulary. */
    public record SectorChangeDTO(
            String id,
            String source,
            String summary,
            LocalDate occurredAt,
            String clientUuid,
            String clientName) {
    }

    public record OwnerLoadDTO(PersonDTO owner, int strategic, int active) {
    }

    /**
     * A NEW signal as the Attention tab shows it. {@code clientHasOwner} is what decides
     * whether the SECTOR LEAD may decide it — spec §3.4 hands an unowned account's signals
     * to the sector lead.
     */
    public record SectorSignalDTO(
            String uuid,
            String clientUuid,
            String clientName,
            boolean clientHasOwner,
            String text,
            String personName,
            String personRole,
            String signalType,
            PersonDTO author,
            LocalDate createdAt) {
    }
}
