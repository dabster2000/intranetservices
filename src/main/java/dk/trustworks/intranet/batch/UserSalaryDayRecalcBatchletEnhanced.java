package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.batch.monitoring.AbstractEnhancedBatchlet;
import dk.trustworks.intranet.batch.monitoring.BatchletResult;
import dk.trustworks.intranet.bi.services.UserSalaryCalculatorService;
import jakarta.batch.api.BatchProperty;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

@Dependent
@Named("userSalaryDayRecalcBatchletEnhanced")
@JBossLog
public class UserSalaryDayRecalcBatchletEnhanced extends AbstractEnhancedBatchlet {

    @Inject
    UserSalaryCalculatorService userSalaryCalculatorService;

    @Inject @BatchProperty(name = "userUuid")
    String userUuid;

    @Inject @BatchProperty(name = "date")
    String dateStr;
    
    @Override
    protected String generatePartitionId() {
        return userUuid + "_" + dateStr;
    }

    @Override
    @ActivateRequestContext
    @Transactional(TxType.REQUIRES_NEW)
    protected BatchletResult performWork(String partitionId) throws Exception {
        try {
            if (userUuid == null || userUuid.isBlank() || dateStr == null || dateStr.isBlank()) {
                return BatchletResult.failure(
                    "Missing partition properties: userUuid=" + userUuid + ", date=" + dateStr,
                    new jakarta.batch.operations.BatchRuntimeException(
                        "Missing partition properties: userUuid=" + userUuid + ", date=" + dateStr)
                );
            }
            
            LocalDate date = LocalDate.parse(dateStr);
            
            log.debugf("Recalculating salary for user %s on %s", userUuid, date);
            userSalaryCalculatorService.recalculateSalary(userUuid, date);
            log.debugf("Successfully recalculated salary for user %s on %s", userUuid, date);
            
            return BatchletResult.success("Successfully processed salary for user " + userUuid + " on " + date);
            
        } catch (Exception e) {
            log.errorf(e, "Failed to recalculate salary for user %s on %s", userUuid, dateStr);
            return BatchletResult.failure(
                "Failed to recalculate salary for user " + userUuid + " on " + dateStr,
                e
            );
        }
    }
}