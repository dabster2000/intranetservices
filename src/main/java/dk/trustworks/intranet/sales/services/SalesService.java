package dk.trustworks.intranet.sales.services;

import dk.trustworks.intranet.contracts.model.enums.SalesStatus;
import dk.trustworks.intranet.sales.model.SalesLead;
import dk.trustworks.intranet.sales.model.SalesLeadConsultant;
import dk.trustworks.intranet.sales.model.dto.LeadTrendData;
import dk.trustworks.intranet.sales.model.enums.LeadStatus;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

@JBossLog
@ApplicationScoped
public class SalesService {

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    EntityManager entityManager;

    public SalesLead findOne(String uuid) {
        return SalesLead.findById(uuid);
    }

    public List<SalesLead> findAll(int offset, int limit, List<String> sortOrders, String filter, String status) {
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        // Exclude WON and LOST statuses
        conditions.add("status NOT IN (:excludedStatuses)");
        params.put("excludedStatuses", Arrays.asList(LeadStatus.WON, LeadStatus.LOST));

        if (status != null && !status.isEmpty()) {
            conditions.add("status IN (:statusList)");
            params.put("statusList", Arrays.stream(status.split(",")).map(LeadStatus::valueOf).toList());
        }

        if (filter != null && !filter.isEmpty()) {
            conditions.add("(lower(description) LIKE :filter OR lower(client.name) LIKE :filter)");
            params.put("filter", "%" + filter.toLowerCase() + "%");
        }

        String queryString = String.join(" AND ", conditions);

        // Build the Sort object
        Sort sort = null;
        if (sortOrders != null && !sortOrders.isEmpty()) {
            for (String sortOrder : sortOrders) {
                String[] parts = sortOrder.split(":");
                String field = parts[0];
                Sort.Direction direction = (parts.length > 1 && "desc".equalsIgnoreCase(parts[1]))
                        ? Sort.Direction.Descending
                        : Sort.Direction.Ascending;

                if ("created".equals(field)) {
                    // Invert the sort direction to match age sorting
                    direction = direction == Sort.Direction.Ascending ? Sort.Direction.Descending : Sort.Direction.Ascending;
                }

                if (sort == null) {
                    sort = Sort.by(field, direction);
                } else {
                    sort = sort.and(field, direction);
                }
            }
        }

        PanacheQuery<SalesLead> query;
        if (sort != null) {
            query = SalesLead.find(queryString, sort, params);
        } else {
            query = SalesLead.find(queryString, params);
        }

        // Apply paging
        int pageNumber = offset / limit;
        query = query.page(Page.of(pageNumber, limit));

        List<SalesLead> list = query.list();
        System.out.println("list = " + list.size());
        return list;
    }

    public long count(String filter, String status) {
        System.out.println("SalesService.count");
        System.out.println("filter = " + filter + ", status = " + status);
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        // Exclude WON and LOST statuses
        conditions.add("status NOT IN (:excludedStatuses)");
        params.put("excludedStatuses", Arrays.asList(LeadStatus.WON, LeadStatus.LOST));

        if (status != null && !status.isEmpty()) {
            conditions.add("status IN (:statusList)");
            params.put("statusList", Arrays.stream(status.split(",")).map(LeadStatus::valueOf).toList());
        }

        if (filter != null && !filter.isEmpty()) {
            conditions.add("(lower(description) LIKE :filter OR lower(client.name) LIKE :filter)");
            params.put("filter", "%" + filter.toLowerCase() + "%");
        }

        String queryString = String.join(" AND ", conditions);

        long count = SalesLead.count(queryString, params);
        System.out.println("count = " + count);
        return count;
    }

    public List<SalesLead> findAll() {
        return SalesLead.findAll().<SalesLead>stream().sorted(Comparator.comparing(SalesLead::getCreated)).toList();
    }

    public List<SalesLead> findLost(int offset, int limit) {
        return SalesLead.find("status = ?1 ORDER BY modified DESC", LeadStatus.LOST)
                .page(Page.of(offset / Math.max(limit, 1), Math.max(limit, 1)))
                .list();
    }

    public List<SalesLead> findWon(LocalDate sinceDate) {
        LocalDateTime since = sinceDate.atStartOfDay();
        log.infof("since = %s", since);
        return SalesLead.list("status = ?1 and wonDate >= ?2", LeadStatus.WON, since);
    }

    public List<SalesLead> findByStatus(SalesStatus... status) {
        return SalesLead.find("IN ?1", Arrays.stream(status).toList()).list();
    }

    @Transactional
    public SalesLead persist(SalesLead salesLead) {
        if(salesLead.getUuid()==null || salesLead.getUuid().isBlank()) {
            salesLead.setUuid(UUID.randomUUID().toString());
            salesLead.setCreated(LocalDateTime.now());
            if (salesLead.getStatus() == LeadStatus.WON) {
                salesLead.setWonDate(LocalDateTime.now());
            }
            salesLead.persist();
            logStageTransition(salesLead.getUuid(), null, salesLead.getStatus().name());
            log.info("Created new SalesLead with UUID: " + salesLead.getUuid());
        } else if(SalesLead.findById(salesLead.getUuid())==null) {
            if (salesLead.getStatus() == LeadStatus.WON) {
                salesLead.setWonDate(LocalDateTime.now());
            }
            salesLead.persist();
            logStageTransition(salesLead.getUuid(), null, salesLead.getStatus().name());
        } else {
            update(salesLead);
        }

        return salesLead;
    }

    @Transactional
    public void addConsultant(String salesLeaduuid, User user) {
        new SalesLeadConsultant(SalesLead.findById(salesLeaduuid), user).persist();
    }

    @Transactional
    public void removeConsultant(String salesleaduuid, String useruuid) {
        SalesLead salesLead = SalesLead.findById(salesleaduuid);
        User user = User.findById(useruuid);
        SalesLeadConsultant.delete("lead = ?1 and user = ?2", salesLead, user);
    }

    @Transactional
    public void update(SalesLead salesLead) {
        log.info("SalesService.update");
        log.info("salesLead = " + salesLead);

        // Determine won_date based on status transition
        SalesLead existing = SalesLead.findById(salesLead.getUuid());

        // Log stage transition if status changed
        if (existing != null && existing.getStatus() != salesLead.getStatus()) {
            logStageTransition(salesLead.getUuid(),
                    existing.getStatus() != null ? existing.getStatus().name() : null,
                    salesLead.getStatus().name());
        }

        LocalDateTime wonDate;
        if (salesLead.getStatus() == LeadStatus.WON && (existing == null || existing.getStatus() != LeadStatus.WON)) {
            // Transitioning TO WON — set won_date to now
            wonDate = LocalDateTime.now();
        } else if (salesLead.getStatus() != LeadStatus.WON) {
            // Not WON — clear won_date
            wonDate = null;
        } else {
            // Already WON and staying WON — preserve existing won_date
            wonDate = existing != null ? existing.getWonDate() : null;
        }

        SalesLead.update("client = ?1, " +
                        "allocation = ?2, " +
                        "closeDate = ?3, " +
                        "practice = ?4, " +
                        "leadManager = ?5, " +
                        "extension = ?6, " +
                        "rate = ?7, " +
                        "period = ?8, " +
                        "contactInformation = ?9, " +
                        "description = ?10, " +
                        "detailedDescription = ?11, " +
                        "status = ?12, " +
                        "modified = ?13, " +
                        "wonDate = ?14, " +
                        "lostReason = ?15, " +
                        "lostNotes = ?16, " +
                        "lostAtStage = ?17 " +
                        "WHERE uuid like ?18 ",
                salesLead.getClient(),
                salesLead.getAllocation(),
                salesLead.getCloseDate(),
                salesLead.getPractice(),
                salesLead.getLeadManager(),
                salesLead.isExtension(),
                salesLead.getRate(),
                salesLead.getPeriod(),
                salesLead.getContactInformation(),
                salesLead.getDescription(),
                salesLead.getDetailedDescription(),
                salesLead.getStatus(),
                LocalDateTime.now(),
                wonDate,
                salesLead.getLostReason(),
                salesLead.getLostNotes(),
                salesLead.getLostAtStage(),
                salesLead.getUuid());
    }

    @Transactional
    public void delete(String uuid) {
        SalesLead.deleteById(uuid);
    }

    /**
     * Computes 3-month rolling averages for active, new, and won leads.
     * Uses the 3 most recent completed calendar months (excludes current month).
     *
     * Active leads: from fact_pipeline_snapshot joined to sales_lead for revenue formula fields.
     * New leads: from sales_lead by created date.
     * Won leads: from sales_lead by won_date.
     *
     * Revenue formula: period * 150 * (allocation / 100) * rate
     */
    public LeadTrendData calculateTrends() {
        var now = YearMonth.now();

        // The 3 most recent completed months: now-3, now-2, now-1
        // e.g., if now is April 2026, months are Jan, Feb, Mar (202601, 202602, 202603)
        var formatter = DateTimeFormatter.ofPattern("yyyyMM");
        String month1 = now.minusMonths(3).format(formatter);
        String month2 = now.minusMonths(2).format(formatter);
        String month3 = now.minusMonths(1).format(formatter);
        List<String> months = List.of(month1, month2, month3);

        var activeLeads = calculateActiveLeadTrends(months);
        var newLeads = calculateNewLeadTrends(now);
        var wonLeads = calculateWonLeadTrends(now);

        return new LeadTrendData(activeLeads, newLeads, wonLeads);
    }

    private LeadTrendData.ActiveLeadTrend calculateActiveLeadTrends(List<String> months) {
        // Query snapshot table: count and revenue per month
        // Revenue uses snapshot-time fields (fps.period_months, fps.allocation, fps.rate)
        // to reflect the pipeline state as it was when the snapshot was captured
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT fps.snapshot_month,
                       COUNT(*) AS lead_count,
                       SUM(fps.period_months * 150.0 * (fps.allocation / 100.0) * fps.rate) AS total_revenue
                FROM fact_pipeline_snapshot fps
                WHERE fps.snapshot_month IN (:month1, :month2, :month3)
                  AND fps.stage_id NOT IN ('WON', 'LOST')
                GROUP BY fps.snapshot_month
                """)
                .setParameter("month1", months.get(0))
                .setParameter("month2", months.get(1))
                .setParameter("month3", months.get(2))
                .getResultList();

        double totalCount = 0;
        double totalRevenue = 0;
        int monthsWithData = months.size(); // Always divide by 3 for consistent averaging

        for (Object[] row : rows) {
            totalCount += ((Number) row[1]).doubleValue();
            totalRevenue += row[2] != null ? ((Number) row[2]).doubleValue() : 0;
        }

        double avgCount = roundToTwo(totalCount / monthsWithData);
        double avgRevenue = roundToTwo(totalRevenue / monthsWithData);

        return new LeadTrendData.ActiveLeadTrend(avgCount, avgRevenue);
    }

    private LeadTrendData.MonthlyLeadTrend calculateNewLeadTrends(YearMonth now) {
        // New leads: sales_lead rows where `created` falls within each of the 3 months
        LocalDateTime from = now.minusMonths(3).atDay(1).atStartOfDay();
        LocalDateTime to = now.atDay(1).atStartOfDay(); // exclusive: start of current month

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT DATE_FORMAT(created, '%Y%m') AS month_key,
                       COUNT(*) AS lead_count,
                       SUM(period * 150.0 * (allocation / 100.0) * rate) AS total_revenue
                FROM sales_lead
                WHERE created >= :fromDate AND created < :toDate
                GROUP BY month_key
                """)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();

        double totalCount = 0;
        double totalRevenue = 0;

        for (Object[] row : rows) {
            totalCount += ((Number) row[1]).doubleValue();
            totalRevenue += row[2] != null ? ((Number) row[2]).doubleValue() : 0;
        }

        double avgCount = roundToTwo(totalCount / 3.0);
        double avgRevenue = roundToTwo(totalRevenue / 3.0);

        return new LeadTrendData.MonthlyLeadTrend(avgCount, avgRevenue);
    }

    private LeadTrendData.MonthlyLeadTrend calculateWonLeadTrends(YearMonth now) {
        // Won leads: sales_lead rows where `won_date` falls within each of the 3 months
        LocalDateTime from = now.minusMonths(3).atDay(1).atStartOfDay();
        LocalDateTime to = now.atDay(1).atStartOfDay();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT DATE_FORMAT(won_date, '%Y%m') AS month_key,
                       COUNT(*) AS lead_count,
                       SUM(period * 150.0 * (allocation / 100.0) * rate) AS total_revenue
                FROM sales_lead
                WHERE won_date >= :fromDate AND won_date < :toDate
                  AND status = 'WON'
                GROUP BY month_key
                """)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();

        double totalCount = 0;
        double totalRevenue = 0;

        for (Object[] row : rows) {
            totalCount += ((Number) row[1]).doubleValue();
            totalRevenue += row[2] != null ? ((Number) row[2]).doubleValue() : 0;
        }

        double avgCount = roundToTwo(totalCount / 3.0);
        double avgRevenue = roundToTwo(totalRevenue / 3.0);

        return new LeadTrendData.MonthlyLeadTrend(avgCount, avgRevenue);
    }

    private static double roundToTwo(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private void logStageTransition(String leadUuid, String fromStage, String toStage) {
        try {
            String changedBy = requestHeaderHolder != null ? requestHeaderHolder.getUserUuid() : null;
            SalesLead.getEntityManager().createNativeQuery(
                "INSERT INTO sales_lead_stage_history (lead_uuid, from_stage, to_stage, changed_by) VALUES (?, ?, ?, ?)"
            )
            .setParameter(1, leadUuid)
            .setParameter(2, fromStage)
            .setParameter(3, toStage)
            .setParameter(4, changedBy)
            .executeUpdate();
            log.infof("Logged stage transition for lead %s: %s -> %s (by %s)", leadUuid, fromStage, toStage, changedBy);
        } catch (Exception e) {
            log.errorf("Failed to log stage transition for lead %s: %s", leadUuid, e.getMessage());
        }
    }

}
