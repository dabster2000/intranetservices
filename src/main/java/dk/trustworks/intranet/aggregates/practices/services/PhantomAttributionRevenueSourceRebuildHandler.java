package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.services.PhantomAttributionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** Strict evidence-only PHANTOM rebuild under the recovery service's durable owner token. */
@ApplicationScoped
public class PhantomAttributionRevenueSourceRebuildHandler
        implements PracticeRevenueSourceRebuildHandler {
    @Inject
    PhantomAttributionService attributionService;

    @Override
    public PracticeRevenueSourceRecoveryService.Category category(){
        return PracticeRevenueSourceRecoveryService.Category.PHANTOM_ATTRIBUTION;
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public RebuildResult rebuild(RebuildRequest request){
        attributionService.deriveRangeStrict(request.fromInclusive(),request.toInclusive());
        return RebuildResult.success();
    }
}
