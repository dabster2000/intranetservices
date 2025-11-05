// src/main/java/dk/trustworks/intranet/aggregates/invoice/bonus/services/InvoiceBonusService.java
package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibilityGroup;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus.ShareType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonusLine;
import dk.trustworks.intranet.aggregates.invoice.bonus.resources.BonusAggregateResource;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import dk.trustworks.intranet.utils.DateUtils;

@ApplicationScoped
public class InvoiceBonusService {

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

    @Transactional
    public InvoiceBonus addSelfAssign(String invoiceuuid, String useruuid,
                                      InvoiceBonus.ShareType type, double value, String note) {
        assertEligibleForInvoice(invoiceuuid, useruuid);
        return addInternal(invoiceuuid, useruuid, useruuid, type, value, note);
    }

    @Transactional
    public InvoiceBonus addAdmin(String invoiceuuid, String targetUseruuid, String addedBy,
                                 InvoiceBonus.ShareType type, double value, String note) {
        return addInternal(invoiceuuid, targetUseruuid, addedBy, type, value, note);
    }

    private InvoiceBonus addInternal(String invoiceuuid, String useruuid, String addedBy,
                                     InvoiceBonus.ShareType type, double value, String note) {
        if (InvoiceBonus.count("invoiceuuid = ?1 and useruuid = ?2", invoiceuuid, useruuid) > 0) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("User already added for invoice bonus").build());
        }
        Invoice inv = Invoice.findById(invoiceuuid);
        if (inv == null) throw new WebApplicationException(Response.Status.NOT_FOUND);

        InvoiceBonus ib = new InvoiceBonus();
        ib.setInvoiceuuid(invoiceuuid);
        ib.setUseruuid(useruuid);
        ib.setAddedBy(addedBy);
        ib.setShareType(type);
        ib.setShareValue(value);
        ib.setOverrideNote(note);
        recomputeComputedAmount(inv, ib);
        ib.persist();

        if (type == InvoiceBonus.ShareType.PERCENT) {
            double sumPct = InvoiceBonus.<InvoiceBonus>stream("invoiceuuid = ?1 and shareType = ?2",
                            invoiceuuid, InvoiceBonus.ShareType.PERCENT)
                    .mapToDouble(InvoiceBonus::getShareValue).sum();
            if (sumPct > 100.0 + 1e-9) {
                throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                        .entity("Sum of percent shares exceeds 100%").build());
            }
        }
        return ib;
    }

    @Transactional
    public void updateShare(String bonusUuid, InvoiceBonus.ShareType type, double value, String note) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        ib.setShareType(type);
        ib.setShareValue(value);
        ib.setOverrideNote(note);
        Invoice inv = Invoice.findById(ib.getInvoiceuuid());
        recomputeComputedAmount(inv, ib);
        ib.persist();
    }

    @Transactional
    public void approve(String bonusUuid, String approverUuid) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);

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
        } else {
            recomputeComputedAmount(inv, ib);
            ib.persist();
        }
    }

    /** Default selection at approve-time if none exists (bulk persist):
     *   BASE lines billed by applicant -> 0%; other BASE -> 100%.
     */
    private void ensureDefaultLinesIfEmptyBulk(InvoiceBonus ib) {
        if (InvoiceBonusLine.count("bonusuuid", ib.getUuid()) > 0) return;
        Invoice inv = Invoice.findById(ib.getInvoiceuuid());
        if (inv == null || inv.getInvoiceitems() == null) return;

        List<InvoiceBonusLine> toPersist = new ArrayList<>();
        for (InvoiceItem ii : inv.getInvoiceitems()) {
            if (ii.getOrigin() == InvoiceItemOrigin.CALCULATED) continue;
            double pct = Objects.equals(ii.getConsultantuuid(), ib.getUseruuid()) ? 0.0 : 100.0;
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
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        ib.setOverrideNote(note);
        setStatus(bonusUuid, approverUuid, SalesApprovalStatus.REJECTED);
    }

    private void setStatus(String bonusUuid, String approver, SalesApprovalStatus status) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);

        String approverUuid = resolveUserUuid(approver);
        if (approverUuid == null) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("Approver must be a valid user UUID or username").build());
        }

        ib.setStatus(status);
        ib.setApprovedBy(approverUuid);
        ib.setApprovedAt(java.time.LocalDateTime.now());
        ib.persist();
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
        Panache.getEntityManager().remove(Objects.requireNonNull(InvoiceBonus.findById(bonusUuid)));
        InvoiceBonusLine.delete("bonusuuid", bonusUuid);
    }

    public List<InvoiceBonusLine> listLines(String bonusuuid) {
        return InvoiceBonusLine.list("bonusuuid = ?1", bonusuuid);
    }

    @Transactional
    public void putLines(String invoiceuuid, String bonusuuid, List<InvoiceBonusLine> lines) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusuuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        if (!Objects.equals(ib.getInvoiceuuid(), invoiceuuid)) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("bonusuuid does not belong to invoiceuuid").build());
        }

        // Sanitize input and compute from the submitted set (no extra read)
        Invoice inv = Invoice.findById(invoiceuuid);
        List<InvoiceBonusLine> sanitized = (lines == null ? List.<InvoiceBonusLine>of() :
                lines.stream().map(l -> {
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
            double discountPct = Optional.ofNullable(inv.getHeaderDiscountPct())
                    .map(BigDecimal::doubleValue).orElse(0.0);
            amount = baseSelected * (1.0 - discountPct/100.0);
        }

        if (inv.getType() == InvoiceType.CREDIT_NOTE) amount = -amount;
        return round2(amount);
    }

    /** Legacy computation when no line selections exist. */
    private static void recomputeComputedAmount(Invoice inv, InvoiceBonus ib) {
        double base = 0.0;
        if (inv != null) {
            if (inv.getSumAfterDiscounts() != null) base = inv.getSumAfterDiscounts();
            else base = inv.getSumNoTax(); // adjust if your entity differs
            if (inv.getType() == InvoiceType.CREDIT_NOTE) base = -base;
        }
        double computed = switch (ib.getShareType()) {
            case PERCENT -> base * (ib.getShareValue() / 100.0);
            case AMOUNT  -> ib.getShareValue();
        };
        ib.setComputedAmount(round2(computed));
    }

    /** Recalc all bonuses for an invoice (honors line selections). */
    @Transactional
    public void recalcForInvoice(String invoiceuuid) {
        Invoice inv = Invoice.findById(invoiceuuid);
        if (inv == null) return;
        List<InvoiceBonus> list = findByInvoice(invoiceuuid);
        for (InvoiceBonus ib : list) {
            List<InvoiceBonusLine> lines = InvoiceBonusLine.list("bonusuuid = ?1", ib.getUuid());
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

    private static int fiscalYearOf(LocalDate date) {
        LocalDate fyStart = DateUtils.getFiscalStartDateBasedOnDate(date);
        return fyStart.getYear();
    }

    private static String fiscalYearLabel(int fyStartYear) {
        return fyStartYear + "/" + (fyStartYear + 1);
    }

    private static void assertEligibleForInvoice(String invoiceuuid, String useruuid) {
        Invoice inv = Invoice.findById(invoiceuuid);
        if (inv == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        LocalDate date = inv.getInvoicedate();
        if (date == null) date = LocalDate.now();
        int fy = fiscalYearOf(date);
        BonusEligibility be = BonusEligibility.find("useruuid = ?1 and financialYear = ?2", useruuid, fy).firstResult();
        if (be == null) {
            String msg = "User not eligible to self-assign for FY " + fiscalYearLabel(fy);
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN).entity(msg).build());
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }


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
        if (groupUuid == null || groupUuid.isBlank()) {
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
        long deleted = BonusEligibility.delete("useruuid", useruuid);
        if (deleted == 0) throw new WebApplicationException(Response.Status.NOT_FOUND);
    }

    /**
     * Splits each user's approved bonus sales by consultant company at invoice date for a given FY.
     *
     * Algorithm (per APPROVED bonus):
     *  1) Load the invoice and its items.
     *  2) Build the selection map: use stored InvoiceBonusLine percentages; if none exist,
     *     simulate the default selection (0% for applicant's own lines, 100% for others).
     *  3) For each selected BASE line:
     *      - baseContribution = (hours * rate) * (pct/100).
     *      - If CALCULATED items exist: add pro‑rata share of syntheticTotal using the line's
     *        baseContribution / baseSelectedSum.
     *        Otherwise: apply invoice discount to baseContribution.
     *      - For credit notes, invert the sign.
     *      - Attribute the final line amount to the consultant's company at invoice date.
     *
     * The per-line amounts sum up to the same total computed by computeAmountFromLines().
     */
    public Map<String, List<BonusAggregateResource.CompanyAmount>>
    calculateCompanyBonusShareByFinancialYear(int financialYear, LocalDate periodStart, LocalDate periodEnd) {

        // userId -> (companyId -> amount)
        Map<String, Map<String, Double>> acc = new HashMap<>();
        Map<String, String> companyNames     = new HashMap<>();

        // Cache for consultant status resolution
        Map<String, Map<LocalDate, UserStatus>> userStatusCache = new HashMap<>();

        // 1) Load invoices in FY having APPROVED bonuses (to avoid scanning all invoices)
        List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoices = Panache.getEntityManager()
                .createQuery("""
                    SELECT DISTINCT i FROM dk.trustworks.intranet.aggregates.invoice.model.Invoice i
                    WHERE i.invoicedate >= :from AND i.invoicedate <= :to
                      AND EXISTS (
                        SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
                        WHERE b.invoiceuuid = i.uuid AND b.status = :approved
                      )
                    """, dk.trustworks.intranet.aggregates.invoice.model.Invoice.class)
                .setParameter("from", periodStart)
                .setParameter("to", periodEnd)
                .setParameter("approved", SalesApprovalStatus.APPROVED)
                .getResultList();

        if (invoices.isEmpty()) {
            return Map.of();
        }

        List<String> invoiceIds = invoices.stream().map(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid).toList();

        // 2) Load APPROVED bonuses and group by invoice
        List<InvoiceBonus> approvedBonuses = InvoiceBonus.list(
                "invoiceuuid in ?1 and status = ?2", invoiceIds, SalesApprovalStatus.APPROVED);
        Map<String, List<InvoiceBonus>> bonusesByInvoice = approvedBonuses.stream()
                .collect(Collectors.groupingBy(InvoiceBonus::getInvoiceuuid));

        // 3) Load items per invoice (1 bulk query)
        Map<String, List<dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem>> itemsByInvoice = new HashMap<>();
        {
            List<dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem> allItems =
                    dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem
                            .list("invoiceuuid in ?1", invoiceIds);
            for (dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem ii : allItems) {
                itemsByInvoice.computeIfAbsent(ii.getInvoiceuuid(), k -> new ArrayList<>()).add(ii);
            }
        }

        // 4) Load saved line selections for all bonuses once
        Map<String, List<InvoiceBonusLine>> linesByBonus = new HashMap<>();
        {
            List<String> bonusIds = approvedBonuses.stream().map(InvoiceBonus::getUuid).toList();
            if (!bonusIds.isEmpty()) {
                List<InvoiceBonusLine> allLines = InvoiceBonusLine.list("bonusuuid in ?1", bonusIds);
                for (InvoiceBonusLine l : allLines) {
                    linesByBonus.computeIfAbsent(l.getBonusuuid(), k -> new ArrayList<>()).add(l);
                }
            }
        }

        // 5) Process invoice by invoice
        for (dk.trustworks.intranet.aggregates.invoice.model.Invoice inv : invoices) {
            LocalDate invoiceDate = inv.getInvoicedate();
            List<dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem> items =
                    itemsByInvoice.getOrDefault(inv.getUuid(), List.of());
            if (items.isEmpty()) continue;

            // Precompute invoice totals
            double baseTotal = items.stream()
                    .filter(ii -> ii.getOrigin() != InvoiceItemOrigin.CALCULATED)
                    .mapToDouble(ii -> ii.getHours() * ii.getRate())
                    .sum();

            double syntheticTotal = items.stream()
                    .filter(ii -> ii.getOrigin() == InvoiceItemOrigin.CALCULATED)
                    .mapToDouble(ii -> ii.getHours() * ii.getRate())
                    .sum();

            double discountPct = Optional.ofNullable(inv.getHeaderDiscountPct())
                    .map(BigDecimal::doubleValue).orElse(0.0);

            Map<String, dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem> itemById = items.stream()
                    .collect(Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem::getUuid, Function.identity(), (a,b)->a));

            // Bonuses on this invoice
            for (InvoiceBonus bonus : bonusesByInvoice.getOrDefault(inv.getUuid(), List.of())) {
                String applicantUserId = bonus.getUseruuid();

                // Build selection map: stored lines or default (0% own, 100% others)
                Map<String, Double> pctByItem = new LinkedHashMap<>();
                List<InvoiceBonusLine> saved = linesByBonus.getOrDefault(bonus.getUuid(), List.of());

                if (!saved.isEmpty()) {
                    for (InvoiceBonusLine l : saved) {
                        var ii = itemById.get(l.getInvoiceitemuuid());
                        if (ii == null) continue;
                        if (ii.getOrigin() == InvoiceItemOrigin.CALCULATED) continue;
                        pctByItem.put(ii.getUuid(), sanitizePct(l.getPercentage()));
                    }
                } else {
                    // Simulate approval defaults (§ ensureDefaultLinesIfEmptyBulk)
                    for (var ii : items) {
                        if (ii.getOrigin() == InvoiceItemOrigin.CALCULATED) continue;
                        double pct = Objects.equals(ii.getConsultantuuid(), applicantUserId) ? 0.0 : 100.0;
                        pctByItem.put(ii.getUuid(), pct);
                    }
                }

                // baseSelectedSum and per‑item base contribution
                Map<String, Double> baseContribution = new HashMap<>();
                double baseSelectedSum = 0.0;
                for (var en : pctByItem.entrySet()) {
                    var ii = itemById.get(en.getKey());
                    if (ii == null) continue;
                    double c = (ii.getHours() * ii.getRate()) * (sanitizePct(en.getValue()) / 100.0);
                    baseContribution.put(ii.getUuid(), c);
                    baseSelectedSum += c;
                }

                // Attribute each selected BASE line to the consultant's company
                for (var en : pctByItem.entrySet()) {
                    var ii = itemById.get(en.getKey());
                    if (ii == null) continue;

                    double baseC = baseContribution.getOrDefault(ii.getUuid(), 0.0);
                    double lineFinal;
                    if (Math.abs(syntheticTotal) > 1e-9) {
                        double ratio = (baseSelectedSum == 0.0) ? 0.0 : (baseC / baseSelectedSum);
                        lineFinal = baseC + ratio * syntheticTotal;
                    } else {
                        lineFinal = baseC * (1.0 - discountPct / 100.0);
                    }
                    if (inv.getType() == dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType.CREDIT_NOTE) {
                        lineFinal = -lineFinal;
                    }

                    // Consultant's company at invoice date (same approach as findBonusApprovalRow)
                    String consultantId = ii.getConsultantuuid();
                    UserStatus st = getUserStatusCached(consultantId, invoiceDate, userStatusCache);

                    String compId   = (st != null && st.getCompany() != null) ? st.getCompany().getUuid() : "unknown";
                    String compName = (st != null && st.getCompany() != null) ? st.getCompany().getName() : "Unknown Company";
                    companyNames.put(compId, compName);

                    acc.computeIfAbsent(applicantUserId, k -> new HashMap<>())
                            .merge(compId, round2(lineFinal), Double::sum);
                }
            }
        }

        // Transform to response shape: userId -> List<CompanyAmount>
        Map<String, List<BonusAggregateResource.CompanyAmount>> result = new HashMap<>();
        for (var e : acc.entrySet()) {
            String userId = e.getKey();
            Map<String, Double> perCompany = e.getValue();
            List<BonusAggregateResource.CompanyAmount> list = perCompany.entrySet().stream()
                    .map(x -> new BonusAggregateResource.CompanyAmount(
                            x.getKey(),
                            companyNames.getOrDefault(x.getKey(), "Unknown Company"),
                            round2(x.getValue())))
                    .sorted((a, b) -> Double.compare(b.amount(), a.amount()))
                    .toList();
            result.put(userId, list);
        }
        return result;
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
