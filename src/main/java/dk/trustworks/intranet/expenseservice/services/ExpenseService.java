package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@JBossLog
@RequestScoped
public class ExpenseService {

    // Status constants used throughout the expense processing flow
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_VALIDATED = "VALIDATED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_NO_FILE = "NO_FILE";
    public static final String STATUS_NO_USER = "NO_USER";
    public static final String STATUS_UP_FAILED = "UP_FAILED";
    public static final String STATUS_DELETED = "DELETED";

    @Inject
    ExpenseFileService expenseFileService;

    @Inject
    EconomicsService economicsService;

    @Inject
    dk.trustworks.intranet.expenseservice.events.ExpenseCreatedProducer expenseCreatedProducer;

    public List<Expense> findByUser(String useruuid) {
        return Expense.find("useruuid", useruuid).list();
    }

    public List<Expense> findByUserLimited(String useruuid) {
        return Expense.find("useruuid = ?1 AND status NOT LIKE ?2 ORDER BY expensedate DESC LIMIT 40", useruuid, STATUS_DELETED).list();
    }

    public List<Expense> findByUserAndPaidOutMonth(String useruuid, LocalDate month) {
        return Expense.find("useruuid = ?1 and status = ?2 AND " +
                "(YEAR(paidOut) = YEAR(?3) AND MONTH(paidOut) = MONTH(?3))", useruuid, STATUS_PROCESSED, month).list();
    }

    public List<Expense> findByUserAndUnpaidAndMonth(String useruuid, LocalDate month) {
        return Expense.find("useruuid = ?1 and status = ?2 AND " +
                "(paidOut is null OR YEAR(paidOut) = YEAR(?3) AND MONTH(paidOut) = MONTH(?3))", useruuid, STATUS_PROCESSED, month).list();
    }

    @Transactional
    public void processExpense(Expense expense) throws IOException {
        log.info("Processing new expense " + expense.getUuid());
        try {
            //save expense to db
            expense.setStatus(STATUS_CREATED);
            expense.persist();
            log.info("Expense persisted with status CREATED: " + expense.getUuid());

            //save expense file to AWS
            ExpenseFile expenseFile = new ExpenseFile(expense.getUuid(), expense.getExpensefile());
            PutObjectResponse awsResponse = expenseFileService.saveFile(expenseFile);

            if(awsResponse==null) {
                log.error("aws s3/user account issue. Expense : "+ expense +", aws response: "+ awsResponse +", useraccount: "+ expense.getUseruuid());
                QuarkusTransaction.setRollbackOnly();
                throw new IOException("aws s3/user account issue. Expense : "+ expense +", aws response: "+ awsResponse +", useraccount: "+ expense.getUseruuid());
            }
            log.info("Expense file stored in S3 for " + expense.getUuid());

            // Publish Kafka event to validate asynchronously
            try {
                expenseCreatedProducer.publishCreated(expense.getUuid());
            } catch (Exception ex) {
                log.error("Failed to publish expense created event for uuid=" + expense.getUuid(), ex);
            }

        } catch (Exception e) {
            log.error("exception posting expense: " + expense + ", exception: " + e.getMessage(), e);
            throw new IOException("exception posting expense: " + expense.getUuid() + ", exception: " + e.getMessage(), e);//Response.status(500).entity(e).build();
        }
        log.info("Expense processed and stored with uuid: " + expense.getUuid());
    }

    @Transactional
    public void consumeCreate() throws IOException {
        log.info("Starting scheduled expense upload job");
        List<Expense> expenses = Expense.<Expense>stream("status", STATUS_VALIDATED)
                .filter(e -> e.getAmount() > 0)
                .filter(e -> e.getDatecreated().isBefore(LocalDate.now().minusDays(2)))
                .limit(1).toList();
        log.info("Expenses found with status VALIDATED: " + expenses.size());
        if (expenses.isEmpty()) return;

        for (Expense expense : expenses) {
            log.info("Processing expense " + expense.getUuid());
            updateStatus(expense, STATUS_PROCESSING);

            ExpenseFile expenseFile = expenseFileService.getFileById(expense.getUuid());
            if (expenseFile == null || expenseFile.getExpensefile().isEmpty()) {
                log.error("No expense file found for expense " + expense);
                updateStatus(expense, STATUS_NO_FILE);
                continue;
            }

            List<UserAccount> userAccounts = UserAccount.find("useruuid = ?1", expense.getUseruuid()).list();
            if (userAccounts.size() != 1) {
                log.warn("No single user account found for expense " + expense);
                updateStatus(expense, STATUS_NO_USER);
                continue;
            }

            UserAccount userAccount = userAccounts.get(0);
            sendExpense(expense, expenseFile, userAccount);
        }
    }


    public void sendExpense(Expense expense, ExpenseFile expenseFile, UserAccount userAccount) throws IOException {
        Response response;
        try {
            response = economicsService.sendVoucher(expense, expenseFile, userAccount);
            updateStatus(expense, STATUS_PROCESSING);
        } catch (Exception e) {
            log.error("Exception posting expense: " + expense + ", exception: " + e.getMessage(), e);
            updateStatus(expense, STATUS_UP_FAILED);
            throw new IOException("Exception posting expense: " + expense.getUuid() + ", exception: " + e.getMessage(), e);
        }

        if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
            updateStatus(expense, STATUS_PROCESSED);
        } else {
            log.error("unable to send voucher to economics: " + expense);
            updateStatus(expense, STATUS_UP_FAILED);
            throw new IOException("Economics error on uploading file. Expense : "+ expense +", response: "+ response);
        }
    }

    public void updateStatus(Expense expense, String status) {
        QuarkusTransaction.requiringNew().run(() -> {
            Expense.update("status = ?1, " +
                    "vouchernumber = ?2, " +
                    "journalnumber = ?3, " +
                    "accountingyear = ?4, " +
                    "account = ?5 " +
                    "WHERE uuid like ?6 ",
                    status,
                    expense.getVouchernumber(),
                    expense.getJournalnumber(),
                    expense.getAccountingyear(),
                    expense.getAccount(),
                    expense.getUuid());
            log.info("Updated expense uuid=" + expense.getUuid() + " -> status=" + status + ", triple=" + expense.getJournalnumber() + "/" + expense.getAccountingyear() + "-" + expense.getVouchernumber());
        });
    }

    @Transactional
    public void setPaidAndUpdate(Expense expense) {
        expense.setPaidOut(LocalDateTime.now());
        Expense.update("paidOut = ?1 WHERE uuid like ?2 ", expense.getPaidOut(), expense.getUuid());
    }

    @Transactional
    public void clearPaidAndUpdate(Expense expense) {
        expense.setPaidOut(null);
        Expense.update("paidOut = ?1 WHERE uuid like ?2 ", expense.getPaidOut(), expense.getUuid());
    }
}


// if user has account and file is stored at AWS send files to e-conomics
            /*
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
                QuarkusTransaction.setRollbackOnly();
                throw new IOException("aws s3/user account issue. Expense : "+ expense +", aws response: "+ awsResponse +", useraccount: "+ expense.getUseruuid());
            }

             */
