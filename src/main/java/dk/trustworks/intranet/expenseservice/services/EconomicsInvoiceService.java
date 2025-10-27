package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.*;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.utils.StringUtils;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import jakarta.enterprise.context.ApplicationScoped;
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
@ApplicationScoped
public class EconomicsInvoiceService {

    private IntegrationKey.IntegrationKeyValue integrationKeyValue;

    public Response sendVoucher(Invoice invoice) throws IOException {
        log.info("EconomicsInvoiceService.sendVoucher");
        log.info("Sending invoice number " + invoice.invoicenumber);
        integrationKeyValue = IntegrationKey.getIntegrationKeyValue(invoice.getCompany());
        log.info("integrationKeyValue = " + integrationKeyValue);

        Journal journal = new Journal(integrationKeyValue.invoiceJournalNumber());
        String text = invoice.getClientname() + ", Faktura " + StringUtils.convertInvoiceNumberToString(invoice.getInvoicenumber());

        Voucher voucher = buildJSONRequest(invoice, journal, text, integrationKeyValue, invoice.getCompany());
        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(voucher);
        log.info("json = " + json);

        // call e-conomics endpoint
        try {
            EconomicsAPI economicsAPI = getEconomicsAPI(integrationKeyValue);
            String idem = "invoice-" + invoice.getUuid();
            Response response = economicsAPI.postVoucher(journal.getJournalNumber(), idem, json);

            // extract voucher number from reponse
            if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
                ObjectMapper objectMapper = new ObjectMapper();
                String responseAsString = response.readEntity(String.class);
                JsonNode array = objectMapper.readValue(responseAsString, JsonNode.class);
                JsonNode object = array.get(0);
                int voucherNumber = object.get("voucherNumber").intValue();
                // Persist voucher number on invoice entity; actual DB write will occur in caller @Transactional
                invoice.setEconomicsVoucherNumber(voucherNumber);
                Invoice.update("economicsVoucherNumber = ?1 WHERE uuid like ?2", voucherNumber, invoice.getUuid());
                log.info("Saved e-conomics voucherNumber=" + voucherNumber + " on invoice " + invoice.getUuid());

                //upload file to e-conomics voucher
                log.info("voucher posted successfully to e-conomics. Invoiceuuid: " + invoice.getUuid() + ", voucher: " + voucher + ", voucherNumber: " + voucherNumber);
                return sendFile(invoice, voucher, voucherNumber, integrationKeyValue);
            } else {
                String errorBody = response.readEntity(String.class);
                log.error("voucher not posted successfully to e-conomics. Invoiceuuid: " + invoice.getUuid() + ", voucher: " + voucher + ", status: " + response.getStatus() + ", body: " + errorBody);
                return response;
            }
        } catch (Exception e) {
            log.error("Failed to send voucher", e);
            throw new RuntimeException(e);
        }
    }

    public Response sendFile(Invoice invoice, Voucher voucher, int voucherNumber, IntegrationKey.IntegrationKeyValue integrationKeyValue) throws IOException {
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
        log.debug("fileResponse = " + fileResponse);
        if ((fileResponse.getStatus() < 200) || (fileResponse.getStatus() > 299)) {
            log.error("file not posted successfully to e-conomics. Expenseuuid: " + invoice.getUuid() + ", voucher: " + voucher.getJournal().getJournalNumber() + ", response: " + fileResponse);
        }
        return fileResponse;
    }

    public Voucher buildJSONRequest(Invoice invoice, Journal journal, String text, IntegrationKey.IntegrationKeyValue integrationKeyValue, dk.trustworks.intranet.model.Company targetCompany){
        log.debug("EconomicsInvoiceService.buildJSONRequest");
        ContraAccount contraAccount = new ContraAccount(integrationKeyValue.invoiceAccountNumber());
        log.debug("contraAccount = " + contraAccount.getAccountNumber());
        ExpenseAccount account = new ExpenseAccount(integrationKeyValue.invoiceAccountNumber());
        log.debug("account = " + account.getAccountNumber());
        String fiscalYearName = DateUtils.getFiscalYearName(
                DateUtils.getFiscalStartDateBasedOnDate(invoice.getInvoicedate()),
                targetCompany.getUuid());
        AccountingYear accountingYear = new AccountingYear(fiscalYearName);
        log.debug("Using accounting year " + accountingYear.getYear() + " for company " + targetCompany.getUuid());

        String date = DateUtils.stringIt(invoice.getInvoicedate());

        log.info("Creating manual customer invoice for number " + invoice.getInvoicenumber());
        ManualCustomerInvoice manualCustomerInvoice = new ManualCustomerInvoice(account, invoice.getInvoicenumber(), text, invoice.getGrandTotal(), contraAccount, date);
        log.debug("ManualCustomerInvoice text=" + manualCustomerInvoice.text + ", contraAccount=" + contraAccount.getAccountNumber());
        List<ManualCustomerInvoice> manualCustomerInvoices = new ArrayList<>();
        manualCustomerInvoices.add(manualCustomerInvoice);

        Entries entries = new Entries();
        Voucher voucher = new Voucher(accountingYear, journal, entries);

        entries.setManualCustomerInvoices(manualCustomerInvoices);
        voucher.setEntries(entries);

        return voucher;
    }


    /**
     * Sends a voucher to e-conomics for a specific company using their custom journal number.
     * This is used for internal invoices that need to be uploaded to multiple companies.
     *
     * @param invoice The invoice to send
     * @param targetCompany The company whose e-conomics to upload to
     * @param journalNumber The journal number to use (e.g., internal-journal-number)
     * @return Response from e-conomics API
     * @throws IOException if upload fails
     */
    public Response sendVoucherToCompany(Invoice invoice, dk.trustworks.intranet.model.Company targetCompany, int journalNumber) throws IOException {
        log.info("EconomicsInvoiceService.sendVoucherToCompany");
        log.infof("Sending invoice %d to company %s using journal %d",
                invoice.invoicenumber, targetCompany.getName(), journalNumber);

        IntegrationKey.IntegrationKeyValue targetKeys = IntegrationKey.getIntegrationKeyValue(targetCompany);
        log.info("integrationKeyValue = " + targetKeys);

        Journal journal = new Journal(journalNumber);
        String text = invoice.getClientname() + ", Faktura " + StringUtils.convertInvoiceNumberToString(invoice.getInvoicenumber());

        Voucher voucher = buildJSONRequest(invoice, journal, text, targetKeys, targetCompany);
        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(voucher);
        log.info("json = " + json);

        // call e-conomics endpoint
        try {
            EconomicsAPI economicsAPI = getEconomicsAPI(targetKeys);
            String idem = "invoice-" + invoice.getUuid() + "-" + targetCompany.getUuid();
            Response response = economicsAPI.postVoucher(journal.getJournalNumber(), idem, json);

            if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
                ObjectMapper objectMapper = new ObjectMapper();
                String responseAsString = response.readEntity(String.class);
                JsonNode array = objectMapper.readValue(responseAsString, JsonNode.class);
                JsonNode object = array.get(0);
                int voucherNumber = object.get("voucherNumber").intValue();

                log.infof("Voucher posted successfully to company %s. VoucherNumber: %d",
                        targetCompany.getName(), voucherNumber);

                // Upload file to e-conomics voucher
                return sendFile(invoice, voucher, voucherNumber, targetKeys);
            } else {
                String errorBody = response.readEntity(String.class);
                log.error("Voucher not posted successfully to company " + targetCompany.getName() +
                         ". Status: " + response.getStatus() + ", body: " + errorBody);
                return response;
            }
        } catch (Exception e) {
            log.error("Failed to send voucher to company " + targetCompany.getName(), e);
            throw new RuntimeException(e);
        }
    }

    private static EconomicsAPI getEconomicsAPI(IntegrationKey.IntegrationKeyValue result) {
        log.info("EconomicsInvoiceService.getEconomicsAPI");
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(result.url()))
                .register(new EconomicsDynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPI.class);
    }

}
