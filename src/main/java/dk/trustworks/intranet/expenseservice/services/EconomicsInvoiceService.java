package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPIFile;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.*;
import dk.trustworks.intranet.invoiceservice.model.Invoice;
import dk.trustworks.intranet.invoiceservice.utils.StringUtils;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@JBossLog
@RequestScoped
public class EconomicsInvoiceService {

    @Inject
    @RestClient
    EconomicsAPI economicsAPI;

    @Inject
    @RestClient
    EconomicsAPIFile economicsAPIFile;

    @ConfigProperty(name = "e-conomics.journal-number")
    int journalNumber;

    public Response sendVoucher(Invoice invoice) throws IOException {

        Journal journal = new Journal(journalNumber);
        String text = invoice.getClientname() + ", Faktura " + StringUtils.convertInvoiceNumberToString(invoice.getInvoicenumber());

        Voucher voucher = buildJSONRequest(invoice, journal, text);
        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(voucher);
        System.out.println("json = " + json);

        // call e-conomics endpoint
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
            return sendFile(invoice, voucher, voucherNumber);
        } else {
            log.error("voucher not posted successfully to e-conomics. Invoiceuuid: " + invoice.getUuid() + ", voucher: " + voucher + ", response: " + response);
                return response;
        }
    }

    public Response sendFile(Invoice invoice, Voucher voucher, int voucherNumber) throws IOException {
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
        Response fileResponse = economicsAPIFile.postFile(voucher.getJournal().getJournalNumber(), urlYear, voucherNumber, upload);
        if ((fileResponse.getStatus() < 200) || (fileResponse.getStatus() > 299)) {
            log.error("file not posted successfully to e-conomics. Expenseuuid: " + invoice.getUuid() + ", voucher: " + voucher.getJournal().getJournalNumber() + ", response: " + fileResponse);
        }
        return fileResponse;
    }

    public Voucher buildJSONRequest(Invoice invoice, Journal journal, String text){

        ContraAccount contraAccount = new ContraAccount(invoice.getInvoicenumber()==0?2102:2101);
        ExpenseAccount account = new ExpenseAccount(invoice.getInvoicenumber()==0?2102:2101);
        String s = DateUtils.getFiscalStartDateBasedOnDate(invoice.getInvoicedate()).getYear() + "/" + DateUtils.getFiscalStartDateBasedOnDate(invoice.getInvoicedate()).plusYears(1).getYear();
        AccountingYear accountingYear = new AccountingYear(s);//new AccountingYear(DateUtils.getCurrentFiscalStartDate().getYear()+"/"+DateUtils.getCurrentFiscalStartDate().plusYears(1).getYear());

        String date = DateUtils.stringIt(invoice.getInvoicedate());

        ManualCustomerInvoice manualCustomerInvoice = new ManualCustomerInvoice(account, invoice.getInvoicenumber(), text, invoice.getSumWithTax(), contraAccount, date);
        List<ManualCustomerInvoice> manualCustomerInvoices = new ArrayList<>();
        manualCustomerInvoices.add(manualCustomerInvoice);

        Entries entries = new Entries();
        Voucher voucher = new Voucher(accountingYear, journal, entries);

        entries.setManualCustomerInvoices(manualCustomerInvoices);
        voucher.setEntries(entries);

        return voucher;
    }

}
