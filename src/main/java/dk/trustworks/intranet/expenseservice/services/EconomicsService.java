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
import dk.trustworks.intranet.financeservice.remote.DynamicHeaderFilter;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static dk.trustworks.intranet.financeservice.model.IntegrationKey.getIntegrationKeyValue;

@JBossLog
@RequestScoped
public class  EconomicsService {

    @Inject
    UserService userService;

    public Response sendVoucher(Expense expense, ExpenseFile expensefile, UserAccount userAccount) throws IOException {

        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);

        Journal journal = new Journal(result.expenseJournalNumber());
        String text = "Udlæg | " + userAccount.getUsername() + " | " + expense.getAccountname();

        Voucher voucher = buildJSONRequest(expense, userAccount, journal, text);

        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(voucher);
        // call e-conomics endpoint
        EconomicsAPI remoteApi = getEconomicsAPI(result);
        Response response =  remoteApi.postVoucher(journal.getJournalNumber(), json);

        // extract voucher number from reponse
        if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
            ObjectMapper objectMapper = new ObjectMapper();
            String responseAsString = response.readEntity(String.class);
            JsonNode array = objectMapper.readValue(responseAsString, JsonNode.class);
            JsonNode object = array.get(0);
            int voucherNumber = object.get("voucherNumber").intValue();
            expense.setVouchernumber(voucherNumber);

            //upload file to e-conomics voucher
            return sendFile(expense, expensefile, voucher);

        } else {
            log.error("voucher not posted successfully to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", voucher: " + voucher + ", response: " + response);
                return response;
        }
    }

    public Response sendFile(Expense expense, ExpenseFile expensefile, Voucher voucher) throws IOException {
        // format accountingYear to URL output
        String year = voucher.getAccountingYear().getYear();
        String[] arrOfStr = year.split("/", 2);
        String urlYear = arrOfStr[0]+ "_6_" +arrOfStr[1];

        // format file to outputstream as MultipartFormDataOutput
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        String imageString = expensefile.getExpensefile();
        outputStream.write(Base64.getDecoder().decode(imageString));

        byte [] finalByteArray = outputStream.toByteArray();

        InputStream targetStream = new ByteArrayInputStream(finalByteArray);

        MultipartFormDataOutput upload = new MultipartFormDataOutput();
        upload.addFormData("fileContent", targetStream, MediaType.APPLICATION_OCTET_STREAM_TYPE, "output.jpg");

        // call e-conomics endpoint
        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);
        EconomicsAPI remoteApi = getEconomicsAPI(result);
        Response fileResponse = remoteApi.postFile(voucher.getJournal().getJournalNumber(), urlYear, expense.getVouchernumber(), upload);
        if ((fileResponse.getStatus() < 200) || (fileResponse.getStatus() > 299)) {
            log.error("file not posted successfully to e-conomics. Expenseuuid: " + expense.getUseruuid() + ", voucher: " + expense.getVouchernumber() + ", response: " + fileResponse);
        }
        return fileResponse;
    }

    public Voucher buildJSONRequest(Expense expense, UserAccount userAccount, Journal journal, String text){

        ContraAccount contraAccount = new ContraAccount(userAccount.getAccount());
        ExpenseAccount expenseaccount = new ExpenseAccount(Integer.parseInt(expense.getAccount()));
        AccountingYear accountingYear = new AccountingYear(DateUtils.getCurrentFiscalStartDate().getYear()+"/"+DateUtils.getCurrentFiscalStartDate().plusYears(1).getYear());

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
                .register(new DynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPI.class);
    }

    private static EconomicsAPIAccount getEconomicsAccountAPI(IntegrationKey.IntegrationKeyValue result) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(result.url()))
                .register(new DynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPIAccount.class);
    }

    private IntegrationKey.IntegrationKeyValue getIntegrationKey(Expense expense) {
        UserStatus userStatus = userService.findById(expense.getUseruuid(), false).getUserStatus(LocalDate.now());
        Company company = userStatus.getCompany();

        return getIntegrationKeyValue(company);
    }
}
