package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.dto.ExpenseFile;
import lombok.extern.jbosslog.JBossLog;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.TransactionManager;
import javax.transaction.Transactional;
import javax.ws.rs.NotAllowedException;
import javax.ws.rs.core.Response;
import java.io.IOException;

@JBossLog
@RequestScoped
public class ExpenseService {

    @Inject
    ExpenseFileService expenseFileService;

    @Inject
    EconomicsService economicsService;

    @Inject
    TransactionManager tm;

    @Transactional
    public void processExpense(Expense expense) throws IOException {

        //validate account
        if (economicsService.validateAccount(expense)) {

            try {
                expense.setStatus("oprettet");

                //save expense to db
                expense.persistAndFlush();

                //save expense file to AWS
                ExpenseFile expenseFile = new ExpenseFile(expense.getUuid(), expense.getExpensefile());
                PutObjectResponse awsResponse = expenseFileService.saveFile(expenseFile);

                // if user has account and file is stored at AWS send files to e-conomics
                if (awsResponse != null ) {

                    UserAccount user = UserAccount.findById(expense.getUseruuid());
                    if (user != null) {
                        //send expense to economics
                        sendExpense(expense, expenseFile, user);
                    } else {
                        //user does not have an economics account - await userAccount creation
                        log.info("unknown user created expense: "+ expense.getUseruuid());
                    }

                } else {
                    log.error("aws s3/user account issue. Expense : "+ expense +", aws response: "+ awsResponse +", useraccount: "+ expense.getUseruuid());
                    tm.setRollbackOnly();
                    throw new IOException("aws s3/user account issue. Expense : "+ expense +", aws response: "+ awsResponse +", useraccount: "+ expense.getUseruuid());
                }

            }catch (Exception e) {
                log.error("exception posting expense: " + expense + ", exception: " + e.getMessage(), e);
                throw new IOException("exception posting expense: " + expense.getUuid() + ", exception: " + e.getMessage(), e);//Response.status(500).entity(e).build();
            }
        } else {
            throw new NotAllowedException("Account not valid: " + expense.getAccount());
        }
    }

    @Transactional
    public void sendExpense(Expense expense, ExpenseFile expenseFile, UserAccount userAccount) throws IOException {
        System.out.println("ExpenseService.sendExpense");

        Response response = economicsService.sendVoucher(expense, expenseFile, userAccount);

        if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
            //update expense in db
            expense.setStatus("afsendt");
            Expense.update("status = ?1, " + "vouchernumber = ?2 " + "WHERE uuid like ?3 ", expense.getStatus(), expense.getVouchernumber(), expense.getUuid());
            log.info("Updated expense "+expense);
        } else {
            log.error("unable to send voucher to economics: " + expense);
            throw new IOException("Economics error on uploading file. Expense : "+ expense +", response: "+ response);
        }
    }

}
