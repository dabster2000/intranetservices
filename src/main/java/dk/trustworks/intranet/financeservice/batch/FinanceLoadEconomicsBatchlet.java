package dk.trustworks.intranet.financeservice.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.ExpenseMetadata;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.ai.ExpenseInsightRepository;
import dk.trustworks.intranet.expenseservice.ai.TaxonomyAwareExpenseMapper;
import dk.trustworks.intranet.expenseservice.ai.model.ExpenseInsight;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.services.EconomicsService;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.financeservice.model.PostedVoucher;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.api.BatchProperty;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import io.quarkus.narayana.jta.QuarkusTransaction;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import dk.trustworks.intranet.utils.PdfToImage;

@JBossLog
@Named("financeLoadEconomicsBatchlet")
@Dependent
public class FinanceLoadEconomicsBatchlet extends AbstractBatchlet {

    private final Map<String, Set<Integer>> expenseAccountsCache = new HashMap<>();

    private Set<Integer> loadExpenseAccounts(EconomicsAPI api, String companyUuid) {
        return expenseAccountsCache.computeIfAbsent(companyUuid, k -> {
            Set<Integer> set = new HashSet<>();
            int skip = 0;
            while (true) {
                Response r = null;
                try {
                    r = api.getAccounts("accountType$eq:profitAndLoss$and:debitCredit$eq:debit", 1000, skip);
                    if (r == null) break;
                    String body = r.readEntity(String.class);
                    List<JsonNode> items = extractItems(body);
                    for (JsonNode acc : items) {
                        if (acc.has("accountNumber")) set.add(acc.get("accountNumber").asInt());
                        else if (acc.has("account") && acc.get("account").has("accountNumber")) set.add(acc.get("account").get("accountNumber").asInt());
                    }
                    if (items.size() < 1000) break;
                    skip++;
                } catch (Exception e) {
                    log.warnf("Failed loading expense accounts (using empty set): %s", e.getMessage());
                    break;
                } finally { if (r != null) r.close(); }
            }
            return set;
        });
    }

    static class Stats {
        long vouchersConsidered;
        long keptExpenseEntries;
        long skippedNonExpenseEntries;
        long vouchersInserted;
        long vouchersUpdated;
        long attachmentsStored;
        long insightsCreated;
        void add(Stats o) {
            if (o == null) return;
            vouchersConsidered += o.vouchersConsidered;
            keptExpenseEntries += o.keptExpenseEntries;
            skippedNonExpenseEntries += o.skippedNonExpenseEntries;
            vouchersInserted += o.vouchersInserted;
            vouchersUpdated += o.vouchersUpdated;
            attachmentsStored += o.attachmentsStored;
            insightsCreated += o.insightsCreated;
        }
    }

    static class FindResult {
        final PostedVoucher pv;
        final boolean isNew;
        FindResult(PostedVoucher pv, boolean isNew) { this.pv = pv; this.isNew = isNew; }
    }

    @Inject EconomicsService economicsService;
    @Inject ExpenseFileService expenseFileService;
    @Inject OpenAIService openAIService;
    @Inject ExpenseInsightRepository insightRepository;
    @Inject TaxonomyAwareExpenseMapper taxonomyMapper;

    @PersistenceContext EntityManager em;

    @Inject @BatchProperty(name = "sleepMillis") String sleepMillisProp;
    @Inject @BatchProperty(name = "pagesize") String pageSizeProp;

    private static final ObjectMapper M = new ObjectMapper();

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        long sleepMillis = parseOrDefaultLong(sleepMillisProp, 1200L);
        int pageSize = parseOrDefault(pageSizeProp, 500);

        List<Company> companies = Company.listAll();
        log.infof("Finance load: %d companies", companies.size());

        Stats total = new Stats();
        for (Company c : companies) {
            try {
                Stats s = processCompany(c, pageSize, sleepMillis);
                total.add(s);
            } catch (Exception ex) {
                log.errorf(ex, "Finance load failed for company %s", c.getUuid());
            }
            // brief pause between companies
            sleepQuiet(1000);
        }
        log.infof("Finance load completed. vouchersConsidered=%d keptExpenseEntries=%d inserted=%d updated=%d attachments=%d insights=%d skippedNonExpenseEntries=%d",
                total.vouchersConsidered, total.keptExpenseEntries, total.vouchersInserted, total.vouchersUpdated,
                total.attachmentsStored, total.insightsCreated, total.skippedNonExpenseEntries);
        return "COMPLETED";
    }

    Stats processCompany(Company company, int pageSize, long sleepMillis) {
        EconomicsAPI api = economicsService.getApiForCompany(company);

        Stats companyStats = new Stats();

        // Load/cached expense account numbers (P&L and debit)
        Set<Integer> expenseAccounts = loadExpenseAccounts(api, company.getUuid());

        // Compute current and last fiscal year labels and URL keys
        LocalDate currentStart = DateUtils.getCurrentFiscalStartDate();
        LocalDate lastStart = currentStart.minusYears(1);
        List<String> labels = List.of(
                DateUtils.getFiscalYearName(lastStart, company.getUuid()),
                DateUtils.getFiscalYearName(currentStart, company.getUuid())
        );

        for (String label : labels) {
            String urlYear = toUrlYear(label);
            Stats yearStats = new Stats();

            // Dedup per (company, year, voucherNumber) so we only process each voucher once,
            // regardless of journal and number of lines.
            Set<String> processedVouchers = new HashSet<>();

            // Load all booked entries for the year and paginate
            String filter = "entryType$eq:financeVoucher$and:voucherNumber$gt:0";
            int skipPages = 0;

            while (true) {
                Response resp = null;
                String body = null;

                // Backoff for 429/503
                int attempts = 0;
                while (true) {
                    try {
                        resp = api.getYearEntries(urlYear, filter, pageSize, skipPages);
                        if (resp == null) break;
                        int st = resp.getStatus();
                        if (st == 429 || st == 503) {
                            sleepQuiet(sleepMillis * (attempts + 1));
                            attempts++;
                            resp.close();
                            if (attempts < 3) continue; else break;
                        }
                        body = resp.readEntity(String.class);
                        break;
                    } catch (Exception e) {
                        log.errorf(e, "Year entries fetch failed. company=%s, year=%s", company.getUuid(), urlYear);
                        break;
                    } finally {
                        if (resp != null) resp.close();
                    }
                }

                List<JsonNode> items = extractItems(body);
                if (items.isEmpty()) {
                    if (skipPages == 0) {
                        log.infof("No entries for company=%s year=%s", company.getUuid(), urlYear);
                    }
                    break;
                }

                log.infof("Processing %d entries for company=%s year=%s page=%d",
                        items.size(), company.getUuid(), urlYear, skipPages);

                for (JsonNode it : items) {
                    try {
                        // Pull voucherNumber; skip if missing
                        Integer voucherNumber = getInt(it, "voucherNumber");
                        if (voucherNumber == null) {
                            JsonNode v = it.get("voucher");
                            if (v != null && v.get("voucherNumber") != null) {
                                voucherNumber = v.get("voucherNumber").asInt();
                            }
                        }
                        if (voucherNumber == null) continue;

                        // De-dup strictly by (company, year, voucherNumber)
                        String key = company.getUuid() + "|" + urlYear + "|" + voucherNumber;
                        if (processedVouchers.contains(key)) {
                            // already processed this voucher on a previous line
                            continue;
                        }
                        processedVouchers.add(key);
                        yearStats.vouchersConsidered++;

                        // Expense filtering by account semantics (P&L + debit)
                        String account = getAccountNumber(it);
                        Integer accountNum = null;
                        try {
                            accountNum = (account != null && !account.isBlank()) ? Integer.valueOf(account) : null;
                        } catch (Exception ignored) { /* leave null */ }

                        if (accountNum == null || !expenseAccounts.contains(accountNum)) {
                            yearStats.skippedNonExpenseEntries++;
                            continue; // not an expense entry
                        }
                        yearStats.keptExpenseEntries++;

                        // Optional fields for storage
                        //Integer journalNumber = getJournalNumber(it); // may be null; not required for identity
                        Integer journalNumber = resolveJournalNumber(it);
                        String yearToken = resolveEncodedAccountingYear(it, urlYear); // urlYear stays as a fallback
                        Double amount = getDouble(it, "amount");
                        if (amount == null && it.has("entry") && it.get("entry").has("amount"))
                            amount = it.get("entry").get("amount").asDouble();
                        String currency = getText(it, "currency");
                        LocalDate date = parseDate(getText(it, "date"));

                        Integer contra = getContraAccountNumber(it);
                        String useruuid = resolveUserByContra(company, contra);

                        // Freeze for lambdas
                        final String companyUuid = company.getUuid();
                        final String urlYearFinal = urlYear;
                        final Integer voucherNumberFinal = voucherNumber;
                        final String labelFinal = label;
                        final String accountFinal = account;
                        final Double amountFinal = amount;
                        final String currencyFinal = currency;
                        final LocalDate dateFinal = date;
                        final String useruuidFinal = useruuid;
                        final Integer journalNumberFinal = journalNumber; // stored as data only

                        // --- TX #1: upsert voucher row, capture managed instance (with id) ---
                        final boolean[] isNewArr = new boolean[1];
                        final PostedVoucher[] holder = new PostedVoucher[1];

                        QuarkusTransaction.requiringNew().run(() -> {
                            FindResult fr = findOrCreate(companyUuid, urlYearFinal, voucherNumberFinal);
                            PostedVoucher pvIn = fr.pv;

                            pvIn.setAccounting_year_label(labelFinal);
                            pvIn.setJournalnumber(journalNumberFinal); // may be null
                            pvIn.setAccount(accountFinal);
                            pvIn.setAmount(amountFinal);
                            pvIn.setCurrency(currencyFinal);
                            pvIn.setVoucher_date(dateFinal);
                            pvIn.setUseruuid(useruuidFinal);
                            pvIn.setSource("economics");
                            if (pvIn.getCreatedAt() == null) pvIn.setCreatedAt(OffsetDateTime.now());

                            PostedVoucher managed = em.merge(pvIn); // IMPORTANT: use returned managed entity
                            em.flush();

                            isNewArr[0] = fr.isNew;
                            holder[0] = managed;      // keep the managed instance (has id)
                        });

                        PostedVoucher pv = holder[0];
                        if (isNewArr[0]) yearStats.vouchersInserted++; else yearStats.vouchersUpdated++;

                        pv.setJournalnumber(journalNumber); // may be null if not resolvable; that’s fine

                        // Fetch/store attachment if not already present
                        String s3Key = pv.getAttachment_s3_key();
                        if (s3Key == null || s3Key.isBlank()) {
                            //byte[] bytes = fetchAttachmentWithFallback(api, urlYear, (journalNumberFinal == null ? -1 : journalNumberFinal), voucherNumberFinal, sleepMillis);
                            //byte[] bytes = fetchAttachment(api, journalNumberFinal, urlYear, voucherNumberFinal, sleepMillis);
                            byte[] bytes = fetchVoucherAttachment(api, journalNumber, yearToken, voucherNumber, sleepMillis);
                            if (bytes != null && bytes.length > 0) {
                                // 1) Store original file with correct MIME in data URL
                                String mime = sniffMime(bytes);
                                String b64 = Base64.getEncoder().encodeToString(bytes);
                                String content = "data:" + mime + ";base64," + b64;
                                String docUuid = UUID.randomUUID().toString();
                                expenseFileService.saveFile(new ExpenseFile(docUuid, content));

                                final Long pvId = pv.getId();
                                final String docUuidFinal = docUuid;
                                QuarkusTransaction.requiringNew().run(() -> {
                                    PostedVoucher managed2 = em.find(PostedVoucher.class, pvId);
                                    managed2.setAttachment_s3_key(docUuidFinal);
                                    managed2.setExpense_uuid(docUuidFinal); // reuse for insights
                                    em.flush();
                                });
                                yearStats.attachmentsStored++;

                                // 2) Prepare image bytes for OpenAI: if PDF -> rasterize; else use as-is
                                byte[] imageBytesForAI = null;
                                if ("application/pdf".equals(mime)) {
                                    try {
                                        imageBytesForAI = PdfToImage.firstPageAsPng(bytes);
                                    } catch (Throwable t) {
                                        log.debugf("PDF rasterization error for voucher %d in year %s: %s",
                                                voucherNumberFinal, urlYear, String.valueOf(t));
                                    }
                                } else if (mime.startsWith("image/")) {
                                    imageBytesForAI = bytes; // already an image
                                }

                                if (imageBytesForAI != null && imageBytesForAI.length > 0) {
                                    String imageB64 = Base64.getEncoder().encodeToString(imageBytesForAI);
                                    boolean willCreateInsight = !insightRepository.existsByExpenseUuid(docUuidFinal);
                                    try {
                                        ExpenseMetadata md = openAIService.extractExpenseMetadata(
                                                imageB64,
                                                buildHints(amountFinal, currencyFinal)  // <- currency-aware
                                        );
                                        persistInsightForVoucher(pv, md);
                                        if (willCreateInsight) yearStats.insightsCreated++;
                                        sleepQuiet(sleepMillis); // throttle OpenAI
                                    } catch (Exception aiEx) {
                                        log.errorf(aiEx, "OpenAI extraction failed for voucher %s/%s-%d",
                                                journalNumberFinal, urlYear, voucherNumberFinal);
                                    }
                                } else {
                                    log.debugf("No image produced for AI (mime=%s) voucher %d in year %s",
                                            mime, voucherNumberFinal, urlYear);
                                }
                            }
                        }


                        // Throttle between vouchers (e-conomic API limit)
                        sleepQuiet(sleepMillis);

                    } catch (Exception e) {
                        log.error("Failed processing entry", e);
                    }
                }

                if (items.size() < pageSize) break; // last page
                skipPages++;
            }

            // Year summary
            log.infof(
                    "Company=%s year=%s summary: considered=%d keptExpense=%d inserted=%d updated=%d attachments=%d insights=%d skippedNonExpense=%d",
                    company.getUuid(), urlYear,
                    yearStats.vouchersConsidered, yearStats.keptExpenseEntries,
                    yearStats.vouchersInserted, yearStats.vouchersUpdated,
                    yearStats.attachmentsStored, yearStats.insightsCreated,
                    yearStats.skippedNonExpenseEntries
            );

            companyStats.add(yearStats);
        }

        return companyStats;
    }

    private byte[] fetchVoucherAttachment(EconomicsAPI api, Integer journalNumber, String encodedYear, int voucherNumber, long baseSleep) {
        if (journalNumber == null || encodedYear == null) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Response r = null;
            try {
                r = api.getVoucherAttachmentFile(journalNumber, encodedYear, voucherNumber);
                if (r == null) break;
                int st = r.getStatus();
                if (st >= 200 && st < 300) return r.readEntity(byte[].class);
                if (st == 429 || st == 503) { sleepQuiet(baseSleep * (attempt + 1)); continue; }
                // Optional: try metadata first to confirm presence
                try { String body = r.readEntity(String.class); log.debugf("Attachment status=%d body=%.200s", st, body); } catch (Exception ignored) {}
                break;
            } catch (Exception ex) {
                log.debugf("Journal attachment fetch failed: %s", ex.getMessage());
                break;
            } finally { if (r != null) r.close(); }
        }
        return null;
    }


    @Transactional
    void persistInsightForVoucher(PostedVoucher pv, ExpenseMetadata md) {
        if (pv.getExpense_uuid() == null) return;
        if (insightRepository.existsByExpenseUuid(pv.getExpense_uuid())) return;
        ExpenseInsight e = new ExpenseInsight();
        e.setExpenseUuid(pv.getExpense_uuid());
        e.setUseruuid(pv.getUseruuid());
        e.setMerchantName(md.getMerchantName());
        e.setMerchantCategory(md.getMerchantCategory());
        e.setConfidence(md.getConfidence());
        e.setExpenseDate(md.getExpenseDate() != null && !md.getExpenseDate().isBlank() ? LocalDate.parse(md.getExpenseDate()) : pv.getVoucher_date());
        e.setCurrency(md.getCurrency() != null ? md.getCurrency() : pv.getCurrency());
        e.setTotalAmount(md.getTotalAmount());
        e.setSubtotalAmount(md.getSubtotalAmount());
        e.setVatAmount(md.getVatAmount());
        e.setPaymentMethod(md.getPaymentMethod());
        e.setCountry(md.getCountry());
        e.setCity(md.getCity());
        e.setModelName("gpt-5-mini");
        try { e.setRawJson(M.writeValueAsString(md)); } catch (Exception ignored) {}
        e.setCreatedAt(OffsetDateTime.now());
        em.persist(e);
        insightRepository.persistLineItems(e, md.getLineItems());
        // Tags using taxonomy
        var tags = new java.util.LinkedHashSet<>(taxonomyMapper.computeTags(md, e));
        insightRepository.persistTags(e, new java.util.ArrayList<>(tags));
    }

    private static String buildHints(Double amount, String currency) {
        String curr = (currency != null && !currency.isBlank()) ? currency : "DKK";
        String amt = amount != null ? String.format(Locale.ROOT, "%.2f %s", amount, curr) : "unknown";
        return "Extract structured expense data. Classify categories using our taxonomy. Declared amount: " + amt;
    }


    private FindResult findOrCreate(String companyuuid, String urlYear, int voucher) {
        List<PostedVoucher> list = em.createQuery(
                        "select p from PostedVoucher p where p.companyuuid = :c and p.accounting_year_url = :y and p.vouchernumber = :v",
                        PostedVoucher.class)
                .setParameter("c", companyuuid)
                .setParameter("y", urlYear)
                .setParameter("v", voucher)
                .getResultList();
        boolean isNew = list.isEmpty();
        PostedVoucher pv = isNew ? new PostedVoucher() : list.get(0);
        pv.setCompanyuuid(companyuuid);
        pv.setAccounting_year_url(urlYear);
        pv.setVouchernumber(voucher);
        return new FindResult(pv, isNew);
    }


    private byte[] fetchAttachment(EconomicsAPI api,
                                   Integer journalNumber,
                                   String urlYear,
                                   int voucherNumber,
                                   long baseSleep) {
        // We can only download via journal-scoped endpoint:
        // GET /journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file
        // (returns a PDF)
        if (journalNumber == null || journalNumber < 1) return null;

        for (int attempt = 0; attempt < 3; attempt++) {
            Response r = null;
            try {
                r = api.getVoucherAttachmentFile(journalNumber, urlYear, voucherNumber);
                if (r == null) break;
                int st = r.getStatus();
                if (st >= 200 && st < 300) {
                    return r.readEntity(byte[].class);
                } else if (st == 404) {
                    // No attachment for this voucher; nothing else to try
                    return null;
                } else if (st == 429 || st == 503) {
                    // Back off and retry a couple of times
                    sleepQuiet(baseSleep * (attempt + 1));
                    continue;
                } else {
                    try {
                        String body = r.readEntity(String.class);
                        log.debugf("Attachment fetch status=%d (voucher=%d year=%s journal=%d) body=%.200s",
                                st, voucherNumber, urlYear, journalNumber, body);
                    } catch (Exception ignored) {}
                    break;
                }
            } catch (Exception ex) {
                // Network or deserialization error; give up quietly for this voucher
                break;
            } finally {
                if (r != null) r.close();
            }
        }
        return null;
    }


    private byte[] fetchAttachmentWithFallback(EconomicsAPI api, String urlYear, int journalNumber, int voucherNumber, long baseSleep) {
        // Try year-level first
        int[] backoff = new int[]{0,1,2};
        for (int attempt = 0; attempt < backoff.length; attempt++) {
            Response r = null;
            try {
                r = api.getYearVoucherAttachmentFile(urlYear, voucherNumber);
                if (r == null) break;
                int st = r.getStatus();
                if (st >= 200 && st < 300) {
                    return r.readEntity(byte[].class);
                } else if (st == 429 || st == 503) {
                    sleepQuiet(baseSleep * (attempt + 1));
                    continue;
                } else if (st == 404) {
                    break; // fall through to journal-level if possible
                } else {
                    try { String body = r.readEntity(String.class); log.warnf("Year-level attachment fetch status=%d body=%.200s", st, body); } catch (Exception ignored) {}
                    break;
                }
            } catch (Exception ex) {
                log.debugf("Year-level attachment fetch failed: %s", ex.getMessage());
                break;
            } finally { if (r != null) r.close(); }
        }

        // Skip journal fallback when we don't have a valid journal number
        if (journalNumber < 0) {
            return null;
        }

        // Fallback: journal-based
        for (int attempt = 0; attempt < 2; attempt++) {
            Response r = null;
            try {
                r = api.getVoucherAttachmentFile(journalNumber, urlYear, voucherNumber);
                if (r == null) break;
                int st = r.getStatus();
                if (st >= 200 && st < 300) {
                    return r.readEntity(byte[].class);
                } else if (st == 429 || st == 503) {
                    sleepQuiet(baseSleep * (attempt + 1));
                    continue;
                } else {
                    try { String body = r.readEntity(String.class); log.debugf("Journal attachment fetch status=%d body=%.200s", st, body); } catch (Exception ignored) {}
                    break;
                }
            } catch (Exception ex) {
                log.debugf("Journal attachment fetch failed: %s", ex.getMessage());
                break;
            } finally { if (r != null) r.close(); }
        }
        return null;
    }

    private static Integer journalFromAnyUrl(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/journals/(\\d+)/")
                .matcher(url);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String encodedYearFromVoucherUrl(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/vouchers/([^/]+)-(\\d+)")
                .matcher(url);
        return m.find() ? m.group(1) : null; // e.g. 2024_6_2025
    }

    private static String textAt(JsonNode n, String... path) {
        JsonNode cur = n;
        for (String p: path) { if (cur == null || !cur.has(p)) return null; cur = cur.get(p); }
        return cur != null && cur.isTextual() ? cur.asText() : null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v: vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static Integer resolveJournalNumber(JsonNode entry) {
        String vSelf = firstNonBlank(
                textAt(entry, "voucher", "self"),
                textAt(entry, "entry", "voucher", "self")
        );
        Integer j = journalFromAnyUrl(vSelf);
        if (j != null) return j;

        String eSelf = firstNonBlank(
                textAt(entry, "self"),
                textAt(entry, "entry", "self")
        );
        j = journalFromAnyUrl(eSelf);
        if (j != null) return j;

        // As a last resort, look in links[]
        if (entry.has("links") && entry.get("links").isArray()) {
            for (JsonNode l : entry.get("links")) {
                if (l.has("href") && l.get("href").isTextual()) {
                    j = journalFromAnyUrl(l.get("href").asText());
                    if (j != null) return j;
                }
            }
        }
        return null;
    }

    private static String resolveEncodedAccountingYear(JsonNode entry, String fallback) {
        String vSelf = firstNonBlank(
                textAt(entry, "voucher", "self"),
                textAt(entry, "entry", "voucher", "self")
        );
        String token = encodedYearFromVoucherUrl(vSelf);
        return token != null ? token : fallback;
    }



    private static String toUrlYear(String label) {
        String[] parts = label.split("/");
        if (parts.length == 2) return parts[0] + "_6_" + parts[1];
        return label; // already formatted
    }

    private static List<JsonNode> extractItems(String body) {
        List<JsonNode> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        try {
            JsonNode root = M.readTree(body);
            if (root.isArray()) root.forEach(out::add);
            else if (root.has("collection") && root.get("collection").isArray()) root.get("collection").forEach(out::add);
            else if (root.has("items") && root.get("items").isArray()) root.get("items").forEach(out::add);
        } catch (Exception ignored) {}
        return out;
    }

    private static String getAccountNumber(JsonNode node) {
        if (node.has("account") && node.get("account").has("accountNumber")) return node.get("account").get("accountNumber").asText();
        if (node.has("entry") && node.get("entry").has("account") && node.get("entry").get("account").has("accountNumber")) return node.get("entry").get("account").get("accountNumber").asText();
        return null;
    }

    private static Integer getContraAccountNumber(JsonNode node) {
        if (node.has("contraAccount") && node.get("contraAccount").has("accountNumber")) return node.get("contraAccount").get("accountNumber").asInt();
        if (node.has("entry") && node.get("entry").has("contraAccount") && node.get("entry").get("contraAccount").has("accountNumber")) return node.get("entry").get("contraAccount").get("accountNumber").asInt();
        return null;
    }

    private static Integer getJournalNumber(JsonNode node) {
        try {
            // Prefer explicit numeric fields when available
            if (node.has("journal") && node.get("journal").has("journalNumber"))
                return node.get("journal").get("journalNumber").asInt();
            if (node.has("voucher")) {
                JsonNode v = node.get("voucher");
                if (v.has("journal") && v.get("journal").has("journalNumber"))
                    return v.get("journal").get("journalNumber").asInt();
                if (v.has("journalNumber"))
                    return v.get("journalNumber").asInt();
            }
            if (node.has("entry")) {
                JsonNode e = node.get("entry");
                if (e.has("journal") && e.get("journal").has("journalNumber"))
                    return e.get("journal").get("journalNumber").asInt();
                if (e.has("voucher") && e.get("voucher").has("journal") && e.get("voucher").get("journal").has("journalNumber"))
                    return e.get("voucher").get("journal").get("journalNumber").asInt();
                if (e.has("voucher") && e.get("voucher").has("journalNumber"))
                    return e.get("voucher").get("journalNumber").asInt();
            }
            // Fallback: parse from self links (e-conomic often exposes journal as a link)
            String link = getLink(node, "journal", "self");
            if (link == null) link = getLink(node, "entry", "journal", "self");
            if (link == null) link = getLink(node, "voucher", "journal", "self");
            if (link == null) link = getLink(node, "entry", "voucher", "journal", "self");
            if (link == null && node.has("links") && node.get("links").isArray()) {
                for (JsonNode l : node.get("links")) {
                    if (l.has("rel") && l.get("rel").asText().toLowerCase().contains("journal") && l.has("href")) {
                        link = l.get("href").asText();
                        break;
                    }
                }
            }
            if (link != null) {
                Integer n = parseLastIntFromUrl(link);
                if (n != null) return n;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String getLink(JsonNode node, String... path) {
        try {
            JsonNode cur = node;
            for (String p : path) {
                if (cur == null || !cur.has(p)) return null;
                cur = cur.get(p);
            }
            return cur != null && cur.isTextual() ? cur.asText() : null;
        } catch (Exception e) { return null; }
    }

    private static Integer parseLastIntFromUrl(String url) {
        if (url == null) return null;
        try {
            // Trim query/fragment
            int q = url.indexOf('?'); if (q >= 0) url = url.substring(0, q);
            int h = url.indexOf('#'); if (h >= 0) url = url.substring(0, h);
            // Remove trailing slash
            if (url.endsWith("/")) url = url.substring(0, url.length()-1);
            int idx = url.lastIndexOf('/');
            if (idx >= 0 && idx+1 < url.length()) {
                String last = url.substring(idx+1);
                // Sometimes last is like journals:9 or something numeric only
                String digits = last.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) return Integer.parseInt(digits);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveUserByContra(Company company, Integer contraAccount) {
        if (contraAccount == null) return null;
        List<UserAccount> ua = UserAccount.find("companyuuid = ?1 and economics = ?2", company.getUuid(), String.valueOf(contraAccount)).list();
        return ua.isEmpty() ? null : ua.get(0).getUseruuid();
    }

    private static String getText(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isTextual()) return node.get(field).asText();
        if (node.has("entry") && node.get("entry").has(field) && node.get("entry").get(field).isTextual()) return node.get("entry").get(field).asText();
        return null;
    }

    private static Integer getInt(JsonNode node, String field) {
        if (node.has(field) && node.get(field).canConvertToInt()) return node.get(field).asInt();
        if (node.has("entry") && node.get("entry").has(field) && node.get("entry").get(field).canConvertToInt()) return node.get("entry").get(field).asInt();
        return null;
    }

    private static Double getDouble(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isNumber()) return node.get(field).asDouble();
        if (node.has("entry") && node.get("entry").has(field) && node.get("entry").get(field).isNumber()) return node.get("entry").get(field).asDouble();
        return null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private static String sniffMime(byte[] b) {
        if (b == null || b.length < 4) return "application/octet-stream";
        // %PDF
        if (b[0] == 0x25 && b[1] == 0x50 && b[2] == 0x44 && b[3] == 0x46) return "application/pdf";
        // PNG
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return "image/png";
        // JPEG
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        // GIF
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        // WebP "RIFF....WEBP"
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b.length >= 12 &&
                b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return "application/octet-stream";
    }

    // Cache whether year-level attachments are supported for a given company+year
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> yearAttachmentSupport = new java.util.concurrent.ConcurrentHashMap<>();

    private static String yearKey(String companyUuid, String urlYear) {
        return companyUuid + "|" + urlYear;
    }
    private boolean allowYearAttachment(String companyUuid, String urlYear) {
        return yearAttachmentSupport.getOrDefault(yearKey(companyUuid, urlYear), true);
    }
    private void markYearAttachmentUnsupported(String companyUuid, String urlYear) {
        String key = yearKey(companyUuid, urlYear);
        Boolean prev = yearAttachmentSupport.put(key, false);
        if (prev == null || prev) {
            // Log only once when we decide to stop trying year-level for this company+year
            log.infof("Year-level voucher attachments not available for company=%s year=%s; using journal fallback only.", companyUuid, urlYear);
        }
    }
    private void markYearAttachmentSupported(String companyUuid, String urlYear) {
        yearAttachmentSupport.put(yearKey(companyUuid, urlYear), true);
    }

    private static int parseOrDefault(String s, int def) { try { return s != null ? Integer.parseInt(s) : def; } catch (Exception e) { return def; } }
    private static long parseOrDefaultLong(String s, long def) { try { return s != null ? Long.parseLong(s) : def; } catch (Exception e) { return def; } }
    private static void sleepQuiet(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }
}
