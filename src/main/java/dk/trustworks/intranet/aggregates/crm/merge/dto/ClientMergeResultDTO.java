package dk.trustworks.intranet.aggregates.crm.merge.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What a merge DID — the same shape as the preview's counts, re-derived inside the
 * transaction that committed them, plus the audit row it wrote.
 */
public record ClientMergeResultDTO(
        String auditUuid,
        String winnerUuid,
        String loserUuid,
        LocalDateTime mergedAt,
        String accountFrom,
        List<ClientMergePreviewDTO.TableCount> moves,
        List<ClientMergePreviewDTO.TableCount> dropped,
        List<ClientMergePreviewDTO.EconomicsCustomerRef> orphanedEconomics,
        List<ClientMergePreviewDTO.EconomicsCustomerRef> movedEconomics,
        List<String> skippedTables,
        long totalMoves,
        long totalDropped) {}
