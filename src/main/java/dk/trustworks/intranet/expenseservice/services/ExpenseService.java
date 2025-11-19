package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.exceptions.ExpenseUploadException;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.AccountingYear;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.Entries;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.Journal;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.Voucher;
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
    // Workflow statuses:
    public static final String STATUS_CREATED = "CREATED";           // Initial creation
    public static final String STATUS_VALIDATED = "VALIDATED";       // AI validated, ready for upload
    public static final String STATUS_PROCESSING = "PROCESSING";     // Currently being uploaded

    // Success statuses (distinguishes upload vs verification):
    public static final String STATUS_VOUCHER_CREATED = "VOUCHER_CREATED";     // Voucher created, file pending
    public static final String STATUS_UPLOADED = "UPLOADED";                   // Successfully uploaded to e-conomics
    public static final String STATUS_VERIFIED_UNBOOKED = "VERIFIED_UNBOOKED"; // Verified in journal (not booked)
    public static final String STATUS_VERIFIED_BOOKED = "VERIFIED_BOOKED";     // Verified booked to ledger

    // Error statuses:
    public static final String STATUS_NO_FILE = "NO_FILE";           // File not found in S3
    public static final String STATUS_NO_USER = "NO_USER";           // User account issue
    public static final String STATUS_UP_FAILED = "UP_FAILED";       // Upload failed
    public static final String STATUS_DELETED = "DELETED";           // Deleted in e-conomics

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
        // Include all success statuses (UPLOADED, VERIFIED_UNBOOKED, VERIFIED_BOOKED)
        return Expense.find("useruuid = ?1 and status IN (?2, ?3, ?4) AND " +
                "(YEAR(paidOut) = YEAR(?5) AND MONTH(paidOut) = MONTH(?5))",
                useruuid, STATUS_UPLOADED, STATUS_VERIFIED_UNBOOKED, STATUS_VERIFIED_BOOKED, month).list();
    }

    public List<Expense> findByUserAndUnpaidAndMonth(String useruuid, LocalDate month) {
        // Include all success statuses (UPLOADED, VERIFIED_UNBOOKED, VERIFIED_BOOKED)
        return Expense.find("useruuid = ?1 and status IN (?2, ?3, ?4) AND " +
                "(paidOut is null OR YEAR(paidOut) = YEAR(?5) AND MONTH(paidOut) = MONTH(?5))",
                useruuid, STATUS_UPLOADED, STATUS_VERIFIED_UNBOOKED, STATUS_VERIFIED_BOOKED, month).list();
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
            try {
                processExpenseItem(expense);
            } catch (IOException e) {
                // Error already logged and status updated in processExpenseItem()
                log.error("Failed to process expense " + expense.getUuid() + ": " + e.getMessage());
            }
        }
    }

    // Constants for retry management
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int MIN_MINUTES_BETWEEN_RETRIES = 10;

    /**
     * Process a single expense item - used by both scheduled job and batch processing.
     * This method contains the core expense processing logic without transaction boundaries
     * (transaction management is handled by the caller).
     *
     * @param expense The expense to process
     * @throws IOException if processing fails
     */
    public void processExpenseItem(Expense expense) throws IOException {
        log.infof("Processing expense %s (retry count: %d, orphaned: %s)",
            expense.getUuid(), expense.getSafeRetryCount(), expense.getIsOrphaned());

        // Pre-flight validation before marking as PROCESSING
        String validationError = validateExpenseForUpload(expense);
        if (validationError != null) {
            log.warn("Pre-flight validation failed for expense " + expense.getUuid() + ": " + validationError);
            updateStatus(expense, STATUS_UP_FAILED, "Pre-flight validation failed: " + validationError);
            throw new IOException("Pre-flight validation failed: " + validationError);
        }

        updateStatus(expense, STATUS_PROCESSING);

        ExpenseFile expenseFile = expenseFileService.getFileById(expense.getUuid());
        if (expenseFile == null || expenseFile.getExpensefile().isEmpty()) {
            log.error("No expense file found for expense " + expense);
            updateStatus(expense, STATUS_NO_FILE, "File not found in S3 or is empty");
            throw new IOException("No expense file found");
        }

        List<UserAccount> userAccounts = UserAccount.find("useruuid = ?1", expense.getUseruuid()).list();
        if (userAccounts.size() != 1) {
            String error = userAccounts.isEmpty() ? "No user account found" : "Multiple user accounts found (" + userAccounts.size() + ")";
            log.warn(error + " for expense " + expense);
            updateStatus(expense, STATUS_NO_USER, error + " for user " + expense.getUseruuid());
            throw new IOException(error);
        }

        UserAccount userAccount = userAccounts.get(0);
        sendExpense(expense, expenseFile, userAccount);
    }

    /**
     * Pre-flight validation to catch common issues before attempting upload
     * @return null if validation passes, error message if validation fails
     */
    private String validateExpenseForUpload(Expense expense) {
        // Validate required fields
        if (expense.getAmount() == null || expense.getAmount() <= 0) {
            return "Invalid amount: " + expense.getAmount();
        }

        if (expense.getAccount() == null || expense.getAccount().isEmpty()) {
            return "Missing expense account";
        }

        if (expense.getUseruuid() == null || expense.getUseruuid().isEmpty()) {
            return "Missing user UUID";
        }

        if (expense.getExpensedate() == null) {
            return "Missing expense date";
        }

        // Validate account is numeric (required for e-conomics)
        try {
            Integer.parseInt(expense.getAccount());
        } catch (NumberFormatException e) {
            return "Invalid account format: " + expense.getAccount() + " (must be numeric)";
        }

        return null; // Validation passed
    }


    public void sendExpense(Expense expense, ExpenseFile expenseFile, UserAccount userAccount) throws IOException {
        Response response;

        // Increment retry count when attempting to send
        expense.incrementRetryCount();
        log.infof("Attempting to send expense %s (attempt %d of %d)",
            expense.getUuid(), expense.getSafeRetryCount(), MAX_RETRY_ATTEMPTS);

        try {
            if (expense.getVouchernumber() > 0
                    && expense.getJournalnumber() != null
                    && expense.getAccountingyear() != null) {
                // Voucher supposedly exists – try to upload file to existing voucher
                log.info("Retrying file upload for existing voucher: " + expense.getJournalnumber() + "/" + expense.getAccountingyear() + "-" + expense.getVouchernumber());
                Journal journal = new Journal(expense.getJournalnumber());
                AccountingYear ay = new AccountingYear(expense.getAccountingyear().replace("_6_", "/"));
                Voucher v = new Voucher(ay, journal, new Entries());

                try {
                    response = economicsService.sendFile(expense, expenseFile, v);
                } catch (ExpenseUploadException e) {
                    // Check if voucher doesn't exist in e-conomics (orphaned reference)
                    if (e.getHttpStatus() != null && e.getHttpStatus() == 404) {
                        log.warnf("Voucher %d not found in e-conomics - detected orphaned reference. " +
                                "Clearing voucher details and creating fresh voucher for expense: %s. " +
                                "Original error: %s",
                                expense.getVouchernumber(), expense.getUuid(), e.getMessage());

                        // Mark as orphaned for smart idempotency
                        expense.markAsOrphaned();

                        // Clear orphaned voucher reference
                        expense.setVouchernumber(0);
                        expense.setJournalnumber(null);
                        expense.setAccountingyear(null);

                        // Create fresh voucher
                        response = economicsService.sendVoucher(expense, expenseFile, userAccount);
                        // Note: sendVoucher internally updates expense with voucher details
                        // We'll set VOUCHER_CREATED after successful file upload in the next section
                    } else {
                        // Not a 404 - propagate exception normally
                        throw e;
                    }
                }
            } else {
                response = economicsService.sendVoucher(expense, expenseFile, userAccount);
                updateStatus(expense, STATUS_PROCESSING);
            }

            if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
                // Clear orphaned status on successful upload
                expense.clearOrphaned();
                updateStatus(expense, STATUS_UPLOADED, null);  // Clear any previous error message
                log.infof("Successfully uploaded expense %s after %d attempts",
                    expense.getUuid(), expense.getSafeRetryCount());
            } else {
                String errorMsg = "HTTP " + response.getStatus() + ": " + response.readEntity(String.class);
                log.error("unable to send voucher to economics: " + expense + ", error: " + errorMsg);
                updateStatus(expense, STATUS_UP_FAILED, errorMsg);
                throw new IOException("Economics error on uploading file. Expense: " + expense.getUuid() + ", error: " + errorMsg);
            }
        } catch (ExpenseUploadException e) {
            log.error("ExpenseUploadException for expense " + expense.getUuid() + ": " + e.getDetailedMessage(), e);

            // Check if this is an orphaned voucher situation (voucher created but file upload failed)
            // In this case, set VOUCHER_CREATED status instead of UP_FAILED
            if (e.getHttpStatus() != null && e.getHttpStatus() == 404 &&
                e.getMessage() != null && e.getMessage().contains("Orphaned voucher detected")) {
                log.info("Voucher created but file upload failed for expense " + expense.getUuid() +
                        " - marking as VOUCHER_CREATED for retry");
                updateStatus(expense, STATUS_VOUCHER_CREATED, "Voucher created, file upload pending: " + e.getDetailedMessage());
            } else {
                updateStatus(expense, STATUS_UP_FAILED, e.getDetailedMessage());
            }

            throw new IOException("Exception uploading expense: " + expense.getUuid(), e);
        } catch (IOException e) {
            // Re-throw IOException as is
            throw e;
        } catch (Exception e) {
            log.error("Unexpected exception posting expense: " + expense.getUuid() + ", exception: " + e.getMessage(), e);
            String errorMsg = "Unexpected error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            updateStatus(expense, STATUS_UP_FAILED, errorMsg);
            throw new IOException("Exception posting expense: " + expense.getUuid() + ", exception: " + e.getMessage(), e);
        }
    }

    public void updateStatus(Expense expense, String status) {
        updateStatus(expense, status, null);
    }

    public void updateStatus(Expense expense, String status, String errorMessage) {
        QuarkusTransaction.requiringNew().run(() -> {
            Expense.update("status = ?1, " +
                    "vouchernumber = ?2, " +
                    "journalnumber = ?3, " +
                    "accountingyear = ?4, " +
                    "account = ?5, " +
                    "errorMessage = ?6, " +
                    "retryCount = ?7, " +
                    "lastRetryAt = ?8, " +
                    "isOrphaned = ?9, " +
                    "accountantNotes = ?10 " +
                    "WHERE uuid like ?11 ",
                    status,
                    expense.getVouchernumber(),
                    expense.getJournalnumber(),
                    expense.getAccountingyear(),
                    expense.getAccount(),
                    errorMessage,
                    expense.getRetryCount(),
                    expense.getLastRetryAt(),
                    expense.getIsOrphaned(),
                    expense.getAccountantNotes(),
                    expense.getUuid());
            log.infof("Updated expense uuid=%s -> status=%s, triple=%s/%s-%d, retry=%d, orphaned=%s%s",
                    expense.getUuid(), status,
                    expense.getJournalnumber(), expense.getAccountingyear(), expense.getVouchernumber(),
                    expense.getSafeRetryCount(), expense.getIsOrphaned(),
                    errorMessage != null ? ", error=" + errorMessage : "");
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

    /**
     * Retry failed expenses that already have voucher numbers.
     * These are cases where voucher creation succeeded but file upload failed.
     * This job runs less frequently than the main job to avoid overwhelming the system.
     */
    @Transactional
    public void retryFailedWithVouchers() throws IOException {
        log.info("Starting retry job for UP_FAILED expenses with existing vouchers");

        // Find UP_FAILED expenses that have voucher numbers (partial success)
        // Also check retry eligibility
        List<Expense> expenses = Expense.<Expense>stream("status = ?1 AND vouchernumber > 0 AND journalnumber IS NOT NULL AND accountingyear IS NOT NULL " +
                        "AND (retryCount IS NULL OR retryCount < ?2)",
                        STATUS_UP_FAILED, MAX_RETRY_ATTEMPTS)
                .filter(e -> e.shouldRetry(MAX_RETRY_ATTEMPTS, MIN_MINUTES_BETWEEN_RETRIES))
                .limit(1)  // Process one at a time
                .toList();

        log.info("Found " + expenses.size() + " UP_FAILED expenses with existing vouchers to retry");

        for (Expense expense : expenses) {
            log.infof("Retrying file upload for expense %s with existing voucher %s/%s-%d (attempt %d)",
                    expense.getUuid(),
                    expense.getJournalnumber(), expense.getAccountingyear(), expense.getVouchernumber(),
                    expense.getSafeRetryCount() + 1);

            // Get the expense file
            ExpenseFile expenseFile = expenseFileService.getFileById(expense.getUuid());
            if (expenseFile == null || expenseFile.getExpensefile().isEmpty()) {
                log.error("No expense file found for expense " + expense);
                updateStatus(expense, STATUS_NO_FILE, "File not found in S3 during retry");
                continue;
            }

            // Get user account (needed for sendExpense)
            List<UserAccount> userAccounts = UserAccount.find("useruuid = ?1", expense.getUseruuid()).list();
            if (userAccounts.size() != 1) {
                String error = userAccounts.isEmpty() ? "No user account found" : "Multiple user accounts found (" + userAccounts.size() + ")";
                log.warn(error + " for expense " + expense);
                updateStatus(expense, STATUS_NO_USER, error + " for user " + expense.getUseruuid());
                continue;
            }

            // Mark as PROCESSING for retry
            updateStatus(expense, STATUS_PROCESSING, "Retrying file upload to existing voucher");

            UserAccount userAccount = userAccounts.get(0);
            try {
                sendExpense(expense, expenseFile, userAccount);
                log.info("Successfully retried expense " + expense.getUuid());
            } catch (Exception e) {
                log.error("Retry failed for expense " + expense.getUuid(), e);
                // sendExpense already updates status to UP_FAILED with error message
            }
        }
    }
}

