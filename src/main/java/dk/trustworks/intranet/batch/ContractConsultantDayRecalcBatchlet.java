package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.Batchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.time.LocalDate;

@Dependent
@Named("contractConsultantDayRecalcBatchlet")
@BatchExceptionTracking
public class ContractConsultantDayRecalcBatchlet implements Batchlet {

    @Inject
    BudgetCalculatingExecutor budgetCalculatingExecutor;

    @Inject @BatchProperty(name = "userUuid")
    String userUuid;

    @Inject @BatchProperty(name = "date")
    String dateStr;

    @Override
    @ActivateRequestContext
    @Transactional(TxType.REQUIRES_NEW)
    public String process() throws Exception {
        LocalDate date = LocalDate.parse(dateStr);
        budgetCalculatingExecutor.recalculateUserDailyBudgets(userUuid, date);
        return "OK";
    }

    @Override
    public void stop() throws Exception {
        // no-op
    }
}
