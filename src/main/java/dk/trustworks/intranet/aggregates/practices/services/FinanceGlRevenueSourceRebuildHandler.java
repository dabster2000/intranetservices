package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.financeservice.services.FinanceGlRecoveryImportService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** FINANCE_GL stale-source recovery backed by the authoritative bounded e-conomic import. */
@ApplicationScoped
public class FinanceGlRevenueSourceRebuildHandler implements PracticeRevenueSourceRebuildHandler {

    @Inject
    FinanceGlRecoveryImportService recoveryImportService;

    @Override
    public PracticeRevenueSourceRecoveryService.Category category() {
        return PracticeRevenueSourceRecoveryService.Category.FINANCE_GL;
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public RebuildResult rebuild(RebuildRequest request) {
        recoveryImportService.rebuild(
                request.fromInclusive(), request.toInclusive(), request.recoveryToken());
        return RebuildResult.success();
    }
}
