package dk.trustworks.intranet.aggregates.invoice.model.dto;

import java.math.BigDecimal;
import java.util.List;

public record AcceptAttributionsRequest(
    List<ItemAttribution> items
) {
    public record ItemAttribution(
        String itemUuid,
        List<ConsultantShare> attributions
    ) {}

    public record ConsultantShare(
        String consultantUuid,
        BigDecimal sharePct
    ) {}
}
