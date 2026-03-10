package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.PartnerProductionBonusCalculator;
import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.PartnerSalesBonusCalculator;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.*;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibilityGroup;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.LockedBonusPoolData;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.MyBonusRow;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.aggregates.revenue.services.RevenueService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@ApplicationScoped
public class PartnerBonusDashboardService {

    @Inject InvoiceBonusService invoiceBonusService;
    @Inject InvoiceService invoiceService;
    @Inject UserService userService;
    @Inject RevenueService revenueService;
    @Inject PartnerSalesBonusCalculator salesCalculator;
    @Inject PartnerProductionBonusCalculator productionCalculator;
    @Inject LockedBonusPoolService lockedBonusPoolService;
    @Inject PartnerBonusPayoutService payoutService;

    private Cache<String, PartnerDashboardDTO> dashboardCache;
    private final Map<String, User> userCache = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        dashboardCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(100)
                .build();
    }

    /**
     * Observes cache invalidation events fired by approve/reject actions.
     */
    public void onBonusCacheInvalidation(@Observes BonusCacheInvalidationEvent event) {
        invalidateCache(event.fiscalYear());
        Log.infof("Dashboard cache invalidated for FY %d via CDI event", event.fiscalYear());
    }

    public void invalidateCache(int fiscalYear) {
        dashboardCache.invalidateAll();
        Log.infof("Dashboard cache invalidated for FY %d", fiscalYear);
    }

    /**
     * Load full dashboard data for a fiscal year with optional group filter.
     * Checks locked snapshot first, then cache, then computes live.
     */
    public PartnerDashboardDTO loadDashboard(int fiscalYear, Set<String> groupUuids) {
        // 1. Check locked snapshot
        Optional<LockedBonusPoolData> locked = lockedBonusPoolService.findByFiscalYear(fiscalYear);
        if (locked.isPresent()) {
            Log.infof("Returning LOCKED snapshot for partner dashboard FY %d", fiscalYear);
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.findAndRegisterModules();
                PartnerDashboardDTO snapshot = mapper.readValue(locked.get().poolContextJson, PartnerDashboardDTO.class);
                return snapshot;
            } catch (Exception e) {
                Log.warnf("Failed to deserialize locked snapshot for FY %d, falling back to live data", fiscalYear);
            }
        }

        // 2. Check cache
        String cacheKey = buildCacheKey(fiscalYear, groupUuids);
        PartnerDashboardDTO cached = dashboardCache.getIfPresent(cacheKey);
        if (cached != null) {
            return new PartnerDashboardDTO(
                    cached.fiscalYear(), cached.fiscalYearStart(), cached.fiscalYearEnd(),
                    cached.groups(), cached.summary(), cached.consultants(),
                    cached.groupAnalytics(), cached.dataTimestamp(), true
            );
        }

        // 3. Compute live
        Log.infof("Computing LIVE dashboard data for FY %d", fiscalYear);
        PartnerDashboardDTO result = computeDashboard(fiscalYear, groupUuids);
        dashboardCache.put(cacheKey, result);
        return result;
    }

    private PartnerDashboardDTO computeDashboard(int fiscalYear, Set<String> groupUuids) {
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);

        // Load groups for FY
        List<BonusEligibilityGroup> allGroups = BonusEligibilityGroup
                .<BonusEligibilityGroup>list("financialYear", fiscalYear);
        List<BonusEligibilityGroup> groups = filterGroups(allGroups, groupUuids);

        // Load eligibilities
        List<BonusEligibility> allEligibilities = invoiceBonusService.listEligibility(null, fiscalYear);
        List<BonusEligibility> eligibilities = filterEligibilitiesByGroups(allEligibilities, groups);

        // Build per-consultant data
        List<ConsultantBonusDTO> consultants = new ArrayList<>();
        for (BonusEligibility eligibility : eligibilities) {
            try {
                ConsultantBonusDTO dto = buildConsultantData(eligibility, fyStart, fyEnd);
                if (dto != null) consultants.add(dto);
            } catch (Exception e) {
                Log.warnf("Failed to load bonus data for user %s: %s", eligibility.getUseruuid(), e.getMessage());
            }
        }

        // Apply partner bonuses (sales + production)
        applyPartnerBonuses(consultants, groups, fyStart, fyEnd, fiscalYear);

        // Build group analytics
        List<GroupAnalyticsDTO> groupAnalytics = buildGroupAnalytics(groups, consultants);

        // Build KPIs
        KpiSummaryDTO summary = calculateKpis(consultants);

        return new PartnerDashboardDTO(
                fiscalYear,
                fyStart.toString(),
                fyEnd.toString(),
                groups,
                summary,
                consultants,
                groupAnalytics,
                LocalDate.now().toString(),
                false
        );
    }

    private ConsultantBonusDTO buildConsultantData(BonusEligibility eligibility,
                                                    LocalDate fyStart, LocalDate fyEnd) {
        String userUuid = eligibility.getUseruuid();
        User user = getCachedUser(userUuid);
        if (user == null) return null;

        // Load bonus rows for FY via the invoice service
        List<MyBonusRow> bonuses = invoiceService.findMyBonusPage(
                userUuid, List.of(), fyStart, fyEnd.plusDays(1), 0, 10000
        );

        if (bonuses == null || bonuses.isEmpty()) return null;

        double approved = 0, pending = 0, rejected = 0;
        int approvedCount = 0, pendingCount = 0, rejectedCount = 0;

        for (MyBonusRow bonus : bonuses) {
            double amount = bonus.computedAmount();
            switch (bonus.status()) {
                case APPROVED -> { approved += amount; approvedCount++; }
                case PENDING -> { pending += amount; pendingCount++; }
                case REJECTED -> { rejected += amount; rejectedCount++; }
            }
        }

        int totalCount = approvedCount + pendingCount + rejectedCount;
        double approvalRate = totalCount > 0 ? (double) approvedCount / totalCount : 0.0;

        String groupUuid = eligibility.getGroup() != null ? eligibility.getGroup().getUuid() : null;
        String groupName = eligibility.getGroup() != null ? eligibility.getGroup().getName() : null;

        // Sales/production bonus fields are filled later by applyPartnerBonuses
        return new ConsultantBonusDTO(
                userUuid, user.getFullname(),
                groupUuid, groupName,
                round2(approved), round2(pending), round2(rejected),
                0, 0, 0, false,  // sales bonus fields - filled later
                0, 0, false,     // production bonus fields - filled later
                bonuses.size(), round4(approvalRate),
                false            // payout exists - filled later
        );
    }

    private void applyPartnerBonuses(List<ConsultantBonusDTO> consultants,
                                      List<BonusEligibilityGroup> groups,
                                      LocalDate fyStart, LocalDate fyEnd,
                                      int fiscalYear) {
        for (BonusEligibilityGroup group : groups) {
            List<ConsultantBonusDTO> groupConsultants = consultants.stream()
                    .filter(c -> group.getUuid().equals(c.groupUuid()))
                    .toList();

            if (groupConsultants.isEmpty()) continue;

            int partnerCount = groupConsultants.size();

            // Get group approved sales total (inline the query from BonusEligibilityGroupResource)
            double groupSalesTotal;
            try {
                groupSalesTotal = sumApprovedForGroupPeriod(group, fyStart, fyEnd);
            } catch (Exception e) {
                Log.errorf("Failed to get approved total for group %s: %s", group.getName(), e.getMessage());
                groupSalesTotal = groupConsultants.stream().mapToDouble(ConsultantBonusDTO::approvedTotal).sum();
            }

            BigDecimal groupSalesBD = BigDecimal.valueOf(groupSalesTotal);
            BigDecimal salesBonusPerPartner = salesCalculator.calculateBonusPerPartner(groupSalesBD, partnerCount);
            boolean salesThresholdMet = salesCalculator.isThresholdMet(groupSalesBD, partnerCount);

            for (int i = 0; i < consultants.size(); i++) {
                ConsultantBonusDTO c = consultants.get(i);
                if (!group.getUuid().equals(c.groupUuid())) continue;

                // Production bonus: individual revenue
                double ownRevenue = 0;
                try {
                    List<DateValueDTO> revSeries = revenueService.getRegisteredRevenueByPeriodAndSingleConsultant(
                            c.consultantUuid(), stringIt(fyStart), stringIt(fyEnd));
                    ownRevenue = revSeries == null ? 0 : revSeries.stream()
                            .mapToDouble(dv -> dv.getValue() != null ? dv.getValue() : 0.0)
                            .sum();
                } catch (Exception e) {
                    Log.warnf("Failed to get revenue for %s: %s", c.consultantName(), e.getMessage());
                }

                BigDecimal revenueBD = BigDecimal.valueOf(ownRevenue);
                BigDecimal prodBonus = productionCalculator.calculateProductionBonus(revenueBD, 12);
                boolean prodEligible = productionCalculator.isThresholdMet(revenueBD, 12);

                boolean payoutExists = payoutService.hasExistingPayout(c.consultantUuid(), fiscalYear);

                // Replace with enriched version
                consultants.set(i, new ConsultantBonusDTO(
                        c.consultantUuid(), c.consultantName(),
                        c.groupUuid(), c.groupName(),
                        c.approvedTotal(), c.pendingTotal(), c.rejectedTotal(),
                        salesBonusPerPartner.doubleValue(), groupSalesTotal, partnerCount, salesThresholdMet,
                        prodBonus.doubleValue(), round2(ownRevenue), prodEligible,
                        c.invoiceCount(), c.approvalRate(),
                        payoutExists
                ));
            }
        }
    }

    /**
     * Sum approved bonus amounts for all invoices that have at least one APPROVED bonus
     * from a member of the group within the period.
     * (Inlined from BonusEligibilityGroupResource to avoid injecting a @RequestScoped bean)
     */
    private double sumApprovedForGroupPeriod(BonusEligibilityGroup group, LocalDate periodStart, LocalDate periodEnd) {
        List<BonusEligibility> eligibility = BonusEligibility
                .<BonusEligibility>list("group.uuid = ?1", group.getUuid());
        if (eligibility.isEmpty()) return 0.0;

        Set<String> users = new HashSet<>();
        for (BonusEligibility be : eligibility) {
            if (be.getUseruuid() != null && !be.getUseruuid().isBlank()) {
                users.add(be.getUseruuid());
            }
        }
        if (users.isEmpty()) return 0.0;

        List<String> invoiceIds = Panache.getEntityManager().createQuery("""
                SELECT DISTINCT i.uuid
                FROM dk.trustworks.intranet.aggregates.invoice.model.Invoice i,
                     dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
                WHERE b.invoiceuuid = i.uuid
                  AND b.status = :approved
                  AND b.useruuid IN :users
                  AND i.invoicedate >= :from
                  AND i.invoicedate <= :to
            """, String.class)
                .setParameter("approved", SalesApprovalStatus.APPROVED)
                .setParameter("users", users)
                .setParameter("from", periodStart)
                .setParameter("to", periodEnd)
                .getResultList();

        if (invoiceIds.isEmpty()) return 0.0;

        double total = 0.0;
        for (String invId : invoiceIds) {
            total += invoiceBonusService.sumApproved(invId);
        }
        return round2(total);
    }

    private List<GroupAnalyticsDTO> buildGroupAnalytics(List<BonusEligibilityGroup> groups,
                                                         List<ConsultantBonusDTO> consultants) {
        List<GroupAnalyticsDTO> analytics = new ArrayList<>();

        for (BonusEligibilityGroup group : groups) {
            List<ConsultantBonusDTO> gc = consultants.stream()
                    .filter(c -> group.getUuid().equals(c.groupUuid()))
                    .toList();

            double salesTotal = gc.stream().mapToDouble(ConsultantBonusDTO::groupSalesTotal).findFirst().orElse(0);
            double bonusTotal = gc.stream().mapToDouble(c -> c.salesBonus() + c.productionBonus()).sum();
            int totalInvoices = gc.stream().mapToInt(ConsultantBonusDTO::invoiceCount).sum();
            int approvedInvoices = gc.stream()
                    .mapToInt(c -> (int) Math.round(c.approvalRate() * c.invoiceCount()))
                    .sum();
            double approvalRate = totalInvoices > 0 ? (double) approvedInvoices / totalInvoices : 0;

            analytics.add(new GroupAnalyticsDTO(
                    group.getUuid(), group.getName(),
                    round2(salesTotal), round2(bonusTotal),
                    gc.size(), round4(approvalRate)
            ));
        }

        return analytics;
    }

    private KpiSummaryDTO calculateKpis(List<ConsultantBonusDTO> consultants) {
        double salesBonusTotal = consultants.stream().mapToDouble(ConsultantBonusDTO::salesBonus).sum();
        double prodBonusTotal = consultants.stream().mapToDouble(ConsultantBonusDTO::productionBonus).sum();
        double totalPool = salesBonusTotal + prodBonusTotal;

        double approvedTotal = consultants.stream().mapToDouble(ConsultantBonusDTO::approvedTotal).sum();
        double pendingTotal = consultants.stream().mapToDouble(ConsultantBonusDTO::pendingTotal).sum();
        double rejectedTotal = consultants.stream().mapToDouble(ConsultantBonusDTO::rejectedTotal).sum();

        int activeConsultants = (int) consultants.stream()
                .filter(c -> c.salesBonus() + c.productionBonus() > 0)
                .count();
        int totalInvoices = consultants.stream().mapToInt(ConsultantBonusDTO::invoiceCount).sum();
        double averageBonus = activeConsultants > 0 ? totalPool / activeConsultants : 0;
        double approvalRate = totalPool > 0 ? approvedTotal / totalPool : 0;

        return new KpiSummaryDTO(
                round2(totalPool), round2(approvedTotal), round2(pendingTotal), round2(rejectedTotal),
                activeConsultants, totalInvoices, round2(averageBonus), round4(approvalRate)
        );
    }

    // --- helpers ---

    private String buildCacheKey(int fiscalYear, Set<String> groupUuids) {
        String groups = (groupUuids == null || groupUuids.isEmpty()) ? "ALL" :
                groupUuids.stream().sorted().collect(Collectors.joining("-"));
        return "dashboard-" + fiscalYear + "-" + groups;
    }

    private List<BonusEligibilityGroup> filterGroups(List<BonusEligibilityGroup> all, Set<String> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) return all;
        return all.stream().filter(g -> selectedIds.contains(g.getUuid())).toList();
    }

    private List<BonusEligibility> filterEligibilitiesByGroups(List<BonusEligibility> all,
                                                                List<BonusEligibilityGroup> groups) {
        Set<String> groupIds = groups.stream().map(BonusEligibilityGroup::getUuid).collect(Collectors.toSet());
        return all.stream()
                .filter(e -> e.getGroup() != null && groupIds.contains(e.getGroup().getUuid()))
                .toList();
    }

    private User getCachedUser(String uuid) {
        return userCache.computeIfAbsent(uuid, id -> userService.findById(id, true));
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
