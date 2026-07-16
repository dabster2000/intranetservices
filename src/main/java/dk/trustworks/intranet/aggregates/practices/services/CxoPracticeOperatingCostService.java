package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.extern.jbosslog.JBossLog;

/** Legacy operating-cost adapter over the single canonical cost snapshot provider. */
@JBossLog
@ApplicationScoped
public class CxoPracticeOperatingCostService {

    @Inject
    PracticeCostSnapshotProvider snapshotProvider;

    public PracticeOperatingCostResponseDTO getOperatingCost(CostSource requestedCostSource) {
        CostSource source = requestedCostSource == null ? CostSource.BOOKED : requestedCostSource;
        try {
            PracticeCostSnapshotProvider.Snapshot snapshot = snapshotProvider.getLegacySnapshot(source);
            if (snapshot.servingEnabled() && !snapshot.canonical().windowAvailable()) {
                throw new ServiceUnavailableException(
                        "No complete 12-month operating-cost window exists in the 36-month search horizon.");
            }
            return snapshot.response();
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.errorf(e, "practice operating-cost canonical snapshot read failed: source=%s", source);
            throw new ServiceUnavailableException(
                    "Operating-cost evidence is refreshing or unavailable; values are withheld.");
        }
    }
}
