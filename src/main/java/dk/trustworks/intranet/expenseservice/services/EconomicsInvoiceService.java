package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.*;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.DynamicHeaderFilter;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.utils.StringUtils;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@JBossLog
@RequestScoped
public class EconomicsInvoiceService {

    private IntegrationKey.IntegrationKeyValue integrationKeyValue;

    public Response sendVoucher(Invoice invoice) throws IOException {
        log.info("EconomicsInvoiceService.sendVoucher");
        log.info("Sending invoice number " + invoice.invoicenumber);
        integrationKeyValue = IntegrationKey.getIntegrationKeyValue(invoice.getCompany());
        log.info("integrationKeyValue = " + integrationKeyValue);

        Journal journal = new Journal(integrationKeyValue.invoiceJournalNumber());
        String text = invoice.getClientname() + ", Faktura " + StringUtils.convertInvoiceNumberToString(invoice.getInvoicenumber());

        Voucher voucher = buildJSONRequest(invoice, journal, text, integrationKeyValue);
        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(voucher);
        log.info("json = " + json);

        // call e-conomics endpoint
        try {
            EconomicsAPI economicsAPI = getEconomicsAPI(integrationKeyValue);
            Response response = economicsAPI.postVoucher(journal.getJournalNumber(), json);

            // extract voucher number from reponse
            if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
                ObjectMapper objectMapper = new ObjectMapper();
                String responseAsString = response.readEntity(String.class);
                JsonNode array = objectMapper.readValue(responseAsString, JsonNode.class);
                JsonNode object = array.get(0);
                int voucherNumber = object.get("voucherNumber").intValue();
                //expense.setVouchernumber(voucherNumber);

                //upload file to e-conomics voucher
                log.info("voucher posted successfully to e-conomics. Invoiceuuid: " + invoice.getUuid() + ", voucher: " + voucher + ", voucherNumber: " + voucherNumber);
                return sendFile(invoice, voucher, voucherNumber);
            } else {
                log.error("voucher not posted successfully to e-conomics. Invoiceuuid: " + invoice.getUuid() + ", voucher: " + voucher + ", response: " + response);
                return response;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public Response sendFile(Invoice invoice, Voucher voucher, int voucherNumber) throws IOException {
        log.info("EconomicsInvoiceService.sendFile");
        log.info("invoice = " + invoice + ", voucher = " + voucher + ", voucherNumber = " + voucherNumber);
        // format accountingYear to URL output
        String year = voucher.getAccountingYear().getYear();
        String[] arrOfStr = year.split("/", 2);
        String urlYear = arrOfStr[0]+ "_6_" +arrOfStr[1];

        // format file to outputstream as MultipartFormDataOutput
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        outputStream.write(invoice.getPdf());

        byte [] finalByteArray = outputStream.toByteArray();

        InputStream targetStream = new ByteArrayInputStream(finalByteArray);

        MultipartFormDataOutput upload = new MultipartFormDataOutput();
        upload.addFormData("fileContent", targetStream, MediaType.APPLICATION_OCTET_STREAM_TYPE, "invoice.pdf");

        // call e-conomics endpoint
        Response fileResponse;
        try(EconomicsAPI economicsAPIFile = getEconomicsAPI(integrationKeyValue)) {
             fileResponse = economicsAPIFile.postFile(voucher.getJournal().getJournalNumber(), urlYear, voucherNumber, upload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println("fileResponse = " + fileResponse);
        if ((fileResponse.getStatus() < 200) || (fileResponse.getStatus() > 299)) {
            log.error("file not posted successfully to e-conomics. Expenseuuid: " + invoice.getUuid() + ", voucher: " + voucher.getJournal().getJournalNumber() + ", response: " + fileResponse);
        }
        return fileResponse;
    }

    public Voucher buildJSONRequest(Invoice invoice, Journal journal, String text, IntegrationKey.IntegrationKeyValue integrationKeyValue){
        System.out.println("EconomicsInvoiceService.buildJSONRequest");
        ContraAccount contraAccount = new ContraAccount(integrationKeyValue.invoiceAccountNumber());
        System.out.println("contraAccount = " + contraAccount.getAccountNumber());
        ExpenseAccount account = new ExpenseAccount(integrationKeyValue.invoiceAccountNumber());
        System.out.println("account = " + account.getAccountNumber());
        String s = DateUtils.getFiscalStartDateBasedOnDate(invoice.getInvoicedate()).getYear() + "/" + DateUtils.getFiscalStartDateBasedOnDate(invoice.getInvoicedate()).plusYears(1).getYear();
        System.out.println("s = " + s);
        AccountingYear accountingYear = new AccountingYear(s);//new AccountingYear(DateUtils.getCurrentFiscalStartDate().getYear()+"/"+DateUtils.getCurrentFiscalStartDate().plusYears(1).getYear());
        System.out.println("accountingYear = " + accountingYear.getYear());

        String date = DateUtils.stringIt(invoice.getInvoicedate());

        System.out.println("Creating manual customer invoice");
        ManualCustomerInvoice manualCustomerInvoice = new ManualCustomerInvoice(account, invoice.getInvoicenumber(), text, invoice.getSumWithTax(), contraAccount, date);
        System.out.println("manualCustomerInvoice = " + manualCustomerInvoice.text);
        List<ManualCustomerInvoice> manualCustomerInvoices = new ArrayList<>();
        manualCustomerInvoices.add(manualCustomerInvoice);

        Entries entries = new Entries();
        Voucher voucher = new Voucher(accountingYear, journal, entries);

        entries.setManualCustomerInvoices(manualCustomerInvoices);
        voucher.setEntries(entries);

        return voucher;
    }


    private static EconomicsAPI getEconomicsAPI(IntegrationKey.IntegrationKeyValue result) {
        log.info("EconomicsInvoiceService.getEconomicsAPI");
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(result.url()))
                .register(new DynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPI.class);
    }

}
