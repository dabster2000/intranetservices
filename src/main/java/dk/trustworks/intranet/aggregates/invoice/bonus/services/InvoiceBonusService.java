// src/main/java/dk/trustworks/intranet/aggregates/invoice/bonus/services/InvoiceBonusService.java
package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibilityGroup;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus.ShareType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonusLine;
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
            double discountPct = Optional.ofNullable(inv.getDiscount()).orElse(0.0);
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

    private static void assertEligible(String useruuid) {
        BonusEligibility be = BonusEligibility.find("useruuid", useruuid).firstResult();
        if (be == null) {
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                    .entity("User is not allowed to self‑assign bonus").build());
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
     * Calculates approved bonus amounts per user, split by their company affiliation
     * at the time of each invoice for a given financial year.
     *
     * @param financialYear The financial year (e.g., 2025 for FY 2025-07-01 to 2026-06-30)
     * @param periodStart   Start date of the financial year (inclusive)
     * @param periodEnd     End date of the financial year (inclusive)
     * @return Map of user UUID to list of company amounts
     */
    public Map<String, List<dk.trustworks.intranet.aggregates.invoice.bonus.resources.BonusAggregateResource.CompanyAmount>>
    calculateCompanyBonusShareByFinancialYear(int financialYear, LocalDate periodStart, LocalDate periodEnd) {
        // Result map: userId -> List of CompanyAmount
        Map<String, Map<String, Double>> userCompanyAmounts = new HashMap<>();
        Map<String, String> companyNames = new HashMap<>();

        // Cache for user status lookups to avoid repeated queries
        Map<String, Map<LocalDate, UserStatus>> userStatusCache = new HashMap<>();

        // Query all invoices within the financial year that have approved bonuses
        // Optimized: Get only invoices that actually have approved bonuses
        List<Invoice> invoicesWithBonuses = Panache.getEntityManager()
                .createQuery("""
                    SELECT DISTINCT i FROM Invoice i
                    WHERE i.invoicedate >= :startDate
                    AND i.invoicedate <= :endDate
                    AND EXISTS (
                        SELECT 1 FROM InvoiceBonus b
                        WHERE b.invoiceuuid = i.uuid
                        AND b.status = :approvedStatus
                    )
                    """, Invoice.class)
                .setParameter("startDate", periodStart)
                .setParameter("endDate", periodEnd)
                .setParameter("approvedStatus", SalesApprovalStatus.APPROVED)
                .getResultList();

        // Batch load all approved bonuses for these invoices
        if (invoicesWithBonuses.isEmpty()) {
            return new HashMap<>();
        }

        List<String> invoiceIds = invoicesWithBonuses.stream()
                .map(Invoice::getUuid)
                .toList();

        List<InvoiceBonus> allApprovedBonuses = InvoiceBonus.list(
                "invoiceuuid in ?1 and status = ?2",
                invoiceIds, SalesApprovalStatus.APPROVED
        );

        // Group bonuses by invoice for efficient processing
        Map<String, List<InvoiceBonus>> bonusesByInvoice = allApprovedBonuses.stream()
                .collect(Collectors.groupingBy(InvoiceBonus::getInvoiceuuid));

        // Batch load all bonus lines
        List<String> bonusIds = allApprovedBonuses.stream()
                .map(InvoiceBonus::getUuid)
                .toList();

        List<InvoiceBonusLine> allBonusLines = bonusIds.isEmpty() ?
                new ArrayList<>() :
                InvoiceBonusLine.list("bonusuuid in ?1", bonusIds);

        Map<String, List<InvoiceBonusLine>> linesByBonus = allBonusLines.stream()
                .collect(Collectors.groupingBy(InvoiceBonusLine::getBonusuuid));

        // Process each invoice with its bonuses
        for (Invoice invoice : invoicesWithBonuses) {
            LocalDate invoiceDate = invoice.getInvoicedate();
            if (invoiceDate == null) continue;

            List<InvoiceBonus> invoiceBonuses = bonusesByInvoice.getOrDefault(invoice.getUuid(), new ArrayList<>());

            // Process each approved bonus
            for (InvoiceBonus bonus : invoiceBonuses) {
                String userId = bonus.getUseruuid();
                double bonusAmount = bonus.getComputedAmount();

                // Get user's company at invoice date (with caching)
                UserStatus userStatus = getUserStatusCached(userId, invoiceDate, userStatusCache);

                String companyId = "unknown";
                String companyName = "Unknown Company";

                if (userStatus != null && userStatus.getCompany() != null) {
                    companyId = userStatus.getCompany().getUuid();
                    companyName = userStatus.getCompany().getName();
                    companyNames.put(companyId, companyName);
                }

                // Check if there are line-level allocations
                List<InvoiceBonusLine> bonusLines = linesByBonus.getOrDefault(bonus.getUuid(), new ArrayList<>());

                if (!bonusLines.isEmpty()) {
                    // Calculate amount based on line allocations
                    for (InvoiceBonusLine line : bonusLines) {
                        double lineAmount = bonusAmount * (line.getPercentage() / 100.0);
                        lineAmount = round2(lineAmount);

                        // Add to user's company total
                        userCompanyAmounts
                                .computeIfAbsent(userId, k -> new HashMap<>())
                                .merge(companyId, lineAmount, Double::sum);
                    }
                } else {
                    // No line allocations, use full bonus amount
                    userCompanyAmounts
                            .computeIfAbsent(userId, k -> new HashMap<>())
                            .merge(companyId, bonusAmount, Double::sum);
                }
            }
        }

        // Transform the aggregated data into the response format
        Map<String, List<dk.trustworks.intranet.aggregates.invoice.bonus.resources.BonusAggregateResource.CompanyAmount>> result = new HashMap<>();

        for (Map.Entry<String, Map<String, Double>> userEntry : userCompanyAmounts.entrySet()) {
            String userId = userEntry.getKey();
            Map<String, Double> companyTotals = userEntry.getValue();

            List<dk.trustworks.intranet.aggregates.invoice.bonus.resources.BonusAggregateResource.CompanyAmount> companyAmountList =
                    companyTotals.entrySet().stream()
                            .map(entry -> new dk.trustworks.intranet.aggregates.invoice.bonus.resources.BonusAggregateResource.CompanyAmount(
                                    entry.getKey(),
                                    companyNames.getOrDefault(entry.getKey(), "Unknown Company"),
                                    round2(entry.getValue())
                            ))
                            .sorted((a, b) -> Double.compare(b.amount(), a.amount())) // Sort by amount descending
                            .toList();

            result.put(userId, companyAmountList);
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
