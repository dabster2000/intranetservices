package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.batch.monitoring.AbstractEnhancedBatchlet;
import dk.trustworks.intranet.batch.monitoring.BatchletResult;
import dk.trustworks.intranet.recalc.DayRecalcService;
import dk.trustworks.intranet.recalc.RecalcResult;
import dk.trustworks.intranet.recalc.RecalcTrigger;
import jakarta.batch.api.BatchProperty;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

/**
 * Enhanced batchlet to recalculate user data for a specific day after a status change.
 * Delegates to DayRecalcService to ensure correct pipeline ordering and error handling.
 */
@Dependent
@Named("userStatusDayRecalcBatchletEnhanced")
@JBossLog
public class UserStatusDayRecalcBatchletEnhanced extends AbstractEnhancedBatchlet {

    @Inject
    DayRecalcService recalcService;

    @Inject @BatchProperty(name = "userUuid")
    String userUuid;

    @Inject @BatchProperty(name = "date")
    String dateStr;

    @Inject @BatchProperty(name = "trigger")
    String triggerName;
    
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

            RecalcTrigger trigger;
            try {
                // Default to STATUS_CHANGE for this batchlet if not provided
                trigger = (triggerName != null && !triggerName.isBlank()) ? RecalcTrigger.valueOf(triggerName) : RecalcTrigger.STATUS_CHANGE;
            } catch (IllegalArgumentException ex) {
                log.warnf("Unknown trigger '%s', defaulting to STATUS_CHANGE", triggerName);
                trigger = RecalcTrigger.STATUS_CHANGE;
            }

            RecalcResult r = recalcService.recalc(userUuid, date, trigger);

            if (r.isFailed()) {
                return BatchletResult.partial("Partial success: " + r.summary());
            } else {
                return BatchletResult.success("Successfully processed user " + userUuid + " on " + date + "; " + r.summary());
            }
            
        } catch (Exception e) {
            log.errorf(e, "Fatal error processing user status updates for %s on %s", userUuid, dateStr);
            return BatchletResult.failure(
                "Fatal error processing user status updates for " + userUuid + " on " + dateStr,
                e
            );
        }
    }
}