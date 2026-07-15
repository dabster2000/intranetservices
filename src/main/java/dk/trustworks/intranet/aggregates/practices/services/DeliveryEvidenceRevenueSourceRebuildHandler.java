package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** DELIVERY retention-gap recheck; completion/versioning remains owned by the recovery service. */
@ApplicationScoped
public class DeliveryEvidenceRevenueSourceRebuildHandler implements PracticeRevenueSourceRebuildHandler {
    @Inject PracticeDeliveryEvidenceRecoveryService recoveryService;

    @Override public PracticeRevenueSourceRecoveryService.Category category(){
        return PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE;
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public RebuildResult rebuild(RebuildRequest request){
        var result=recoveryService.rebuild(request);
        return RebuildResult.deliverySuccess(result.observedFactChangeLogId());
    }
}
