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
import dk.trustworks.intranet.expenseservice.remote.EconomicsJournalsAPI;
import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
import dk.trustworks.intranet.expenseservice.remote.DraftEntryDeleteRequest;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static dk.trustworks.intranet.financeservice.model.IntegrationKey.getIntegrationKeyValue;

@JBossLog
@RequestScoped
public class  EconomicsService {

    @Inject
    UserService userService;

    /**
     * Environment prefix on the idempotency key — prevents the same expense UUID
     * from colliding across environments at e-conomics' idempotency cache, which
     * would otherwise let staging block production's POST with HTTP 400 URLChanged.
     */
    @ConfigProperty(name = "dk.trustworks.environment.id", defaultValue = "production")
    String environmentId;

    /**
     * Cap on closed-period auto-shift retries. Covers the realistic edge cases (period
     * boundary race, year-end roll-over) without risking runaway loops.
     */
    private static final int MAX_PERIOD_SHIFT_DAYS = 7;

    /** Builds the e-conomics Idempotency-Key header value for a voucher POST. */
    String buildIdempotencyKey(Expense expense) {
        if (expense.hasKnownCacheIssue() || Boolean.TRUE.equals(expense.getIsOrphaned())) {
            return String.format("%s-expense-%s-retry-%d",
                    environmentId, expense.getUuid(), expense.getSafeRetryCount());
        }
        return environmentId + "-expense-" + expense.getUuid();
    }

    /**
     * Idempotency key used when retrying after a closed-period rejection. Distinct from
     * the standard and orphan keys so e-conomics' cache treats the shifted-date POST as a
     * fresh request.
     */
    String buildPeriodShiftIdempotencyKey(Expense expense, int shiftDays) {
        return String.format("%s-expense-%s-period-shift-%d",
                environmentId, expense.getUuid(), shiftDays);
    }

    /**
     * Detects e-conomic error responses indicating the voucher's entry date falls in a
     * closed accounting period. e-conomic uses errorCode names in its problem-detail
     * body; we match the known variants seen in production / documented in the API.
     */
    boolean isPeriodClosedError(String body) {
        if (body == null) return false;
        return body.contains("AccountingYearClosed")
                || body.contains("EntryDateInClosedPeriod")
                || body.contains("DateInClosedPeriod")
                || body.contains("PeriodClosed")
                || body.contains("ClosedAccountingYear");
    }

    public Response sendVoucher(Expense expense, ExpenseFile expensefile, UserAccount userAccount) throws Exception {
        log.info("Sending voucher for expense " + expense.getUuid());

        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);
        log.info("Voucher target = " + result);

        Journal journal = new Journal(result.expenseJournalNumber());
        String text = "Udlæg | " + userAccount.getUsername() + " | " + expense.getAccountname();
        Company company = getCompanyFromExpense(expense);

        if("44232855".equals(company.getCvr())) {
            expense.setAccount(String.valueOf(convertKontokode(Integer.parseInt(expense.getAccount()))));
        }
        String defaultVatCode = resolveDefaultVatCode(result, Integer.parseInt(expense.getAccount()));

        try (EconomicsAPI remoteApi = getEconomicsAPI(result)) {
            Voucher voucher = null;
            Response response = null;
            String lastBody = null;
            int lastStatus = 0;

            // Period-closed auto-shift: each retry advances the voucher entry date by 1 day
            // and uses a fresh idempotency key so e-conomic's cache treats it as new.
            for (int shift = 0; shift <= MAX_PERIOD_SHIFT_DAYS; shift++) {
                LocalDate voucherDate = LocalDate.now().plusDays(shift);
                voucher = buildJSONRequestWithDate(expense, userAccount, journal, text, voucherDate, defaultVatCode);
                String json = new ObjectMapper().writeValueAsString(voucher);
                String idempotencyKey = (shift == 0)
                        ? buildIdempotencyKey(expense)
                        : buildPeriodShiftIdempotencyKey(expense, shift);

                if (shift == 0) {
                    log.debugf("Posting voucher for expense %s with idempotency key %s", expense.getUuid(), idempotencyKey);
                    log.info("Voucher payload = " + json);
                } else {
                    log.warnf("Closed period on previous attempt — retrying expense %s with voucher date %s (shift +%d days, key %s)",
                            expense.getUuid(), voucherDate, shift, idempotencyKey);
                }

                try {
                    response = remoteApi.postVoucher(journal.getJournalNumber(), idempotencyKey, json);
                } catch (WebApplicationException e) {
                    String errorDetails = safeRead(e.getResponse());
                    log.error("Failed to post voucher to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", status: " + e.getResponse().getStatus() + ", details: " + errorDetails);
                    throw new ExpenseUploadException("Failed to post voucher to e-conomics", e, e.getResponse().getStatus(), errorDetails);
                } catch (Exception e) {
                    log.error("Failed to post voucher to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", voucher: " + voucher);
                    throw new ExpenseUploadException("Failed to post voucher to e-conomics", e, null, e.getMessage());
                }

                int status = response.getStatus();
                if (status >= 200 && status < 300) break; // success — fall through to response parsing
                lastStatus = status;
                lastBody = safeRead(response);
                try { response.close(); } catch (Exception ignore) {}
                response = null;
                if (status != 400 || !isPeriodClosedError(lastBody)) {
                    log.error("voucher not posted successfully to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", status: " + status + ", details: " + lastBody);
                    throw new ExpenseUploadException("Voucher not posted successfully to e-conomics", null, status, lastBody);
                }
            }
            if (response == null) {
                log.error("Voucher post failed after " + (MAX_PERIOD_SHIFT_DAYS + 1) + " period-shift attempts. Expenseuuid: " + expense.getUseruuid() + ", lastStatus: " + lastStatus + ", lastBody: " + lastBody);
                throw new ExpenseUploadException(
                        "Voucher post failed: closed period persists after " + MAX_PERIOD_SHIFT_DAYS + " day-shift retries",
                        null, lastStatus, lastBody);
            }

            try (Response voucherResponse = response) {
                ObjectMapper objectMapper = new ObjectMapper();
                String responseAsString = voucherResponse.readEntity(String.class);
                JsonNode root = objectMapper.readValue(responseAsString, JsonNode.class);
                JsonNode first = root.isArray() ? (!root.isEmpty() ? root.get(0) : null) : root;
                if (first == null || first.get("voucherNumber") == null) {
                    log.error("Unexpected voucher POST response: " + responseAsString);
                    throw new ExpenseUploadException("Unexpected voucher response from e-conomics", null, 502, responseAsString);
                }
                int voucherNumber = first.get("voucherNumber").asInt();
                expense.setVouchernumber(voucherNumber);
                // persist accountingYear (canonical URL form without any trailing letters) and journal too
                String fiscalYearName = voucher.getAccountingYear().getYear(); // e.g., 2025/2026 or 2025/2026a
                String urlYear = DateUtils.toEconomicsUrlYear(fiscalYearName);
                log.debugf("Storing accounting year for expense %s: fiscalYear=%s -> storedFormat=%s",
                    expense.getUuid(), fiscalYearName, urlYear);
                expense.setAccountingyear(urlYear);
                expense.setJournalnumber(journal.getJournalNumber());

                //upload file to e-conomics voucher
                return sendFile(expense, expensefile, voucher);
            }
        }
    }

    public Response sendFile(Expense expense, ExpenseFile expensefile, Voucher voucher) throws Exception {
        log.info("Uploading file for expense " + expense.getUuid());

        // DEFENSIVE CHECK: Verify voucher actually exists before attempting file upload
        // This prevents attempting to upload files to non-existent vouchers
        // (can happen if e-conomics returned cached success but voucher wasn't actually created)
        if (expense.getVouchernumber() > 0 && !verifyVoucherExists(expense)) {
            String storedYear = expense.getAccountingyear();
            // Show the actual URL format used in verification (underscore format)
            String urlYear = DateUtils.toEconomicsUrlYear(storedYear);
            log.errorf("Voucher %d doesn't exist in e-conomics for expense %s - orphaned reference detected (storedYear=%s, urlYear=%s)",
                expense.getVouchernumber(), expense.getUuid(), storedYear, urlYear);
            throw new ExpenseUploadException(
                "Orphaned voucher detected - voucher exists in cache but not in e-conomics",
                null,
                404,
                String.format("Voucher %d not found in journal %d for year %s (urlYear=%s)",
                    expense.getVouchernumber(),
                    expense.getJournalnumber(),
                    storedYear,
                    urlYear)
            );
        }

        // Convert to URL format (underscore format) for API path parameters - same as invoice code
        String originalYear = voucher.getAccountingYear().getYear();
        final String urlYear = DateUtils.toEconomicsUrlYear(originalYear);
        log.debugf("File upload for expense %s: originalYear=%s -> urlYear=%s, voucher=%d",
            expense.getUuid(), originalYear, urlYear, expense.getVouchernumber());

        byte[] bytes = ImageProcessor.convertBase64ToImageAndCompress(expensefile.getExpensefile());
        if (bytes == null || bytes.length == 0) {
            throw new ExpenseUploadException("Empty attachment after image processing", null, 400, "Compressed image is empty");
        }
        if (bytes.length > 9 * 1024 * 1024) {
            throw new ExpenseUploadException("Attachment too large", null, 413, "File size: " + bytes.length + " bytes (max 9MB)");
        }

        MultipartFormDataOutput form = new MultipartFormDataOutput();
        form.addFormData("file", new ByteArrayInputStream(bytes), MediaType.valueOf("image/jpeg"), "receipt.jpg");

        try (EconomicsAPI api = getApiForExpense(expense)) {
            // 1) Check om der allerede er en vedhæftning
            boolean hasAttachment = false;
            try (Response meta = api.getAttachment(voucher.getJournal().getJournalNumber(), urlYear, expense.getVouchernumber())) {
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
                    log.debug("POST file attachment: /journals/" + voucher.getJournal().getJournalNumber() +
                        "/vouchers/" + urlYear + "-" + expense.getVouchernumber() + "/attachment/file");
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
                            // Close the failed response before retrying
                            try { r.close(); } catch (Exception ignore) {}
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
                            // Close the failed response before retrying
                            try { r.close(); } catch (Exception ignore) {}
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
                    log.debug("PATCH file attachment: /journals/" + voucher.getJournal().getJournalNumber() +
                        "/vouchers/" + urlYear + "-" + expense.getVouchernumber() + "/attachment/file");
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
    }

    public Voucher buildJSONRequest(Expense expense, UserAccount userAccount, Journal journal, String text){
        return buildJSONRequestWithDate(expense, userAccount, journal, text, LocalDate.now());
    }

    /**
     * Builds the voucher JSON payload with an explicit entry date. The accounting year
     * is derived from this date (not today's date) so closed-period auto-shifts that
     * cross the fiscal year boundary land in the correct year.
     */
    public Voucher buildJSONRequestWithDate(Expense expense, UserAccount userAccount, Journal journal, String text, LocalDate voucherDate){
        return buildJSONRequestWithDate(expense, userAccount, journal, text, voucherDate, null);
    }

    public Voucher buildJSONRequestWithDate(Expense expense, UserAccount userAccount, Journal journal, String text, LocalDate voucherDate, String vatCode){
        Company company = getCompanyFromExpense(expense);
        String fiscalYearName = DateUtils.getFiscalYearName(DateUtils.fiscalYearStart(voucherDate), company.getUuid());
        AccountingYear accountingYear = new AccountingYear(fiscalYearName);
        log.debug("Using accounting year " + fiscalYearName + " for company " + company.getUuid());

        String date = voucherDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        FinanceVoucher financeVoucher1 = buildFinanceVoucher(expense, userAccount, text, date, vatCode);
        List<FinanceVoucher> financeVouchers = new ArrayList<>();
        financeVouchers.add(financeVoucher1);

        Entries entries = new Entries();
        Voucher voucher = new Voucher(accountingYear, journal, entries);

        entries.setFinanceVouchers(financeVouchers);
        voucher.setEntries(entries);

        return voucher;
    }

    FinanceVoucher buildFinanceVoucher(Expense expense, UserAccount userAccount, String text, String date, String vatCode) {
        ContraAccount contraAccount = new ContraAccount(userAccount.getEconomics());
        ExpenseAccount expenseaccount = new ExpenseAccount(Integer.parseInt(expense.getAccount()));
        FinanceVoucher financeVoucher = new FinanceVoucher(expenseaccount, text, expense.getAmount(), contraAccount, date);
        if (vatCode != null && !vatCode.isBlank()) {
            financeVoucher.setVatAccount(new VatAccount(vatCode.trim()));
        }
        return financeVoucher;
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

    String resolveDefaultVatCode(IntegrationKey.IntegrationKeyValue result, int accountNumber) {
        try (EconomicsAPIAccount economicsAccountAPI = getEconomicsAccountAPI(result)) {
            try (Response accountResponse = economicsAccountAPI.getAccount(accountNumber)) {
                int status = accountResponse.getStatus();
                if (status < 200 || status >= 300) {
                    String body = safeRead(accountResponse);
                    log.warnf("Could not resolve e-conomic VAT default for account %d: status=%d, body=%s. Posting without VAT.",
                            accountNumber, status, body);
                    return null;
                }

                String response = accountResponse.readEntity(String.class);
                Optional<String> vatCode = extractDefaultVatCode(response);
                if (vatCode.isEmpty()) {
                    log.warnf("No e-conomic VAT default returned for account %d. Posting without VAT.", accountNumber);
                    return null;
                }
                return vatCode.get();
            }
        } catch (Exception e) {
            log.warnf(e, "Could not resolve e-conomic VAT default for account %d. Posting without VAT.", accountNumber);
            return null;
        }
    }

    static Optional<String> extractDefaultVatCode(String accountJson) {
        if (accountJson == null || accountJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode vatCodeNode = new ObjectMapper()
                    .readTree(accountJson)
                    .path("vatAccount")
                    .path("vatCode");
            if (vatCodeNode.isMissingNode() || vatCodeNode.isNull()) {
                return Optional.empty();
            }
            String vatCode = vatCodeNode.asText(null);
            if (vatCode == null || vatCode.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(vatCode.trim());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public String getAccount(String companyuuid, Integer account) throws Exception {
        // call e-conomics endpoint with proper resource management
        String response = null;
        try (EconomicsAPIAccount economicsAccountAPI = getEconomicsAccountAPI(getIntegrationKeyValue(Company.findById(companyuuid)))) {
            try (Response accountResponse = economicsAccountAPI.getAccount(account)) {
                response = accountResponse.readEntity(String.class);
            } catch (Exception e) {
                log.error("account = "+account);
                log.error(e.getMessage());
            }
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

    /**
     * Create Journals API client (NEW API for draft entry deletion).
     * Base URL: https://apis.e-conomic.com/journalsapi/v13.0.1
     */
    private static EconomicsJournalsAPI getJournalsAPI(IntegrationKey.IntegrationKeyValue result) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create("https://apis.e-conomic.com/journalsapi/v13.0.1"))
                .register(new EconomicsDynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsJournalsAPI.class);
    }

    /**
     * Get entry details from journal to obtain entryNumber and objectVersion.
     * Required for deleting draft entries via NEW Journals API.
     *
     * @param expense Expense with voucher reference
     * @param integrationKey Integration keys for authentication
     * @return Entry details or null if not found
     */
    private JournalEntryResponse.Entry getEntryDetails(Expense expense, IntegrationKey.IntegrationKeyValue integrationKey) throws Exception {
        try (EconomicsJournalsAPI journalsAPI = getJournalsAPI(integrationKey)) {
            // Filter must include BOTH journalNumber AND voucherNumber for NEW Journals API
            String filter = String.format("journalNumber$eq:%d$and:voucherNumber$eq:%d",
                expense.getJournalnumber(), expense.getVouchernumber());
            log.infof("Fetching draft entries with filter: %s", filter);

            JournalEntryResponse response = journalsAPI.getDraftEntries(
                filter,
                null,  // cursor (optional, for pagination)
                1000   // pagesize
            );

            if (response.collection == null || response.collection.isEmpty()) {
                log.warnf("No draft entries found for filter: %s", filter);
                return null;
            }

            // Return first matching entry (expenses typically have one entry per voucher)
            JournalEntryResponse.Entry entry = response.collection.get(0);
            log.infof("Found draft entry: entryNumber=%d, voucherNumber=%d, objectVersion=%s",
                entry.entryNumber, entry.voucherNumber, entry.objectVersion);
            return entry;
        }
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

        try (EconomicsAPI api = getApiForExpense(expense)) {
            // Convert stored year to e-conomics URL format (underscore format)
            String storedYear = expense.getAccountingyear();
            String urlYear = DateUtils.toEconomicsUrlYear(storedYear);

            log.debugf("Verifying voucher existence for expense %s: journal=%d, storedYear=%s -> urlYear=%s, voucher=%d",
                expense.getUuid(), expense.getJournalnumber(), storedYear, urlYear, expense.getVouchernumber());

            // Retry a few times in case of eventual consistency right after creation
            int attempts = 3;
            int lastStatus = 0;
            for (int i = 0; i < attempts; i++) {
                log.debugf("Attempt %d: GET /journals/%d/vouchers/%s-%d",
                    i + 1, expense.getJournalnumber(), urlYear, expense.getVouchernumber());

                Response response = api.getVoucher(
                        expense.getJournalnumber(),
                        urlYear,
                        expense.getVouchernumber()
                );
                int status = response != null ? response.getStatus() : 0;
                lastStatus = status;

                log.debugf("Voucher verification response: status=%d for voucher %d", status, expense.getVouchernumber());

                if (response != null) {
                    try { response.close(); } catch (Exception ignore) {}
                }

                if (status >= 200 && status < 300) {
                    log.debugf("Voucher %d exists (status %d)", expense.getVouchernumber(), status);
                    return true;
                }
                if (status == 404) {
                    log.debugf("Voucher %d not found (404), retrying after delay...", expense.getVouchernumber());
                    // Wait briefly and try again to allow e-conomic to persist the voucher
                    try { Thread.sleep(1500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }

                // Unexpected status -> stop retrying
                log.warnf("Voucher verification unexpected status for expense %s: journal=%d, year=%s, voucher=%d, status=%d",
                        expense.getUuid(), expense.getJournalnumber(), urlYear, expense.getVouchernumber(), status);
                return false;
            }

            // After retries, still not found -> treat as orphan
            if (lastStatus == 404) return false;
            return false;
        } catch (RuntimeException e) {
            // If a provider still mapped a 404 to exception, treat as not found
            String msg = e.getMessage();
            if (msg != null && (msg.contains("httpStatusCode\":404") || msg.contains("HTTP 404"))) {
                return false;
            }
            log.error("Error verifying voucher existence for expense " + expense.getUuid(), e);
            return false;
        } catch (Exception e) {
            log.error("Error verifying voucher existence for expense " + expense.getUuid(), e);
            return false;
        }
    }

    /**
     * Delete voucher from e-conomic using NEW Journals API.
     * <p>
     * Uses GET-then-DELETE pattern: first fetches entry details to obtain
     * entryNumber and objectVersion, then deletes the draft entry.
     * </p>
     * <p>
     * This method deletes an unbooked voucher from the e-conomic journal.
     * Vouchers that have been booked to the accounting year cannot be deleted (HTTP 409).
     * If the voucher is not found (HTTP 404), it's treated as already deleted (auto-reconcile).
     * </p>
     *
     * @param expense Expense with voucher reference to delete
     * @return Response from DELETE operation (HTTP 204 on success, HTTP 404 if not found)
     * @throws Exception if deletion fails or voucher is booked
     */
    public Response deleteVoucher(Expense expense) throws Exception {
        log.info("EconomicsService.deleteVoucher");
        log.infof("Deleting voucher for expense %s: journal=%d, voucher=%d, accountingYear=%s",
                expense.getUuid(), expense.getJournalnumber(), expense.getVouchernumber(), expense.getAccountingyear());

        // Validate voucher references exist
        if (expense.getVouchernumber() <= 0 ||
            expense.getJournalnumber() == null ||
            expense.getAccountingyear() == null) {
            log.warnf("Cannot delete voucher for expense %s: missing voucher references", expense.getUuid());
            throw new IllegalArgumentException("Expense has no voucher reference to delete");
        }

        // Get integration keys for expense company
        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);
        log.info("integrationKeyValue = " + result);

        try {
            // STEP 1: GET entry details (to obtain entryNumber and objectVersion)
            log.infof("Step 1: Fetching entry details for voucher %d in journal %d",
                expense.getVouchernumber(), expense.getJournalnumber());

            JournalEntryResponse.Entry entry = getEntryDetails(expense, result);

            if (entry == null) {
                // Voucher not found - treat as already deleted (auto-reconcile)
                log.warnf("Voucher not found in e-conomic (404) - may have been manually deleted: journal=%d, voucher=%d",
                        expense.getJournalnumber(), expense.getVouchernumber());
                return Response.status(404).build();
            }

            // STEP 2: DELETE draft entry using details from GET
            log.infof("Step 2: Deleting draft entry %d for voucher %d", entry.entryNumber, entry.voucherNumber);

            DraftEntryDeleteRequest deleteRequest = new DraftEntryDeleteRequest(
                expense.getJournalnumber(),
                expense.getVouchernumber(),
                entry.entryNumber,
                entry.objectVersion
            );

            try (EconomicsJournalsAPI journalsAPI = getJournalsAPI(result)) {
                Response response = journalsAPI.deleteDraftEntry(deleteRequest);

                int status = response.getStatus();
                log.infof("DELETE draft entry response: status=%d for expense %s", status, expense.getUuid());

                // Success: 204 No Content or other 2xx
                if (status == 204 || (status >= 200 && status < 300)) {
                    log.infof("Draft entry deleted successfully in e-conomic: journal=%d, voucher=%d, entry=%d",
                            expense.getJournalnumber(), expense.getVouchernumber(), entry.entryNumber);
                    return response;
                }

                // 409: Conflict - voucher may be booked
                if (status == 409) {
                    String errorBody = safeRead(response);
                    log.errorf("Cannot delete booked voucher (409): journal=%d, voucher=%d, error: %s",
                            expense.getJournalnumber(), expense.getVouchernumber(), errorBody);
                    throw new ExpenseUploadException("Cannot delete booked voucher", null, status, errorBody);
                }

                // Other errors
                String errorBody = safeRead(response);
                log.errorf("Failed to delete draft entry: status=%d, journal=%d, voucher=%d, entry=%d, error: %s",
                        status, expense.getJournalnumber(), expense.getVouchernumber(), entry.entryNumber, errorBody);
                throw new ExpenseUploadException("Failed to delete draft entry from e-conomic", null, status, errorBody);
            }

        } catch (ExpenseUploadException e) {
            // Re-throw our own exceptions
            throw e;
        } catch (Exception e) {
            // Check if it's a 404 from GET (voucher not found) - auto-reconcile
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("404")) {
                log.warnf("Voucher not found in e-conomic (404) - auto-reconciling for expense %s", expense.getUuid());
                return Response.status(404).build();
            }

            // Other errors - rethrow
            log.errorf(e, "Failed to delete voucher from e-conomic for expense %s", expense.getUuid());
            throw new RuntimeException("Failed to delete voucher from e-conomic", e);
        }
    }
}
