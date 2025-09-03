package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.bi.services.UserSalaryCalculatorService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.Batchlet;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.LocalDate;

@Dependent
@Named("userSalaryDayRecalcBatchlet")
public class UserSalaryDayRecalcBatchlet implements Batchlet {

    @Inject
    UserSalaryCalculatorService userSalaryCalculatorService;

    @Inject @BatchProperty(name = "userUuid")
    String userUuid;

    @Inject @BatchProperty(name = "date")
    String dateStr;

    @Override
    @ActivateRequestContext // ensures request scoped beans are available if used
    @Transactional(TxType.REQUIRES_NEW) // strong isolation per partition/day
    public String process() throws Exception {
        LocalDate date = LocalDate.parse(dateStr);
        userSalaryCalculatorService.recalculateSalary(userUuid, date);
        return "OK";
    }

    @Override
    public void stop() throws Exception {
        // no-op
    }
}
