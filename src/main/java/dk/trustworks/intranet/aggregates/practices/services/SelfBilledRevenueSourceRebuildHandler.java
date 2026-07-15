package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledImportService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** The only source category that currently exposes an authoritative bounded rebuild API. */
@ApplicationScoped
public class SelfBilledRevenueSourceRebuildHandler implements PracticeRevenueSourceRebuildHandler {
    @Inject
    SelfBilledImportService importService;

    @Override
    public PracticeRevenueSourceRecoveryService.Category category() {
        return PracticeRevenueSourceRecoveryService.Category.SELF_BILLED;
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public RebuildResult rebuild(RebuildRequest request) {
        importService.captureForRecovery(request.fromInclusive(), request.toInclusive());
        return RebuildResult.success();
    }
}
