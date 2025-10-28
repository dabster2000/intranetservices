package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.exceptions.ExpenseUploadException;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPIAccount;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.*;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.ImageProcessor;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;

import static dk.trustworks.intranet.financeservice.model.IntegrationKey.getIntegrationKeyValue;

@JBossLog
@RequestScoped
public class  EconomicsService {

    @Inject
    UserService userService;

    public Response sendVoucher(Expense expense, ExpenseFile expensefile, UserAccount userAccount) throws IOException {
        log.info("Sending voucher for expense " + expense.getUuid());

        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);
        log.info("Voucher target = " + result);

        Journal journal = new Journal(result.expenseJournalNumber());
        String text = "Udlæg | " + userAccount.getUsername() + " | " + expense.getAccountname();

        if(getCompanyFromExpense(expense).getCvr().equals("44232855")) {
            expense.setAccount(String.valueOf(convertKontokode(Integer.parseInt(expense.getAccount()))));
        }

        Voucher voucher = buildJSONRequest(expense, userAccount, journal, text);

        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(voucher);
        // call e-conomics endpoint
        EconomicsAPI remoteApi = getEconomicsAPI(result);
        log.info("Voucher payload = " + json);
        Response response = null;
        try {
            // SMART IDEMPOTENCY STRATEGY:
            // Use deterministic key for normal operations to maintain true idempotency
            // Only modify key when we detect orphaned/cached responses from previous attempts
            String idempotencyKey;

            if (expense.hasKnownCacheIssue() || Boolean.TRUE.equals(expense.getIsOrphaned())) {
                // Known cache issue: Add retry count to force fresh processing
                // This bypasses stale cache while maintaining determinism
                idempotencyKey = String.format("expense-%s-retry-%d",
                    expense.getUuid(), expense.getSafeRetryCount());
                log.infof("Using retry idempotency key for orphaned/cached expense %s (retry %d)",
                    expense.getUuid(), expense.getSafeRetryCount());
            } else {
                // Normal case: Use deterministic key for true idempotency
                idempotencyKey = "expense-" + expense.getUuid();
                log.debugf("Using standard idempotency key for expense %s", expense.getUuid());
            }

            response = remoteApi.postVoucher(journal.getJournalNumber(), idempotencyKey, json);
        } catch (WebApplicationException e) {
            String errorDetails = safeRead(e.getResponse());
            log.error("Failed to post voucher to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", status: " + e.getResponse().getStatus() + ", details: " + errorDetails);
            throw new ExpenseUploadException("Failed to post voucher to e-conomics", e, e.getResponse().getStatus(), errorDetails);
        } catch (Exception e) {
            log.error("Failed to post voucher to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", voucher: " + voucher);
            throw new ExpenseUploadException("Failed to post voucher to e-conomics", e, null, e.getMessage());
        }

        // extract voucher number from reponse
        if ((Objects.requireNonNull(response).getStatus() > 199) & (response.getStatus() < 300)) {
            ObjectMapper objectMapper = new ObjectMapper();
            String responseAsString = response.readEntity(String.class);
            JsonNode root = objectMapper.readValue(responseAsString, JsonNode.class);
            JsonNode first = root.isArray() ? (root.size() > 0 ? root.get(0) : null) : root;
            if (first == null || first.get("voucherNumber") == null) {
                log.error("Unexpected voucher POST response: " + responseAsString);
                throw new ExpenseUploadException("Unexpected voucher response from e-conomics", null, 502, responseAsString);
            }
            int voucherNumber = first.get("voucherNumber").asInt();
            expense.setVouchernumber(voucherNumber);
            // persist accountingYear and journal too
            String fiscalYearName = voucher.getAccountingYear().getYear(); // e.g., 2024/2025
            String[] parts = fiscalYearName.split("/");
            String urlYear = parts.length == 2 ? parts[0] + "_6_" + parts[1] : fiscalYearName;
            expense.setAccountingyear(urlYear);
            expense.setJournalnumber(journal.getJournalNumber());

            //upload file to e-conomics voucher
            return sendFile(expense, expensefile, voucher);

        } else {
            String errorDetails = safeRead(response);
            log.error("voucher not posted successfully to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", status: " + response.getStatus() + ", details: " + errorDetails);
            throw new ExpenseUploadException("Voucher not posted successfully to e-conomics", null, response.getStatus(), errorDetails);
        }
    }

    public Response sendFile(Expense expense, ExpenseFile expensefile, Voucher voucher) throws IOException {
        log.info("Uploading file for expense " + expense.getUuid());

        // DEFENSIVE CHECK: Verify voucher actually exists before attempting file upload
        // This prevents attempting to upload files to non-existent vouchers
        // (can happen if e-conomics returned cached success but voucher wasn't actually created)
        if (expense.getVouchernumber() > 0 && !verifyVoucherExists(expense)) {
            log.errorf("Voucher %d doesn't exist in e-conomics for expense %s - orphaned reference detected",
                expense.getVouchernumber(), expense.getUuid());
            throw new ExpenseUploadException(
                "Orphaned voucher detected - voucher exists in cache but not in e-conomics",
                null,
                404,
                String.format("Voucher %d not found in journal %d for year %s",
                    expense.getVouchernumber(),
                    expense.getJournalnumber(),
                    expense.getAccountingyear())
            );
        }

        final String fiscal = voucher.getAccountingYear().getYear();             // fx "2025/2026" or "2025/2026a"
        // Strip any suffix letters (a, b, c, etc.) for URL consistency
        final String cleanFiscal = fiscal.replaceAll("[a-z]$", "");              // "2025/2026"
        final String urlYear = cleanFiscal.contains("/") ? cleanFiscal.replace("/", "_6_") : cleanFiscal; // sti-format som i eksisterende kode

        byte[] bytes = ImageProcessor.convertBase64ToImageAndCompress(expensefile.getExpensefile());
        if (bytes == null || bytes.length == 0) {
            throw new ExpenseUploadException("Empty attachment after image processing", null, 400, "Compressed image is empty");
        }
        if (bytes.length > 9 * 1024 * 1024) {
            throw new ExpenseUploadException("Attachment too large", null, 413, "File size: " + bytes.length + " bytes (max 9MB)");
        }

        MultipartFormDataOutput form = new MultipartFormDataOutput();
        form.addFormData("file", new ByteArrayInputStream(bytes), MediaType.APPLICATION_OCTET_STREAM_TYPE, "receipt.jpg");

        EconomicsAPI api = getApiForExpense(expense);

        // 1) Check om der allerede er en vedhæftning
        boolean hasAttachment = false;
        try {
            Response meta = api.getAttachment(voucher.getJournal().getJournalNumber(), urlYear, expense.getVouchernumber());
            if (meta.getStatus() / 100 == 2) {
                String json = meta.readEntity(String.class);
                hasAttachment = json.contains("\"pages\"") && !json.contains("\"pages\":0");
            }
        } catch (Exception ignore) { /* fortsæt defensivt */ }

        // 2) POST hvis ingen vedhæftning, ellers PATCH
        try {
            // Smart idempotency for file upload: Use operation type and retry count
            // This maintains determinism while allowing retries when needed
            String operation = !hasAttachment ? "POST" : "PATCH";
            String idemp = String.format("attach-%s-%s-v%d",
                expense.getUuid(), operation, expense.getSafeRetryCount());
            Response r;

            if (!hasAttachment) {
                r = api.postExpenseFile(
                        voucher.getJournal().getJournalNumber(),
                        urlYear,
                        expense.getVouchernumber(),
                        idemp,
                        form
                );
                if (r.getStatus() == 400) {
                    String body = safeRead(r);

                    // Check for idempotency key collision (URLChanged error)
                    if (body != null && body.contains("URLChanged")) {
                        log.warnf("Idempotency key conflict detected for expense %s - retrying with incremented version. Error: %s",
                                 expense.getUuid(), body);
                        // Increment retry count for new idempotency version
                        expense.incrementRetryCount();
                        String retryIdemp = String.format("attach-%s-POST-v%d",
                            expense.getUuid(), expense.getSafeRetryCount());
                        r = api.postExpenseFile(
                                voucher.getJournal().getJournalNumber(),
                                urlYear,
                                expense.getVouchernumber(),
                                retryIdemp,
                                form
                        );
                        log.info("Retry with new idempotency key completed, status: " + r.getStatus());
                    }
                    // Existing fallback for "Voucher already has attachment"
                    else if (body != null && body.contains("Voucher already has attachment")) {
                        log.infof("Voucher already has attachment, switching to PATCH for expense %s", expense.getUuid());
                        // fallback til PATCH with deterministic idempotency key
                        String patchIdemp = String.format("attach-%s-PATCH-v%d",
                            expense.getUuid(), expense.getSafeRetryCount());
                        r = api.patchFile(
                                voucher.getJournal().getJournalNumber(),
                                urlYear,
                                expense.getVouchernumber(),
                                patchIdemp,
                                form
                        );
                    }
                }
            } else {
                r = api.patchFile(
                        voucher.getJournal().getJournalNumber(),
                        urlYear,
                        expense.getVouchernumber(),
                        idemp,
                        form
                );
            }

            // Check if file upload was successful
            if (r.getStatus() < 200 || r.getStatus() >= 300) {
                String errorDetails = safeRead(r);
                log.error("File upload failed for expense " + expense.getUuid() + ", status: " + r.getStatus() + ", details: " + errorDetails);
                throw new ExpenseUploadException("File upload to e-conomics failed", null, r.getStatus(), errorDetails);
            }

            return r;
        } catch (WebApplicationException wae) {
            String errorDetails = safeRead(wae.getResponse());
            log.error("WebApplicationException during file upload for expense " + expense.getUuid() + ", status: " + wae.getResponse().getStatus() + ", details: " + errorDetails);
            throw new ExpenseUploadException("File upload to e-conomics failed", wae, wae.getResponse().getStatus(), errorDetails);
        } catch (ExpenseUploadException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error when posting file for expense " + expense.getUuid(), e);
            throw new ExpenseUploadException("Unexpected error during attachment upload", e, 502, e.getMessage());
        }
    }

    public Voucher buildJSONRequest(Expense expense, UserAccount userAccount, Journal journal, String text){

        ContraAccount contraAccount = new ContraAccount(userAccount.getEconomics());
        ExpenseAccount expenseaccount = new ExpenseAccount(Integer.parseInt(expense.getAccount()));
        Company company = getCompanyFromExpense(expense);
        String fiscalYearName = DateUtils.getFiscalYearName(DateUtils.getCurrentFiscalStartDate(), company.getUuid());
        AccountingYear accountingYear = new AccountingYear(fiscalYearName);
        log.debug("Using accounting year " + fiscalYearName + " for company " + company.getUuid());

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String date = dateFormat.format(new Date());

        FinanceVoucher financeVoucher1 = new FinanceVoucher(expenseaccount, text, expense.getAmount(), contraAccount, date);
        List<FinanceVoucher> financeVouchers = new ArrayList<>();
        financeVouchers.add(financeVoucher1);

        Entries entries = new Entries();
        Voucher voucher = new Voucher(accountingYear, journal, entries);

        entries.setFinanceVouchers(financeVouchers);
        voucher.setEntries(entries);

        return voucher;
    }

    public Boolean validateAccount(Expense expense) {
        int account = Integer.parseInt(expense.getAccount());
        try {
            //economicsAPIAccount.getAccount(account);
            return true;
        } catch (Exception e){
            return false;
        }
    }

    public String getAccount(String companyuuid, Integer account) throws IOException{
        // call e-conomics endpoint
        String response = null;
        EconomicsAPIAccount economicsAccountAPI = getEconomicsAccountAPI(getIntegrationKeyValue(Company.findById(companyuuid)));
        try (Response accountResponse = economicsAccountAPI.getAccount(account)) {
            response = accountResponse.readEntity(String.class);
        } catch (Exception e) {
            log.error("account = "+account);
            log.error(e.getMessage());
        }
        if(response==null) return "";
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(response);
        return jsonNode.get("name").asText();
    }

    private static EconomicsAPI getEconomicsAPI(IntegrationKey.IntegrationKeyValue result) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(result.url()))
                .register(new EconomicsDynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPI.class);
    }

    public EconomicsAPI getApiForExpense(Expense expense) {
        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);
        return getEconomicsAPI(result);
    }

    private static EconomicsAPIAccount getEconomicsAccountAPI(IntegrationKey.IntegrationKeyValue result) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(result.url()))
                .register(new EconomicsDynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPIAccount.class);
    }

    private IntegrationKey.IntegrationKeyValue getIntegrationKey(Expense expense) {
        Company company = getCompanyFromExpense(expense);

        return getIntegrationKeyValue(company);
    }

    private Company getCompanyFromExpense(Expense expense) {
        UserStatus userStatus = userService.findById(expense.getUseruuid(), false).getUserStatus(expense.getExpensedate());
        return userStatus.getCompany();
    }

    private static final Map<Integer, Integer> conversionMap = new HashMap<>();

    static {
        conversionMap.put(4003, 2800);
        conversionMap.put(4008, 2754);
        conversionMap.put(5218, 3604);
        conversionMap.put(5219, 3603);
        conversionMap.put(5214, 3605);
        conversionMap.put(4055, 2779);
        conversionMap.put(4030, 2770);
        conversionMap.put(4050, 2777);
        conversionMap.put(3560, 2245);
        conversionMap.put(3585, 2250);
        conversionMap.put(3591, 2260);
        conversionMap.put(4006, 2753);
        conversionMap.put(4020, 2720);
        conversionMap.put(5222, 3617);
        conversionMap.put(5233, 3600);
        conversionMap.put(5242, 3620);
        conversionMap.put(3575, 2258);
        conversionMap.put(5234, 2780);
        conversionMap.put(4007, 2781);
        conversionMap.put(4040, 2782);
        conversionMap.put(4042, 2783);
    }

    public static int convertKontokode(int kontokode) {
        // Check if the kontokode exists in the map and return the converted value.
        // If the kontokode does not exist in the map, return the input as is or handle as needed.
        return conversionMap.getOrDefault(kontokode, kontokode);
    }

    private static String safeRead(Response r) {
        if (r == null) return null;
        try {
            return r.readEntity(String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verifies that a voucher actually exists in e-conomics.
     * This is used to detect orphaned voucher references where our database
     * has a voucher number but the voucher doesn't exist in e-conomics.
     *
     * @param expense The expense with voucher details to verify
     * @return true if the voucher exists, false otherwise
     */
    public boolean verifyVoucherExists(Expense expense) {
        if (expense.getVouchernumber() <= 0 ||
            expense.getJournalnumber() == null ||
            expense.getAccountingyear() == null) {
            return false;
        }

        try {
            EconomicsAPI api = getApiForExpense(expense);
            // Use the same year format as in file upload
            String urlYear = expense.getAccountingyear();

            Response response = api.getVoucher(
                expense.getJournalnumber(),
                urlYear,
                expense.getVouchernumber()
            );

            boolean exists = response != null && response.getStatus() >= 200 && response.getStatus() < 300;

            if (!exists) {
                log.warnf("Voucher verification failed for expense %s: journal=%d, year=%s, voucher=%d, status=%s",
                    expense.getUuid(),
                    expense.getJournalnumber(),
                    urlYear,
                    expense.getVouchernumber(),
                    response != null ? String.valueOf(response.getStatus()) : "null");
            }

            if (response != null) {
                response.close();
            }

            return exists;
        } catch (Exception e) {
            log.error("Error verifying voucher existence for expense " + expense.getUuid(), e);
            return false;
        }
    }
}
