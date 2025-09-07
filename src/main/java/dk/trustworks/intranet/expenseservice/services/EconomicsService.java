package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dto.ExpenseFile;
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
            String idempotencyKey = "expense-" + expense.getUuid();
            response = remoteApi.postVoucher(journal.getJournalNumber(), idempotencyKey, json);
        } catch (Exception e) {
            log.error("Failed to post voucher to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", voucher: " + voucher);
            throw e;
        }

        // extract voucher number from reponse
        if ((Objects.requireNonNull(response).getStatus() > 199) & (response.getStatus() < 300)) {
            ObjectMapper objectMapper = new ObjectMapper();
            String responseAsString = response.readEntity(String.class);
            JsonNode root = objectMapper.readValue(responseAsString, JsonNode.class);
            JsonNode first = root.isArray() ? (root.size() > 0 ? root.get(0) : null) : root;
            if (first == null || first.get("voucherNumber") == null) {
                log.error("Unexpected voucher POST response: " + responseAsString);
                return Response.status(502).entity("Unexpected voucher response").build();
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
            log.error("voucher not posted successfully to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", voucher: " + voucher + ", response: " + response);
            return response;
        }
    }

    public Response sendFile(Expense expense, ExpenseFile expensefile, Voucher voucher) throws IOException {
        log.info("Uploading file for expense " + expense.getUuid());

        final String fiscal = voucher.getAccountingYear().getYear();             // fx "2025/2026"
        final String urlYear = fiscal.contains("/") ? fiscal.replace("/", "_6_") : fiscal; // sti-format som i eksisterende kode

        byte[] bytes = ImageProcessor.convertBase64ToImageAndCompress(expensefile.getExpensefile());
        if (bytes == null || bytes.length == 0) return Response.status(400).entity("Empty attachment").build();
        if (bytes.length > 9 * 1024 * 1024) return Response.status(413).entity("Attachment too large (>9MB)").build();

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
            String idemp = "attach-" + expense.getUuid();

            if (!hasAttachment) {
                Response r = api.postExpenseFile(
                        voucher.getJournal().getJournalNumber(),
                        urlYear,
                        expense.getVouchernumber(),
                        idemp, // <-- ny parameter
                        form
                );
                if (r.getStatus() == 400) {
                    String body = safeRead(r);
                    if (body != null && body.contains("Voucher already has attachment")) {
                        // fallback til PATCH
                        return api.patchFile(
                                voucher.getJournal().getJournalNumber(),
                                urlYear,
                                expense.getVouchernumber(),
                                idemp, // <-- ny parameter
                                form
                        );
                    }
                }
                return r;
            } else {
                return api.patchFile(
                        voucher.getJournal().getJournalNumber(),
                        urlYear,
                        expense.getVouchernumber(),
                        idemp, // <-- ny parameter
                        form
                );
            }
        } catch (WebApplicationException wae) {
            return wae.getResponse(); // lad kalderen afgøre status
        } catch (Exception e) {
            log.error("Unexpected error when posting file", e);
            return Response.status(502).entity("Unexpected error during attachment upload").build();
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
}
