package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

public record EnrichedBonusLineDTO(
        String uuid,
        String invoiceItemUuid,
        double percentage,
        double lineAmount,
        double estimatedShare,
        boolean editable,
        String origin
) {}
