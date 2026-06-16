package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.*;
import dk.trustworks.intranet.expenseservice.exceptions.PdfNotYetRenderedException;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.supplier.EconomicsSupplierResolver;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.aggregates.invoice.utils.StringUtils;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import dk.trustworks.intranet.aggregates.invoice.services.InvoicePdfS3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class EconomicsInvoiceService {

    /** Hardcoded Danish 25% purchase (input) VAT code (I25), applied to the issuer-aware
     *  intercompany cost account (e.g. 3050/3055) on INTERNAL invoices. */
    private static final String INTERCOMPANY_VAT_CODE = "I25";

    /** VAT rate (percent) that {@link #INTERCOMPANY_VAT_CODE} lifts out. e-conomic treats a
     *  VAT-coded supplier-invoice amount as the VAT-inclusive gross, so the posted amount is only
     *  correct when the invoice carries this rate (grandTotal = net × 1.25). See the guard in
     *  {@link #buildJSONRequest}. */
    private static final double INTERCOMPANY_VAT_RATE = 25.0;

    @Inject
    InvoicePdfS3Service invoicePdfS3Service;

    @Inject
    EconomicsSupplierResolver supplierResolver;

    @Inject
    @RestClient
    EconomicsBookingApiClient bookingApi;

    @Inject
    EconomicsAgreementResolver agreementResolver;

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

        // call e-conomics endpoint with proper resource management
        try (EconomicsAPI economicsAPI = getEconomicsAPI(integrationKeyValue)) {
            String idem = "invoice-" + invoice.getUuid();
            try (Response response = economicsAPI.postVoucher(journal.getJournalNumber(), idem, json)) {
                // extract voucher number from response
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
                    throw new RuntimeException("Voucher upload failed with status " + response.getStatus());
                }
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

        byte[] pdfBytes = loadInvoicePdfBytes(invoice);

        // format file to outputstream as MultipartFormDataOutput
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        outputStream.write(pdfBytes);

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

        Entries entries = new Entries();
        Voucher voucher = new Voucher(accountingYear, journal, entries);

        // Check if this is an internal journal (supplier invoice) or regular journal (customer invoice)
        if (journal.getJournalNumber() == integrationKeyValue.internalJournalNumber()) {
            log.info("Creating supplier invoice for number " + invoice.getInvoicenumber());

            // Resolve the issuer-aware intercompany cost account in the DEBTOR's chart of accounts.
            // issuer = invoice.getCompany(); debtor = targetCompany. When the pair is mapped
            // (e.g. Technology -> A/S = 3050, Cyber -> A/S = 3055) the cost must land on the
            // SupplierInvoice's CONTRA account. e-conomic's supplierInvoices voucher entry has no
            // `account` field — it is silently ignored (see the journals-voucher schema: the only
            // accounts on a supplierInvoice are `contraAccount` and the `supplier`). The cost account
            // is therefore the contra (offset) leg, and the CVR-resolved supplier (kreditor) drives
            // the AP credit. We set both account and contraAccount to the mapped number, mirroring the
            // proven unmapped shape (account == contraAccount); only the contra is honoured.
            // When the pair is unmapped, keep today's behaviour EXACTLY (invoice-account-number for
            // both the cost account and the contra account) so out-of-scope intercompany flows are
            // untouched. See spec 2026-06-15-internal-invoice-debtor-cost-account-mapping-design.md §4.4.
            String issuerCompanyUuid = invoice.getCompany() != null ? invoice.getCompany().getUuid() : null;
            Optional<Integer> mappedCostAccount =
                    agreementResolver.intercompanyCostAccount(targetCompany.getUuid(), issuerCompanyUuid);

            ExpenseAccount supplierAccount;
            ContraAccount supplierContraAccount;
            if (mappedCostAccount.isPresent()) {
                int costAccountNumber = mappedCostAccount.get();
                supplierAccount = new ExpenseAccount(costAccountNumber);
                supplierContraAccount = new ContraAccount(costAccountNumber);
            } else {
                log.warnf("No intercompany cost-account mapping for debtor %s / issuer %s — "
                                + "falling back to invoice-account-number %d",
                        targetCompany.getUuid(), issuerCompanyUuid, integrationKeyValue.invoiceAccountNumber());
                supplierAccount = account;
                supplierContraAccount = contraAccount;
            }

            SupplierInvoice supplierInvoice = new SupplierInvoice(
                    supplierAccount,
                    StringUtils.convertInvoiceNumberToString(invoice.getInvoicenumber()),
                    text,
                    invoice.getGrandTotal() != null ? invoice.getGrandTotal() : 0.0,
                    supplierContraAccount,
                    date);

            String issuerCvr = invoice.getCompany() != null ? invoice.getCompany().getCvr() : null;
            Optional<Integer> resolvedSupplier = supplierResolver.resolveByCvr(
                    targetCompany.getUuid(), issuerCvr);
            if (resolvedSupplier.isPresent()) {
                supplierInvoice.setSupplier(new Supplier(resolvedSupplier.get()));
                // Guard: e-conomic treats a VAT-coded supplier-invoice amount as the VAT-inclusive
                // GROSS and lifts the VAT out of it. That is only correct when the posted amount
                // (grandTotal) carries the matching 25% rate. A net amount (vat=0) tagged with I25
                // makes e-conomic lift VAT out of the net — the 2026-06 intercompany mis-posting.
                // Fail closed rather than mis-state VAT; the invoice's VAT rate must be corrected.
                double vatRate = invoice.getVat();
                if (Math.abs(vatRate - INTERCOMPANY_VAT_RATE) > 0.01) {
                    String msg = String.format(
                            "Refusing to post INTERNAL invoice %s to debtor %s with VAT code %s: invoice VAT "
                                    + "rate is %.2f%%, not %.0f%%, so the posted amount is not the VAT-inclusive "
                                    + "gross and e-conomic would lift VAT out of a net amount. Correct the invoice "
                                    + "VAT rate and retry.",
                            invoice.getUuid(), targetCompany.getName(), INTERCOMPANY_VAT_CODE,
                            vatRate, INTERCOMPANY_VAT_RATE);
                    log.error(msg);
                    throw new IllegalStateException(msg);
                }
                supplierInvoice.setContraVatAccount(new VatAccount(INTERCOMPANY_VAT_CODE));
                log.infof("Enriched SupplierInvoice for invoice %s with supplier %d and vatCode %s",
                        invoice.getUuid(), resolvedSupplier.get(), INTERCOMPANY_VAT_CODE);
            } else {
                log.warnf(
                        "INTERNAL invoice %s: no supplier in debtor %s e-conomic for CVR %s — posting without supplier/VAT",
                        invoice.getUuid(),
                        targetCompany.getName(),
                        issuerCvr);
            }

            log.debugf("SupplierInvoice text=%s, contraAccount=%s, supplier=%s, contraVatAccount=%s",
                    supplierInvoice.text,
                    supplierInvoice.getContraAccount(),
                    supplierInvoice.getSupplier(),
                    supplierInvoice.getContraVatAccount());
            List<SupplierInvoice> supplierInvoices = new ArrayList<>();
            supplierInvoices.add(supplierInvoice);
            entries.setSupplierInvoices(supplierInvoices);
        } else {
            log.info("Creating manual customer invoice for number " + invoice.getInvoicenumber());
            ManualCustomerInvoice manualCustomerInvoice = new ManualCustomerInvoice(account, invoice.getInvoicenumber(), text, invoice.getGrandTotal() != null ? invoice.getGrandTotal() : 0.0, contraAccount, date);
            log.debug("ManualCustomerInvoice text=" + manualCustomerInvoice.text + ", contraAccount=" + contraAccount.getAccountNumber());
            List<ManualCustomerInvoice> manualCustomerInvoices = new ArrayList<>();
            manualCustomerInvoices.add(manualCustomerInvoice);
            entries.setManualCustomerInvoices(manualCustomerInvoices);
        }

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

        // call e-conomics endpoint with proper resource management
        try (EconomicsAPI economicsAPI = getEconomicsAPI(targetKeys)) {
            // Use simple key for ISSUER (regular invoices), compound key for DEBTOR (internal)
            String idem = invoice.getCompany().getUuid().equals(targetCompany.getUuid())
                ? "invoice-" + invoice.getUuid()
                : "invoice-" + invoice.getUuid() + "-" + targetCompany.getUuid();
            try (Response response = economicsAPI.postVoucher(journal.getJournalNumber(), idem, json)) {
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
                    throw new RuntimeException("Voucher upload failed to company " + targetCompany.getName() + " with status " + response.getStatus());
                }
            }
        } catch (Exception e) {
            log.error("Failed to send voucher to company " + targetCompany.getName(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolves the PDF bytes for an invoice, trying sources in priority order:
     *
     * <ol>
     *   <li>{@code pdf_storage_key} → S3 (preferred path for invoices migrated to S3).</li>
     *   <li>{@code pdf} LONGBLOB on the invoice row (legacy, pre-S3 invoices).</li>
     *   <li>E-conomic booked-invoice PDF via {@link EconomicsBookingApiClient#getBookedPdf}
     *       using the <strong>ISSUER</strong> company's tokens (the only agreement that
     *       owns the booked PDF). Used after the 2026-04-16 refactor, when neither
     *       local source is populated for newly booked invoices.</li>
     * </ol>
     *
     * <p>A 404 from e-conomic on path 3 means the PDF rendering is still in progress
     * (typically 1-3s after booking). Surfaces as {@link PdfNotYetRenderedException}
     * so the caller's retry mechanism re-attempts the upload on the next cycle
     * rather than thrashing inline. Non-404 errors from e-conomic propagate as-is.
     *
     * <p>Package-private so unit tests in the same package can exercise the fallback
     * matrix directly without going through the multipart upload flow.
     */
    byte[] loadInvoicePdfBytes(Invoice invoice) throws IOException {
        if (invoice.getPdfStorageKey() != null) {
            return invoicePdfS3Service.getPdfByKey(invoice.getPdfStorageKey());
        }
        if (invoice.getPdf() != null) {
            return invoice.getPdf();
        }
        if (invoice.getEconomicsBookedNumber() != null) {
            String issuerCompanyUuid = invoice.getCompany().getUuid();
            EconomicsAgreementResolver.Tokens issuerTokens = agreementResolver.tokens(issuerCompanyUuid);
            try (InputStream pdfStream = bookingApi.getBookedPdf(
                    issuerTokens.appSecret(),
                    issuerTokens.agreementGrant(),
                    invoice.getEconomicsBookedNumber())) {
                return pdfStream.readAllBytes();
            } catch (WebApplicationException wae) {
                int status = wae.getResponse() == null ? -1 : wae.getResponse().getStatus();
                if (status == 404) {
                    throw new PdfNotYetRenderedException(
                            invoice.getUuid(), invoice.getEconomicsBookedNumber());
                }
                throw wae;
            }
        }
        throw new IOException("No PDF available for invoice: " + invoice.getUuid());
    }

    private static EconomicsAPI getEconomicsAPI(IntegrationKey.IntegrationKeyValue result) {
        log.info("EconomicsInvoiceService.getEconomicsAPI");
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(result.url()))
                .register(new EconomicsDynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPI.class);
    }

}
