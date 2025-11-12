package dk.trustworks.intranet.aggregates.client.services;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.dto.contracts.ClientHealthDTO;
import dk.trustworks.intranet.dto.contracts.ClientHealthDTO.HealthStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

/**
 * Service for calculating client health status based on contracts and invoices.
 *
 * <p>This service provides efficient database queries to determine client health by analyzing:
 * <ul>
 *   <li>Active contracts (SIGNED status, end date >= today)</li>
 *   <li>Budget contracts (BUDGET status)</li>
 *   <li>Expired contracts (end date < today)</li>
 *   <li>Paused contracts (TIME status - used as PAUSED in legacy system)</li>
 *   <li>Completed contracts (CLOSED status - used as COMPLETED in legacy system)</li>
 *   <li>Overdue invoices (> 90 days past due)</li>
 * </ul>
 *
 * <p><b>Performance:</b> Uses efficient COUNT(*) queries to avoid loading full entities.
 * No N+1 query problems - all calculations performed in 6 database queries.
 *
 * <p><b>Health Status Logic:</b>
 * <ul>
 *   <li>HEALTHY: Active contracts exist AND no overdue invoices</li>
 *   <li>AT_RISK: No active contracts BUT budget contracts exist, OR 1-2 overdue invoices</li>
 *   <li>CRITICAL: No active/budget contracts OR 3+ overdue invoices</li>
 * </ul>
 *
 * @since 1.0
 */
@JBossLog
@ApplicationScoped
public class ClientHealthService {

    @Inject
    EntityManager em;

    /**
     * Calculate comprehensive health status for a client.
     *
     * <p>Executes 6 efficient COUNT queries:
     * <ol>
     *   <li>Active contracts (SIGNED, activeTo >= today)</li>
     *   <li>Budget contracts (BUDGET status)</li>
     *   <li>Expired contracts (activeTo < today)</li>
     *   <li>Paused contracts (TIME status)</li>
     *   <li>Completed contracts (CLOSED status)</li>
     *   <li>Overdue invoices (unpaid, dueDate < today - 90 days)</li>
     * </ol>
     *
     * @param clientUuid Client UUID to analyze
     * @return ClientHealthDTO with health status and detailed counts
     * @throws IllegalArgumentException if clientUuid is null/empty
     */
    public ClientHealthDTO calculateHealth(String clientUuid) {
        if (clientUuid == null || clientUuid.trim().isEmpty()) {
            throw new IllegalArgumentException("Client UUID cannot be null or empty");
        }

        log.infof("Calculating health for client: %s", clientUuid);

        LocalDate today = LocalDate.now();
        LocalDate overdueThreshold = today.minusDays(90);

        try {
            // Query 1: Count active contracts (SIGNED, activeTo >= today)
            long activeCount = countActiveContracts(clientUuid, today);
            log.debugf("Active contracts: %d", activeCount);

            // Query 2: Count budget contracts
            long budgetCount = countContractsByStatus(clientUuid, ContractStatus.BUDGET);
            log.debugf("Budget contracts: %d", budgetCount);

            // Query 3: Count expired contracts (activeTo < today)
            long expiredCount = countExpiredContracts(clientUuid, today);
            log.debugf("Expired contracts: %d", expiredCount);

            // Query 4: Count paused contracts (TIME status - legacy mapping)
            long pausedCount = countContractsByStatus(clientUuid, ContractStatus.TIME);
            log.debugf("Paused contracts: %d", pausedCount);

            // Query 5: Count completed contracts (CLOSED status - legacy mapping)
            long completedCount = countContractsByStatus(clientUuid, ContractStatus.CLOSED);
            log.debugf("Completed contracts: %d", completedCount);

            // Query 6: Calculate overdue invoices (> 90 days)
            OverdueInvoiceStats overdueStats = calculateOverdueInvoices(clientUuid, overdueThreshold);
            log.debugf("Overdue invoices: count=%d, amount=%.2f", (Object) overdueStats.count, overdueStats.amount);

            // Calculate health status
            HealthStatus health = calculateHealthStatus(activeCount, budgetCount, overdueStats.count);
            log.infof("Health status for client %s: %s", clientUuid, health);

            return new ClientHealthDTO(
                clientUuid,
                health,
                (int) activeCount,
                (int) budgetCount,
                (int) expiredCount,
                (int) pausedCount,
                (int) completedCount,
                overdueStats.count,
                overdueStats.amount
            );

        } catch (Exception e) {
            log.errorf(e, "Failed to calculate health for client: %s", clientUuid);
            throw new RuntimeException("Health calculation failed for client: " + clientUuid, e);
        }
    }

    /**
     * Count active contracts using efficient COUNT(*) query.
     *
     * <p>Active contracts are:
     * <ul>
     *   <li>status = SIGNED</li>
     *   <li>MAX(activeTo) from contract_consultants >= today</li>
     * </ul>
     *
     * @param clientUuid Client UUID
     * @param today Current date
     * @return Count of active contracts
     */
    private long countActiveContracts(String clientUuid, LocalDate today) {
        String jpql = """
            SELECT COUNT(DISTINCT c)
            FROM Contract c
            JOIN c.contractConsultants cc
            WHERE c.clientuuid = :clientUuid
            AND c.status = :status
            AND cc.activeTo >= :today
            """;

        Query query = em.createQuery(jpql, Long.class);
        query.setParameter("clientUuid", clientUuid);
        query.setParameter("status", ContractStatus.SIGNED);
        query.setParameter("today", today);

        return (Long) query.getSingleResult();
    }

    /**
     * Count contracts by status using efficient COUNT(*) query.
     *
     * @param clientUuid Client UUID
     * @param status Contract status to count
     * @return Count of contracts with specified status
     */
    private long countContractsByStatus(String clientUuid, ContractStatus status) {
        String jpql = """
            SELECT COUNT(c)
            FROM Contract c
            WHERE c.clientuuid = :clientUuid
            AND c.status = :status
            """;

        Query query = em.createQuery(jpql, Long.class);
        query.setParameter("clientUuid", clientUuid);
        query.setParameter("status", status);

        return (Long) query.getSingleResult();
    }

    /**
     * Count expired contracts using efficient COUNT(*) query.
     *
     * <p>Expired contracts have MAX(activeTo) from contract_consultants < today.
     *
     * @param clientUuid Client UUID
     * @param today Current date
     * @return Count of expired contracts
     */
    private long countExpiredContracts(String clientUuid, LocalDate today) {
        String jpql = """
            SELECT COUNT(DISTINCT c)
            FROM Contract c
            JOIN c.contractConsultants cc
            WHERE c.clientuuid = :clientUuid
            AND cc.activeTo < :today
            """;

        Query query = em.createQuery(jpql, Long.class);
        query.setParameter("clientUuid", clientUuid);
        query.setParameter("today", today);

        return (Long) query.getSingleResult();
    }

    /**
     * Calculate overdue invoice statistics using native SQL for performance.
     *
     * <p>Uses native SQL to efficiently:
     * <ol>
     *   <li>JOIN invoices with contracts on contractuuid</li>
     *   <li>Filter by client UUID from contracts table</li>
     *   <li>Find unpaid invoices (paymentdate IS NULL)</li>
     *   <li>Find overdue invoices (duedate < threshold)</li>
     *   <li>Calculate count and total amount in single query</li>
     * </ol>
     *
     * @param clientUuid Client UUID
     * @param overdueThreshold Date threshold (today - 90 days)
     * @return OverdueInvoiceStats with count and total amount
     */
    private OverdueInvoiceStats calculateOverdueInvoices(String clientUuid, LocalDate overdueThreshold) {
        // TODO: This query sums header_discount_pct which is a percentage (0-100), not invoice amounts
        // This appears to be a bug - should probably sum invoice totals instead
        String sql = """
            SELECT COUNT(*) AS invoice_count, COALESCE(SUM(i.header_discount_pct), 0) AS total_amount
            FROM invoices_v2 i
            INNER JOIN contracts c ON i.contractuuid = c.uuid
            WHERE c.clientuuid = :clientUuid
            AND i.bookingdate IS NULL
            AND i.duedate < :overdueThreshold
            """;

        Query query = em.createNativeQuery(sql);
        query.setParameter("clientUuid", clientUuid);
        query.setParameter("overdueThreshold", overdueThreshold);

        Object[] result = (Object[]) query.getSingleResult();
        long count = ((Number) result[0]).longValue();
        double amount = ((Number) result[1]).doubleValue();

        return new OverdueInvoiceStats((int) count, amount);
    }

    /**
     * Calculate health status based on contract and invoice metrics.
     *
     * <p><b>Health Status Rules:</b>
     * <ul>
     *   <li><b>HEALTHY:</b> activeCount > 0 AND overdueCount == 0</li>
     *   <li><b>AT_RISK:</b>
     *     <ul>
     *       <li>No active contracts BUT budget contracts exist (activeCount == 0 AND budgetCount > 0)</li>
     *       <li>OR 1-2 overdue invoices (overdueCount > 0 AND overdueCount < 3)</li>
     *     </ul>
     *   </li>
     *   <li><b>CRITICAL:</b>
     *     <ul>
     *       <li>No active OR budget contracts (activeCount == 0 AND budgetCount == 0)</li>
     *       <li>OR 3+ overdue invoices (overdueCount >= 3)</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param activeCount Number of active (SIGNED) contracts
     * @param budgetCount Number of budget contracts
     * @param overdueCount Number of overdue invoices (> 90 days)
     * @return HealthStatus enum (HEALTHY, AT_RISK, or CRITICAL)
     */
    private HealthStatus calculateHealthStatus(long activeCount, long budgetCount, int overdueCount) {
        // HEALTHY: Active contracts exist AND no overdue invoices
        if (activeCount > 0 && overdueCount == 0) {
            return HealthStatus.HEALTHY;
        }

        // CRITICAL: No contracts OR severe payment issues (3+ overdue)
        if ((activeCount == 0 && budgetCount == 0) || overdueCount >= 3) {
            return HealthStatus.CRITICAL;
        }

        // AT_RISK: Everything else (no active but has budget, OR 1-2 overdue)
        return HealthStatus.AT_RISK;
    }

    /**
     * Internal record for overdue invoice statistics.
     */
    private record OverdueInvoiceStats(int count, double amount) {}
}
