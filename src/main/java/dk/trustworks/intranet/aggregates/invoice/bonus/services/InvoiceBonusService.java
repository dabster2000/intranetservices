// src/main/java/dk/trustworks/intranet/aggregates/invoice/bonus/services/InvoiceBonusService.java
package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibilityGroup;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.BonusCacheInvalidationEvent;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.EnrichedBonusLineDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus.ShareType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonusLine;
import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.PartnerBonusCompanySplitMath;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import dk.trustworks.intranet.utils.DateUtils;

import static dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType.CREDIT_NOTE;

@JBossLog
@ApplicationScoped
public class InvoiceBonusService {

    /**
     * SQL expression yielding the work-period date for an invoice: the first day of the
     * (year, month) work period when both are set, else the raw invoicedate. Alias 'i' = invoices.
     */
    public static final String WP_DATE_SQL =
        "(CASE WHEN i.year > 0 AND i.month BETWEEN 1 AND 12 " +
        "      THEN (MAKEDATE(i.year, 1) + INTERVAL (i.month - 1) MONTH) " +
        "      ELSE i.invoicedate END)";

    /**
     * The credited-measure tolerance: an invoice counts as fully credited when the live
     * credit-note sum is within 1.0 DKK of the invoice's own item sum. Shared by every
     * SQL fragment below and by {@code CreditNoteCoverageService} (the Java-side measure).
     */
    public static final double CREDITED_TOLERANCE_DKK = 1.0;

    /**
     * Correlated subquery: Σ(hours×rate) over items of LIVE credit notes
     * (CREATED/QUEUED/PENDING_REVIEW) pointing at the invoice aliased {@code invAlias}.
     * This is the single SQL definition of "how much of an invoice is credited".
     */
    public static String creditedSumSql(String invAlias) {
        return "(SELECT COALESCE(SUM(cii.hours*cii.rate),0) " +
               "   FROM invoiceitems cii JOIN invoices cn ON cn.uuid = cii.invoiceuuid " +
               "  WHERE cn.creditnote_for_uuid = " + invAlias + ".uuid AND cn.type = 'CREDIT_NOTE' " +
               "    AND cn.status IN ('CREATED','QUEUED','PENDING_REVIEW'))";
    }

    /** Correlated subquery: Σ(hours×rate) over the invoice's own items. */
    public static String invoiceItemSumSql(String invAlias) {
        return "(SELECT COALESCE(SUM(oii.hours*oii.rate),0) FROM invoiceitems oii WHERE oii.invoiceuuid = " + invAlias + ".uuid)";
    }

    /**
     * SQL predicate: the invoice aliased {@code invAlias} is FULLY credited — its live
     * credit-note sum covers its own item sum within {@link #CREDITED_TOLERANCE_DKK}.
     * Sum-based on purpose: a partial credit note does NOT make an invoice fully credited.
     */
    public static String fullyCreditedPredicateSql(String invAlias) {
        return "( " + creditedSumSql(invAlias) +
               "   >= " + invoiceItemSumSql(invAlias) + " - " + CREDITED_TOLERANCE_DKK + " " +
               "  AND " + invoiceItemSumSql(invAlias) + " > 0 " +
               " )";
    }

    /**
     * SQL fragment (leading ' AND NOT (...)') that excludes invoices fully credited by open/pending
     * credit notes. Alias 'i' = invoices. Takes NO parameters. Multi-credit-note safe: the inner
     * measure SUMs across ALL live credit notes of the invoice.
     */
    public static final String NOT_FULLY_CREDITED_SQL = " AND NOT " + fullyCreditedPredicateSql("i");

    @Inject
    Event<BonusCacheInvalidationEvent> cacheInvalidation;

    public SalesApprovalStatus aggregatedStatusForInvoice(String invoiceuuid) {
        List<InvoiceBonus> bonuses = InvoiceBonus.list("invoiceuuid = ?1", invoiceuuid);
        boolean hasPending = bonuses.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.PENDING);
        boolean hasRejected = bonuses.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.REJECTED);
        boolean hasApproved = bonuses.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.APPROVED);
        if (hasPending) return SalesApprovalStatus.PENDING;
        if (hasRejected) return SalesApprovalStatus.REJECTED;
        if (hasApproved) return SalesApprovalStatus.APPROVED;
        return SalesApprovalStatus.PENDING;
    }

    public double totalBonusAmountForInvoice(String invoiceuuid) {
        return InvoiceBonus.<InvoiceBonus>stream("invoiceuuid = ?1", invoiceuuid)
                .mapToDouble(InvoiceBonus::getComputedAmount)
                .sum();
    }

    public List<InvoiceBonus> findByInvoice(String invoiceuuid) {
        return InvoiceBonus.list("invoiceuuid = ?1", invoiceuuid);
    }

    public double sumApproved(String invoiceuuid) {
        return InvoiceBonus.<InvoiceBonus>stream("invoiceuuid = ?1 and status = ?2",
                        invoiceuuid, SalesApprovalStatus.APPROVED)
                .mapToDouble(InvoiceBonus::getComputedAmount)
                .sum();
    }

    /**
     * Sum of APPROVED, not-yet-consumed (payoutUuid IS NULL) bonus amounts on an invoice that belong
     * to one of the given users. Scoping to the group's members is essential: an invoice can carry
     * APPROVED rows from members of more than one partner group, and each group's basis (and the rows
     * it consumes) must include ONLY its own members' rows — otherwise the first group to pay would
     * absorb and stamp another group's share. This is the "still fundable" basis for the group.
     */
    public double sumApprovedUnconsumed(String invoiceuuid, Set<String> users) {
        if (users == null || users.isEmpty()) return 0.0;
        return InvoiceBonus.<InvoiceBonus>stream(
                        "invoiceuuid = ?1 and status = ?2 and payoutUuid is null and useruuid in ?3",
                        invoiceuuid, SalesApprovalStatus.APPROVED, users)
                .mapToDouble(InvoiceBonus::getComputedAmount)
                .sum();
    }

    /**
     * Distinct invoice UUIDs in [from, to] bucketed by <b>work period</b> ({@code invoice.year}/{@code month},
     * falling back to {@code invoicedate}; see {@link #WP_DATE_SQL}) that carry at least one APPROVED bonus
     * from one of the given users. Invoices fully reversed by a live credit note are excluded
     * ({@link #NOT_FULLY_CREDITED_SQL}). When {@code onlyUnconsumed} is true, only invoices whose APPROVED
     * bonus rows have not yet been stamped to a payout (payout_uuid IS NULL) are returned — this is the
     * set the partner-bonus payout is allowed to consume.
     */
    public List<String> findApprovedInvoiceIdsForUsers(Set<String> users, LocalDate from, LocalDate to,
                                                       boolean onlyUnconsumed) {
        if (users == null || users.isEmpty()) return List.of();
        String sql = "SELECT DISTINCT i.uuid"
                + " FROM invoices i JOIN invoice_bonuses b ON b.invoiceuuid = i.uuid"
                + " WHERE b.status = :approved"
                + "   AND b.useruuid IN (:users)"
                + "   AND " + WP_DATE_SQL + " >= :from"
                + "   AND " + WP_DATE_SQL + " <= :to"
                + (onlyUnconsumed ? "   AND b.payout_uuid IS NULL" : "")
                + NOT_FULLY_CREDITED_SQL;
        var q = Panache.getEntityManager().createNativeQuery(sql)
                .setParameter("approved", SalesApprovalStatus.APPROVED.name())
                .setParameter("users", users)
                .setParameter("from", from)
                .setParameter("to", to);
        List<?> raw = q.getResultList();
        return raw.stream().map(String::valueOf).toList();
    }

    /** Company key used when a company cannot be resolved (no issuing company, no status at the date). */
    public static final String UNKNOWN_COMPANY_UUID = "unknown";

    /**
     * One selected invoice line of an APPROVED, un-consumed bonus row of a partner, as loaded by
     * {@link #findApprovedUnconsumedBasisLines}. {@code selectedAmount} is the item's
     * {@code hours × rate × pct/100}; {@code consultantUuid}, {@code origin} and {@code selectedAmount}
     * are null/0 for a bonus row that has no line selections at all.
     */
    public record BasisLineRow(String bonusUuid, double computedAmount, String invoiceUuid,
                               LocalDate invoiceDate, String invoiceCompanyUuid,
                               String consultantUuid, String origin, double selectedAmount) {}

    /**
     * The partner's still-fundable sales basis attributed per company, keyed by company UUID.
     *
     * <p>Scope is exactly the sales-bonus basis: APPROVED rows with {@code payout_uuid IS NULL}, bucketed
     * into the window by work period ({@link #WP_DATE_SQL}) and excluding invoices fully reversed by a
     * live credit note ({@link #NOT_FULLY_CREDITED_SQL}) — the same rows {@link #findApprovedInvoiceIdsForUsers}
     * and {@link #sumApprovedUnconsumed} count, so the values sum to the partner's un-consumed approved
     * total.</p>
     *
     * <p>Attribution per bonus row ({@link PartnerBonusCompanySplitMath#attributeBonus}): each selected
     * BASE consultant line follows the consultant's company at invoice date — the same resolution as the
     * 0/80/100% line defaults and the approval-grid badges (unresolvable → the invoice's issuing company).
     * The row's {@code computedAmount}, which already carries fee/discount lines without a consultant,
     * CALCULATED adjustments, the invoice discount and the credit-note sign, is spread pro-rata over those
     * company amounts. A row with no consultant line goes entirely to the issuing company.</p>
     */
    public Map<String, Double> approvedUnconsumedBasisByCompany(String userUuid, LocalDate from, LocalDate to) {
        Map<String, Double> byCompany = new LinkedHashMap<>();
        if (userUuid == null || userUuid.isBlank()) return byCompany;
        List<BasisLineRow> rows = findApprovedUnconsumedBasisLines(userUuid, from, to);
        if (rows == null || rows.isEmpty()) return byCompany;

        Map<String, List<BasisLineRow>> byBonus = new LinkedHashMap<>();
        for (BasisLineRow r : rows) byBonus.computeIfAbsent(r.bonusUuid(), k -> new ArrayList<>()).add(r);

        Map<String, Map<LocalDate, UserStatus>> statusCache = new HashMap<>();
        for (List<BasisLineRow> bonusRows : byBonus.values()) {
            BasisLineRow head = bonusRows.get(0);
            LocalDate date = head.invoiceDate() != null ? head.invoiceDate() : LocalDate.now();
            String issuingCompany = head.invoiceCompanyUuid() != null && !head.invoiceCompanyUuid().isBlank()
                    ? head.invoiceCompanyUuid() : UNKNOWN_COMPANY_UUID;

            Map<String, Double> consultantSelected = new LinkedHashMap<>();
            for (BasisLineRow r : bonusRows) {
                if (r.consultantUuid() == null || r.consultantUuid().isBlank()) continue;   // fee/discount line
                if (InvoiceItemOrigin.CALCULATED.name().equals(r.origin())) continue;        // pro-rated via computedAmount
                if (r.selectedAmount() == 0.0) continue;                                     // 0% (own production) or empty
                String company = companyUuidAt(r.consultantUuid(), date, statusCache);
                consultantSelected.merge(company != null ? company : issuingCompany, r.selectedAmount(), Double::sum);
            }
            PartnerBonusCompanySplitMath.attributeBonus(head.computedAmount(), consultantSelected, issuingCompany)
                    .forEach((company, amount) -> byCompany.merge(company, amount, Double::sum));
        }
        return byCompany;
    }

    /** The user's company UUID at {@code date} (null if unresolvable), resolved like the approval-grid badges. */
    public String companyUuidAt(String userUuid, LocalDate date) {
        return companyUuidAt(userUuid, date, new HashMap<>());
    }

    /**
     * Hook for tests: loads a partner's APPROVED, un-consumed basis lines — one row per selected invoice
     * line (LEFT JOIN, so a bonus row without line selections still yields one row with no item).
     */
    protected List<BasisLineRow> findApprovedUnconsumedBasisLines(String userUuid, LocalDate from, LocalDate to) {
        String sql = "SELECT b.uuid, b.computed_amount, i.uuid, i.invoicedate, i.companyuuid,"
                + " ii.consultantuuid, ii.origin,"
                + " COALESCE(ii.hours * ii.rate * LEAST(GREATEST(l.percentage, 0), 100) / 100, 0)"
                + " FROM invoice_bonuses b"
                + " JOIN invoices i ON i.uuid = b.invoiceuuid"
                + " LEFT JOIN invoice_bonus_lines l ON l.bonusuuid = b.uuid"
                + " LEFT JOIN invoiceitems ii ON ii.uuid = l.invoiceitemuuid"
                + " WHERE b.useruuid = :user"
                + "   AND b.status = :approved"
                + "   AND b.payout_uuid IS NULL"
                + "   AND " + WP_DATE_SQL + " >= :from"
                + "   AND " + WP_DATE_SQL + " <= :to"
                + NOT_FULLY_CREDITED_SQL
                + " ORDER BY b.uuid";
        List<?> raw = Panache.getEntityManager().createNativeQuery(sql)
                .setParameter("user", userUuid)
                .setParameter("approved", SalesApprovalStatus.APPROVED.name())
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        List<BasisLineRow> rows = new ArrayList<>(raw.size());
        for (Object o : raw) {
            Object[] r = (Object[]) o;
            rows.add(new BasisLineRow(
                    asString(r[0]), asDouble(r[1]), asString(r[2]), asLocalDate(r[3]), asString(r[4]),
                    asString(r[5]), asString(r[6]), asDouble(r[7])));
        }
        return rows;
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }

    private static double asDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }

    private static LocalDate asLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.time.LocalDateTime ldt) return ldt.toLocalDate();
        String s = String.valueOf(o);
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    /**
     * Stamp the APPROVED, not-yet-consumed bonus rows belonging to {@code users} on the given invoices
     * with a payout UUID. Scoped to the group's members so a payout consumes only its own members' rows
     * on a shared invoice. Idempotent: rows already stamped (payoutUuid IS NOT NULL) are left untouched.
     * Must run inside the payout transaction. Returns the number of rows stamped.
     */
    @Transactional
    public int markApprovedConsumed(Collection<String> invoiceIds, String payoutUuid, Set<String> users) {
        if (invoiceIds == null || invoiceIds.isEmpty() || payoutUuid == null || users == null || users.isEmpty()) {
            return 0;
        }
        return InvoiceBonus.update(
                "payoutUuid = ?1 where status = ?2 and payoutUuid is null and invoiceuuid in ?3 and useruuid in ?4",
                payoutUuid, SalesApprovalStatus.APPROVED, invoiceIds, users);
    }

    @Transactional
    public InvoiceBonus addSelfAssign(String invoiceuuid, String useruuid,
                                      InvoiceBonus.ShareType type, double value, String note) {
        log.debugf("addSelfAssign: invoiceUuid=%s, userUuid=%s, shareType=%s, shareValue=%.2f",
                invoiceuuid, useruuid, type, value);
        assertEligibleForInvoice(invoiceuuid, useruuid);
        return addInternal(invoiceuuid, useruuid, useruuid, type, value, note);
    }

    @Transactional
    public InvoiceBonus addAdmin(String invoiceuuid, String targetUseruuid, String addedBy,
                                 InvoiceBonus.ShareType type, double value, String note) {
        log.debugf("addAdmin: invoiceUuid=%s, targetUserUuid=%s, addedBy=%s, shareType=%s, shareValue=%.2f",
                invoiceuuid, targetUseruuid, addedBy, type, value);
        return addInternal(invoiceuuid, targetUseruuid, addedBy, type, value, note);
    }

    private InvoiceBonus addInternal(String invoiceuuid, String useruuid, String addedBy,
                                     InvoiceBonus.ShareType type, double value, String note) {
        if (InvoiceBonus.count("invoiceuuid = ?1 and useruuid = ?2", invoiceuuid, useruuid) > 0) {
            log.warnf("Duplicate bonus attempt: invoiceUuid=%s, userUuid=%s already exists", invoiceuuid, useruuid);
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("User already added for invoice bonus").build());
        }
        Invoice inv = Invoice.findById(invoiceuuid);
        if (inv == null) {
            log.warnf("Bonus add failed: invoiceUuid=%s not found", invoiceuuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        InvoiceBonus ib = new InvoiceBonus();
        ib.setInvoiceuuid(invoiceuuid);
        ib.setUseruuid(useruuid);
        ib.setAddedBy(addedBy);
        ib.setShareType(type);
        ib.setShareValue(value);
        ib.setOverrideNote(note);
        recomputeComputedAmount(inv, ib);
        ib.persist();
        log.infof("Bonus created: bonusUuid=%s, invoiceUuid=%s, userUuid=%s, addedBy=%s, shareType=%s, shareValue=%.2f, computedAmount=%.2f",
                ib.getUuid(), invoiceuuid, useruuid, addedBy, type, value, ib.getComputedAmount());

        if (type == InvoiceBonus.ShareType.PERCENT) {
            double sumPct = InvoiceBonus.<InvoiceBonus>stream("invoiceuuid = ?1 and shareType = ?2",
                            invoiceuuid, InvoiceBonus.ShareType.PERCENT)
                    .mapToDouble(InvoiceBonus::getShareValue).sum();
            if (sumPct > 100.0 + 1e-9) {
                log.warnf("Percent share sum exceeds 100%%: invoiceUuid=%s, totalPct=%.2f", invoiceuuid, sumPct);
                throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                        .entity("Sum of percent shares exceeds 100%").build());
            }
        }
        return ib;
    }

    @Transactional
    public void updateShare(String bonusUuid, InvoiceBonus.ShareType type, double value, String note) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) {
            log.warnf("Update share failed: bonusUuid=%s not found", bonusUuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        log.infof("Updating bonus share: bonusUuid=%s, oldShareType=%s, oldShareValue=%.2f, newShareType=%s, newShareValue=%.2f",
                bonusUuid, ib.getShareType(), ib.getShareValue(), type, value);
        ib.setShareType(type);
        ib.setShareValue(value);
        ib.setOverrideNote(note);
        Invoice inv = Invoice.findById(ib.getInvoiceuuid());
        recomputeComputedAmount(inv, ib);
        ib.persist();
        log.infof("Bonus share updated: bonusUuid=%s, computedAmount=%.2f", bonusUuid, ib.getComputedAmount());
    }

    @Transactional
    public void approve(String bonusUuid, String approverUuid) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) {
            log.warnf("Approval failed: bonusUuid=%s not found", bonusUuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        log.infof("Approving bonus: bonusUuid=%s, invoiceUuid=%s, userUuid=%s, approverUuid=%s",
                bonusUuid, ib.getInvoiceuuid(), ib.getUseruuid(), approverUuid);

        // Seed defaults once (bulk)
        ensureDefaultLinesIfEmptyBulk(ib);

        // Status + approver
        setStatus(bonusUuid, approverUuid, SalesApprovalStatus.APPROVED);

        // Recompute using current line set
        Invoice inv = Invoice.findById(ib.getInvoiceuuid());
        List<InvoiceBonusLine> lines = InvoiceBonusLine.list("bonusuuid = ?1", ib.getUuid());
        if (!lines.isEmpty()) {
            double amount = computeAmountFromLines(inv, lines);
            ib.setShareType(InvoiceBonus.ShareType.AMOUNT);
            ib.setShareValue(amount);
            ib.setComputedAmount(amount);
            ib.persist();
            log.infof("Bonus approved (from lines): bonusUuid=%s, invoiceUuid=%s, approvedBy=%s, computedAmount=%.2f, lineCount=%d",
                    bonusUuid, ib.getInvoiceuuid(), approverUuid, amount, lines.size());
        } else {
            recomputeComputedAmount(inv, ib);
            ib.persist();
            log.infof("Bonus approved (legacy calc): bonusUuid=%s, invoiceUuid=%s, approvedBy=%s, computedAmount=%.2f",
                    bonusUuid, ib.getInvoiceuuid(), approverUuid, ib.getComputedAmount());
        }

        // Fire cache invalidation event
        fireCacheInvalidation(inv);
    }

    /** Default selection at approve-time if none exists (bulk persist), per business rule:
     *   own production -> 0%; cross-company consultant -> 80%; same-company consultant -> 100%.
     *   Companies are resolved at invoice date (same source as the approval-grid company badges).
     */
    private void ensureDefaultLinesIfEmptyBulk(InvoiceBonus ib) {
        if (InvoiceBonusLine.count("bonusuuid", ib.getUuid()) > 0) return;
        Invoice inv = Invoice.findById(ib.getInvoiceuuid());
        if (inv == null || inv.getInvoiceitems() == null) return;

        LocalDate date = inv.getInvoicedate() != null ? inv.getInvoicedate() : LocalDate.now();
        Map<String, Map<LocalDate, UserStatus>> statusCache = new HashMap<>();
        String recipientCompany = companyUuidAt(ib.getUseruuid(), date, statusCache);

        List<InvoiceBonusLine> toPersist = new ArrayList<>();
        for (InvoiceItem ii : inv.getInvoiceitems()) {
            if (ii.getOrigin() == InvoiceItemOrigin.CALCULATED) continue;
            boolean hasConsultant = ii.getConsultantuuid() != null && !ii.getConsultantuuid().isBlank();
            boolean editable = ii.getRate() >= 0 && hasConsultant;
            double pct = lineDefault(ii, editable, ib.getUseruuid(), recipientCompany, date, statusCache).percentage();
            InvoiceBonusLine l = new InvoiceBonusLine();
            l.setBonusuuid(ib.getUuid());
            l.setInvoiceuuid(inv.getUuid());
            l.setInvoiceitemuuid(ii.getUuid());
            l.setPercentage(pct);
            toPersist.add(l);
        }
        if (!toPersist.isEmpty()) {
            InvoiceBonusLine.persist(toPersist);
        }
    }

    @Transactional
    public void reject(String bonusUuid, String approverUuid, String note) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) {
            log.warnf("Rejection failed: bonusUuid=%s not found", bonusUuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        log.infof("Rejecting bonus: bonusUuid=%s, invoiceUuid=%s, userUuid=%s, rejectedBy=%s, note=%s",
                bonusUuid, ib.getInvoiceuuid(), ib.getUseruuid(), approverUuid, note);
        ib.setOverrideNote(note);
        setStatus(bonusUuid, approverUuid, SalesApprovalStatus.REJECTED);
        log.infof("Bonus rejected: bonusUuid=%s, invoiceUuid=%s, rejectedBy=%s",
                bonusUuid, ib.getInvoiceuuid(), approverUuid);

        // Fire cache invalidation event
        Invoice inv = Invoice.findById(ib.getInvoiceuuid());
        fireCacheInvalidation(inv);
    }

    private void setStatus(String bonusUuid, String approver, SalesApprovalStatus status) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) {
            log.warnf("setStatus failed: bonusUuid=%s not found", bonusUuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        String approverUuid = resolveUserUuid(approver);
        if (approverUuid == null) {
            log.warnf("setStatus failed: invalid approver identity '%s' for bonusUuid=%s", approver, bonusUuid);
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("Approver must be a valid user UUID or username").build());
        }

        ib.setStatus(status);
        ib.setApprovedBy(approverUuid);
        ib.setApprovedAt(java.time.LocalDateTime.now());
        ib.persist();
        log.debugf("Bonus status set: bonusUuid=%s, status=%s, approverUuid=%s", bonusUuid, status, approverUuid);
    }

    private String resolveUserUuid(String input) {
        if (input == null || input.isBlank()) return null;
        // If it's a UUID and exists as a user, accept it
        if (looksLikeUuid(input)) {
            if (User.findById(input) != null) return input;
        }
        // Otherwise, try resolve as username
        return User.findByUsername(input).map(u -> u.uuid).orElse(null);
    }

    private boolean looksLikeUuid(String s) {
        try {
            java.util.UUID.fromString(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public void delete(String bonusUuid) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) {
            log.warnf("Delete failed: bonusUuid=%s not found", bonusUuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        log.infof("Deleting bonus: bonusUuid=%s, invoiceUuid=%s, userUuid=%s, status=%s",
                bonusUuid, ib.getInvoiceuuid(), ib.getUseruuid(), ib.getStatus());
        Panache.getEntityManager().remove(ib);
        long deletedLines = InvoiceBonusLine.delete("bonusuuid", bonusUuid);
        log.infof("Bonus deleted: bonusUuid=%s, deletedLines=%d", bonusUuid, deletedLines);
    }

    public List<InvoiceBonusLine> listLines(String bonusuuid) {
        return InvoiceBonusLine.list("bonusuuid = ?1", bonusuuid);
    }

    /**
     * Returns one enriched, display-ready line per invoice item — not only persisted lines — so the
     * inline approval panel can render the full proposed allocation even when the bonus was never edited.
     *
     * <p>When a saved {@link InvoiceBonusLine} exists for an item, {@code percentage} is the saved value;
     * otherwise it is the smart default ({@code defaultPercentage}). Each line also carries the
     * consultant's name and their company (abbreviation + uuid) resolved at invoice date — the same
     * source used for the approval-grid company badges — plus the rule {@code reason}.</p>
     */
    public List<EnrichedBonusLineDTO> listEnrichedLines(String invoiceuuid, String bonusuuid) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusuuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        if (!Objects.equals(ib.getInvoiceuuid(), invoiceuuid)) {
            log.warnf("listEnrichedLines failed: bonusUuid=%s belongs to invoiceUuid=%s, not %s",
                    bonusuuid, ib.getInvoiceuuid(), invoiceuuid);
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("bonusuuid does not belong to invoiceuuid").build());
        }

        Invoice inv = Invoice.findById(ib.getInvoiceuuid());
        if (inv == null) throw new WebApplicationException(Response.Status.NOT_FOUND);

        List<InvoiceItem> items = (inv.getInvoiceitems() == null) ? List.of() : inv.getInvoiceitems();

        // Saved selections keyed by invoice item (empty for never-edited bonuses).
        Map<String, InvoiceBonusLine> savedByItem = listLines(bonusuuid).stream()
                .collect(Collectors.toMap(InvoiceBonusLine::getInvoiceitemuuid,
                        java.util.function.Function.identity(), (a, b) -> a));

        double discountPct = Optional.ofNullable(inv.getDiscount()).orElse(0.0);
        double discountFactor = 1.0 - (discountPct / 100.0);

        LocalDate date = inv.getInvoicedate() != null ? inv.getInvoicedate() : LocalDate.now();
        Map<String, Map<LocalDate, UserStatus>> statusCache = new HashMap<>();
        Map<String, User> userByUuid = new HashMap<>();
        String recipientCompany = companyUuidAt(ib.getUseruuid(), date, statusCache);

        List<EnrichedBonusLineDTO> result = new ArrayList<>(items.size());
        for (InvoiceItem item : items) {
            double lineAmount = round2(item.rate * item.hours);
            String origin = item.origin != null ? item.origin.name() : "BASE";

            boolean isCalculated = item.origin == InvoiceItemOrigin.CALCULATED;
            boolean isNegativeRate = item.rate < 0;
            boolean hasNoConsultant = item.consultantuuid == null || item.consultantuuid.isBlank();
            boolean editable = !isCalculated && !isNegativeRate && !hasNoConsultant;

            LineDefault def = lineDefault(item, editable, ib.getUseruuid(), recipientCompany, date, statusCache);

            // Effective % = saved value if present, else the same default the seeding would persist
            // (editable: rule value; non-editable BASE: 100, or 0 for a negative-rate own line; CALCULATED: unused).
            InvoiceBonusLine saved = savedByItem.get(item.uuid);
            double percentage;
            if (saved != null) {
                percentage = saved.getPercentage();
            } else if (isCalculated) {
                percentage = 0.0;     // CALCULATED items are allocated pro-rata; percentage is unused
            } else {
                percentage = def.percentage();
            }

            // estimatedShare = lineAmount * discountFactor * (pct/100), negated for credit notes
            double estimatedShare = 0.0;
            if (!isCalculated && percentage > 0) {
                estimatedShare = lineAmount * discountFactor * (percentage / 100.0);
                if (inv.getType() == CREDIT_NOTE) estimatedShare = -estimatedShare;
                estimatedShare = round2(estimatedShare);
            }

            // Consultant + company display (resolved at invoice date).
            String consultantName = null;
            String companyUuid = null;
            String companyAbbreviation = null;
            if (!hasNoConsultant) {
                User cu = getUserCached(item.consultantuuid, userByUuid);
                if (cu != null) {
                    consultantName = (cu.getFirstname() + " " + cu.getLastname()).trim();
                }
                UserStatus st = getUserStatusCached(item.consultantuuid, date, statusCache);
                if (st != null && st.getCompany() != null) {
                    companyUuid = st.getCompany().getUuid();
                    companyAbbreviation = st.getCompany().getAbbreviation();
                }
            }

            result.add(new EnrichedBonusLineDTO(
                    saved != null ? saved.getUuid() : null,
                    item.uuid,
                    item.itemname,
                    item.description,
                    item.consultantuuid,
                    consultantName,
                    companyUuid,
                    companyAbbreviation,
                    item.rate,
                    item.hours,
                    lineAmount,
                    percentage,
                    isCalculated ? 0.0 : def.percentage(),
                    def.reason(),
                    estimatedShare,
                    editable,
                    origin
            ));
        }
        return result;
    }

    private void fireCacheInvalidation(Invoice inv) {
        if (inv != null && inv.getInvoicedate() != null) {
            int fy = fiscalYearOf(inv.getInvoicedate());
            cacheInvalidation.fire(new BonusCacheInvalidationEvent(fy));
        }
    }

    @Transactional
    public void putLines(String invoiceuuid, String bonusuuid, List<InvoiceBonusLine> lines) {
        log.debugf("putLines: invoiceUuid=%s, bonusUuid=%s, lineCount=%d",
                invoiceuuid, bonusuuid, lines == null ? 0 : lines.size());
        InvoiceBonus ib = InvoiceBonus.findById(bonusuuid);
        if (ib == null) {
            log.warnf("putLines failed: bonusUuid=%s not found", bonusuuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        if (!Objects.equals(ib.getInvoiceuuid(), invoiceuuid)) {
            log.warnf("putLines failed: bonusUuid=%s belongs to invoiceUuid=%s, not %s",
                    bonusuuid, ib.getInvoiceuuid(), invoiceuuid);
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("bonusuuid does not belong to invoiceuuid").build());
        }

        // Sanitize input and compute from the submitted set (no extra read).
        // Drop any line whose invoiceitemuuid is not an item of this invoice (data-integrity guard).
        Invoice inv = Invoice.findById(invoiceuuid);
        Set<String> validItemUuids = (inv == null || inv.getInvoiceitems() == null) ? Set.of()
                : inv.getInvoiceitems().stream().map(InvoiceItem::getUuid).collect(Collectors.toSet());
        List<InvoiceBonusLine> sanitized = (lines == null ? List.<InvoiceBonusLine>of() :
                lines.stream()
                        .filter(l -> validItemUuids.contains(l.getInvoiceitemuuid()))
                        .map(l -> {
                            InvoiceBonusLine x = new InvoiceBonusLine();
                            x.setInvoiceitemuuid(l.getInvoiceitemuuid());
                            x.setPercentage(sanitizePct(l.getPercentage()));
                            return x;
                        }).toList());

        double amount;
        if (!sanitized.isEmpty()) {
            amount = computeAmountFromLines(inv, sanitized);
            ib.setShareType(InvoiceBonus.ShareType.AMOUNT);
            ib.setShareValue(amount);
            ib.setComputedAmount(amount);
        } else {
            recomputeComputedAmount(inv, ib); // fallback (no lines)
        }
        ib.persist();

        // Replace existing lines in bulk
        InvoiceBonusLine.delete("bonusuuid = ?1", bonusuuid);
        if (!sanitized.isEmpty()) {
            for (InvoiceBonusLine l : sanitized) {
                l.setBonusuuid(bonusuuid);
                l.setInvoiceuuid(invoiceuuid);
            }
            InvoiceBonusLine.persist(sanitized);
        }
    }

    private static double sanitizePct(double pct) {
        if (Double.isNaN(pct) || Double.isInfinite(pct)) return 0.0;
        if (pct < 0) return 0.0;
        if (pct > 100) return 100.0;
        return pct;
    }

    /** Sum selected BASE items and include global adjustments.
     * If CALCULATED lines exist: allocate them pro‑rata to the selected BASE share.
     * Else: apply invoice.discount% directly to the selected BASE sum.
     * Credit notes keep negative sign.
     */
    private static double computeAmountFromLines(Invoice inv, List<InvoiceBonusLine> lines) {
        if (inv == null || inv.getInvoiceitems() == null || inv.getInvoiceitems().isEmpty()) return 0.0;

        Map<String, InvoiceItem> byId = inv.getInvoiceitems().stream()
                .collect(Collectors.toMap(InvoiceItem::getUuid, Function.identity(), (a,b)->a));

        double baseTotal = inv.getInvoiceitems().stream()
                .filter(ii -> ii.getOrigin() != InvoiceItemOrigin.CALCULATED)
                .mapToDouble(ii -> ii.getHours() * ii.getRate())
                .sum();

        double baseSelected = 0.0;
        for (InvoiceBonusLine sel : lines) {
            InvoiceItem ii = byId.get(sel.getInvoiceitemuuid());
            if (ii == null || ii.getOrigin() == InvoiceItemOrigin.CALCULATED) continue;
            baseSelected += (ii.getHours() * ii.getRate()) * (sanitizePct(sel.getPercentage())/100.0);
        }

        double syntheticTotal = inv.getInvoiceitems().stream()
                .filter(ii -> ii.getOrigin() == InvoiceItemOrigin.CALCULATED)
                .mapToDouble(ii -> ii.getHours() * ii.getRate())
                .sum();

        double amount;
        if (Math.abs(syntheticTotal) > 1e-9) {
            double ratio = baseTotal == 0.0 ? 0.0 : (baseSelected / baseTotal);
            amount = baseSelected + ratio * syntheticTotal;
        } else {
            double discountPct = Optional.ofNullable(inv.getDiscount()).orElse(0.0);
            amount = baseSelected * (1.0 - discountPct/100.0);
        }

        if (inv.getType() == CREDIT_NOTE) amount = -amount;
        return round2(amount);
    }

    /** Legacy computation when no line selections exist. */
    private static void recomputeComputedAmount(Invoice inv, InvoiceBonus ib) {
        double base = 0.0;
        if (inv != null) {
            if (inv.getSumAfterDiscounts() != null) base = inv.getSumAfterDiscounts();
            else base = inv.getSumNoTax(); // adjust if your entity differs
            if (inv.getType() == CREDIT_NOTE) base = -base;
        }
        double computed = switch (ib.getShareType()) {
            case PERCENT -> base * (ib.getShareValue() / 100.0);
            case AMOUNT  -> ib.getShareValue();
        };
        ib.setComputedAmount(round2(computed));
    }

    /**
     * Recalc all bonuses for an invoice, resolving the {@link Invoice} by uuid.
     *
     * <p><b>Prefer {@link #recalcForInvoice(Invoice)} whenever the caller already holds the
     * entity.</b> Re-reading by uuid is not hazard-free: a caller whose transaction opened
     * <em>before</em> the invoice row was committed by a nested {@code REQUIRES_NEW}
     * transaction cannot see that row at all, because MariaDB's default REPEATABLE READ pins
     * the transaction's consistent-read snapshot at its first read. That is exactly what
     * happened on the draft-creation path — {@code InvoiceGenerator.createDraftInvoiceFromProject}
     * (tx T1) → {@code InvoiceService.createDraftInvoice} ({@code REQUIRES_NEW}, tx T2, commits)
     * → {@code InvoiceService.updateDraftInvoice} (joins T1) — where this lookup returned null
     * on every single draft creation and the recalculation was skipped in silence.
     *
     * <p>The bulk {@code Invoice.update(...)} in {@code updateDraftInvoice} is unaffected by the
     * same snapshot because DML performs a current read, which is why the skip stayed invisible:
     * the save itself worked, only the bonus recomputation was dropped.
     */
    @Transactional
    public void recalcForInvoice(String invoiceuuid) {
        log.debugf("recalcForInvoice: invoiceUuid=%s", invoiceuuid);
        Invoice inv = findInvoiceById(invoiceuuid);
        if (inv == null) {
            // invoice_bonuses.invoiceuuid is FK-constrained to invoices.uuid (fk_invbonus_invoice),
            // so an invoice that is genuinely absent provably has no bonus rows and there is nothing
            // to recompute. If rows DO come back, the invoice is invisible rather than absent and
            // skipping would strand real money on a stale computedAmount — fail loudly instead.
            List<InvoiceBonus> stranded = findByInvoice(invoiceuuid);
            if (!stranded.isEmpty()) {
                throw new IllegalStateException(
                        "recalcForInvoice: invoice " + invoiceuuid + " is not resolvable but "
                        + stranded.size() + " bonus row(s) reference it; refusing to silently skip "
                        + "recalculation of computedAmount. The caller must pass the already-loaded "
                        + "Invoice via recalcForInvoice(Invoice) from its own transaction.");
            }
            // Still ERROR, not WARN: a money recalculation that did not happen must be visible in
            // the logs even when it is provably a no-op, because it always means a caller reached
            // this method with a uuid its own transaction cannot resolve.
            log.errorf("recalcForInvoice skipped: invoiceUuid=%s not resolvable in this transaction; "
                    + "0 bonus rows reference it, so no computedAmount is stale. Caller should pass "
                    + "the loaded Invoice entity instead of re-reading by uuid.", invoiceuuid);
            return;
        }
        recalcForInvoice(inv);
    }

    /**
     * Recalc all bonuses for an already-loaded invoice (honors line selections).
     *
     * <p>This is the preferred entry point. Beyond side-stepping the snapshot hazard described on
     * {@link #recalcForInvoice(String)}, it computes from a strictly better basis: callers invoke
     * this immediately after {@code InvoiceItemRecalculator.recalculateInvoiceItems(invoice)},
     * which rebuilds {@code invoice.invoiceitems} and populates the {@code @Transient}
     * {@code sumBeforeDiscounts}/{@code sumAfterDiscounts}/{@code grandTotal} fields in place.
     * Those fields are never loaded by {@code Invoice.findById}, so the re-read path always fell
     * back to {@link Invoice#getSumNoTax()}. The two agree by construction — every pricing delta
     * is emitted as a synthetic invoice line — but only the passed entity carries the values the
     * pricing engine actually computed.
     */
    @Transactional
    public void recalcForInvoice(Invoice inv) {
        Objects.requireNonNull(inv, "recalcForInvoice requires a non-null Invoice");
        String invoiceuuid = inv.getUuid();
        List<InvoiceBonus> list = findByInvoice(invoiceuuid);
        log.debugf("recalcForInvoice: invoiceUuid=%s, bonusCount=%d", invoiceuuid, list.size());
        for (InvoiceBonus ib : list) {
            List<InvoiceBonusLine> lines = findLinesByBonus(ib.getUuid());
            if (!lines.isEmpty()) {
                double amount = computeAmountFromLines(inv, lines);
                ib.setShareType(InvoiceBonus.ShareType.AMOUNT);
                ib.setShareValue(amount);
                ib.setComputedAmount(amount);
            } else {
                recomputeComputedAmount(inv, ib);
            }
            ib.persist();
        }
    }

    /** Hook for tests: resolves an invoice via Panache. */
    protected Invoice findInvoiceById(String invoiceuuid) {
        return Invoice.findById(invoiceuuid);
    }

    /** Hook for tests: loads a bonus's line selections via Panache. */
    protected List<InvoiceBonusLine> findLinesByBonus(String bonusuuid) {
        return InvoiceBonusLine.list("bonusuuid = ?1", bonusuuid);
    }

    private static int fiscalYearOf(LocalDate date) {
        LocalDate fyStart = DateUtils.getFiscalStartDateBasedOnDate(date);
        return fyStart.getYear();
    }

    private static String fiscalYearLabel(int fyStartYear) {
        return fyStartYear + "/" + (fyStartYear + 1);
    }

    private static void assertEligibleForInvoice(String invoiceuuid, String useruuid) {
        Invoice inv = Invoice.findById(invoiceuuid);
        if (inv == null) {
            log.warnf("Eligibility check failed: invoiceUuid=%s not found", invoiceuuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        int fy = DateUtils.workPeriodFiscalYearStartYear(inv.getYear(), inv.getMonth(), inv.getInvoicedate());
        BonusEligibility be = BonusEligibility.find("useruuid = ?1 and financialYear = ?2", useruuid, fy).firstResult();
        if (be == null) {
            String msg = "User not eligible to self-assign for FY " + fiscalYearLabel(fy);
            log.warnf("Eligibility check failed: userUuid=%s not eligible for FY %s, invoiceUuid=%s",
                    useruuid, fiscalYearLabel(fy), invoiceuuid);
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN).entity(msg).build());
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Default percentage + reason for a single invoice line. */
    public record LineDefault(double percentage, String reason) {}

    /** Resolve a user's company UUID at a date (null if unresolved), via the status cache. Hook for tests. */
    protected String companyUuidAt(String userUuid, LocalDate date,
                                   Map<String, Map<LocalDate, UserStatus>> cache) {
        UserStatus st = getUserStatusCached(userUuid, date, cache);
        return (st != null && st.getCompany() != null) ? st.getCompany().getUuid() : null;
    }

    /**
     * Smart default percentage for a BASE invoice line, resolved at invoice date:
     * <ul>
     *   <li>consultant == bonus recipient → 0% (OWN — no bonus on own production)</li>
     *   <li>non-editable BASE line (fee / no consultant) → 100% (NOT_APPLICABLE)</li>
     *   <li>consultant company ≠ recipient company → 80% (CROSS_COMPANY)</li>
     *   <li>same company, or company unresolved → 100% (SAME_COMPANY)</li>
     * </ul>
     * Company comparison uses {@link #getUserStatusCached} — the same source as the approval-grid badges.
     */
    private LineDefault lineDefault(InvoiceItem item, boolean editable, String recipientUuid,
                                    String recipientCompanyUuid, LocalDate date,
                                    Map<String, Map<LocalDate, UserStatus>> cache) {
        if (Objects.equals(item.consultantuuid, recipientUuid)) {
            return new LineDefault(0.0, "OWN");
        }
        if (!editable) {
            return new LineDefault(100.0, "NOT_APPLICABLE");
        }
        String consultantCompany = companyUuidAt(item.consultantuuid, date, cache);
        if (recipientCompanyUuid != null && consultantCompany != null
                && !recipientCompanyUuid.equals(consultantCompany)) {
            return new LineDefault(80.0, "CROSS_COMPANY");
        }
        return new LineDefault(100.0, "SAME_COMPANY");
    }


    public List<BonusEligibility> listEligibility() {
        return BonusEligibility.listAll();
    }

    public List<BonusEligibility> listEligibility(String useruuid, Integer financialYear) {
        if (useruuid != null && financialYear != null) {
            return BonusEligibility.list("useruuid = ?1 and financialYear = ?2", useruuid, financialYear);
        } else if (useruuid != null) {
            return BonusEligibility.list("useruuid", useruuid);
        } else if (financialYear != null) {
            return BonusEligibility.list("financialYear", financialYear);
        } else {
            return listEligibility();
        }
    }

    @Transactional
    public BonusEligibility upsertEligibility(String useruuid,
                                             boolean canSelfAssign,
                                             String groupUuid) {
        log.debugf("upsertEligibility: userUuid=%s, canSelfAssign=%s, groupUuid=%s", useruuid, canSelfAssign, groupUuid);
        if (groupUuid == null || groupUuid.isBlank()) {
            log.warnf("upsertEligibility failed: groupUuid is required for userUuid=%s", useruuid);
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("groupUuid is required").build());
        }
        BonusEligibilityGroup desiredGroup = BonusEligibilityGroup.find("uuid", groupUuid).firstResult();
        if (desiredGroup == null) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("groupUuid does not exist: " + groupUuid).build());
        }
        int fy = desiredGroup.getFinancialYear();

        BonusEligibility be = BonusEligibility.find("useruuid = ?1 and financialYear = ?2", useruuid, fy).firstResult();
        if (be == null) {
            be = new BonusEligibility();
            be.setUseruuid(useruuid);
            be.setFinancialYear(fy);
        }

        be.setGroup(desiredGroup);
        be.setCanSelfAssign(canSelfAssign);
        be.persist();
        return be;
    }

    @Transactional
    public void deleteEligibilityByUseruuid(String useruuid) {
        log.infof("Deleting eligibility for userUuid=%s", useruuid);
        long deleted = BonusEligibility.delete("useruuid", useruuid);
        if (deleted == 0) {
            log.warnf("Delete eligibility failed: no eligibility found for userUuid=%s", useruuid);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        log.infof("Eligibility deleted: userUuid=%s, deletedCount=%d", useruuid, deleted);
    }

    /**
     * Gets the full name of a user by their UUID.
     *
     * @param userId The user UUID
     * @return Full name (firstname + lastname) or "Unknown User" if not found
     */
    public String getUserFullName(String userId) {
        User user = User.findById(userId);
        if (user == null) {
            return "Unknown User";
        }
        return user.getFirstname() + " " + user.getLastname();
    }

    /**
     * Helper method to get a user with caching to avoid repeated queries.
     *
     * <p>{@code containsKey}/{@code put} rather than {@code computeIfAbsent}: the latter
     * treats a null <em>value</em> as an absent <em>key</em>, so a consultant uuid that
     * resolves to nothing — a deleted or purged user — would leave no entry and be
     * re-queried once per invoice line referencing it instead of once overall. A cached
     * null here is a resolved negative; the caller already handles it by leaving the
     * consultant name blank.</p>
     *
     * @param userUuid The user UUID
     * @param cache The per-call cache map to use
     * @return the User, or null if there is none
     */
    private User getUserCached(String userUuid, Map<String, User> cache) {
        if (cache.containsKey(userUuid)) {
            return cache.get(userUuid);
        }
        // Direct static call, not User::findById — a method reference binds to the
        // un-enhanced PanacheEntityBase stub and throws at runtime (only direct call
        // sites are rewritten by Quarkus build-time enhancement).
        User user = User.findById(userUuid);
        cache.put(userUuid, user);
        return user;
    }

    /**
     * Helper method to get user status with caching to avoid repeated queries.
     *
     * @param userId The user UUID
     * @param date The date to get status for
     * @param cache The cache map to use
     * @return UserStatus at the given date, or null if not found
     */
    private UserStatus getUserStatusCached(String userId,
                                           LocalDate date,
                                           Map<String, Map<LocalDate, UserStatus>> cache) {
        // If no consultant is associated, there is no status to resolve
        if (userId == null || userId.isBlank()) {
            return null;
        }

        // Check cache first
        Map<LocalDate, UserStatus> userCache = cache.get(userId);
        if (userCache != null && userCache.containsKey(date)) {
            return userCache.get(date);
        }

        // Load user and get status
        User user = User.findById(userId);
        if (user == null) {
            // Cache null result
            cache.computeIfAbsent(userId, k -> new HashMap<>()).put(date, null);
            return null;
        }

        // Load user statuses if not already loaded
        if (user.getStatuses() == null || user.getStatuses().isEmpty()) {
            List<UserStatus> statuses = UserStatus.findByUseruuid(userId);
            user.setStatuses(statuses);
        }

        UserStatus status = user.getUserStatus(date);

        // Cache the result
        cache.computeIfAbsent(userId, k -> new HashMap<>()).put(date, status);

        return status;
    }
}
