package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRateDTO;
import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelEconomicsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelEconomicsItemDTO;
import dk.trustworks.intranet.aggregates.finance.usecases.CareerLevelEconomicsUseCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

/**
 * The rate KPI on the account page (CRM spec §4.3): what this account pays us an hour,
 * against what an hour costs us.
 *
 * <h2>The weighted rate</h2>
 * Hours-weighted across every consultant on a running contract:
 * {@code Σ(rate × hours) / Σ(hours)}. A straight average would let a one-day-a-week
 * advisor at 1 800 kr hide four full-time consultants at 900. Consultants with a zero
 * rate are excluded rather than counted as zero — a zero row is a data problem (V572
 * exists because rate-zero rows are real), and averaging one in would drag the whole
 * account's rate down for a reason that has nothing to do with pricing.
 *
 * <h2>Why break-even is filtered HERE and not in the UI</h2>
 * Break-even comes from {@code fact_minimum_viable_rate}: salaries, pension, statutory
 * costs and overhead per career level. It is salary-derived, and this endpoint is reached
 * from a page every one of ~100 employees can open. So the service returns
 * {@code breakEven = null} and {@code target = null} unless the caller holds a cost role —
 * the figure never crosses the wire. Hiding it in the frontend would leave it sitting in
 * the JSON for anyone who opened the network tab, which is how the BFF User leak worked.
 *
 * <p>The cost roles are the same four the CXO cost endpoints use
 * ({@code CXO_SALARY_ROLES} in the frontend): ADMIN, PARTNER, CXO, TECHPARTNER.
 */
@JBossLog
@ApplicationScoped
public class AccountRateService {

    /** Roles that may see a salary-derived cost figure. Mirrors the frontend CXO_SALARY_ROLES. */
    public static final List<String> COST_ROLES = List.of("ADMIN", "PARTNER", "CXO", "TECHPARTNER");

    /** The margin the firm prices at, on top of break-even. Same 15 % the pricing tab uses. */
    private static final double TARGET_MARGIN = 1.15;

    @Inject
    EntityManager em;

    @Inject
    CareerLevelEconomicsUseCase careerLevelEconomics;

    /**
     * @param maySeeCost whether the caller holds one of {@link #COST_ROLES}; decided by the
     *                   resource from the JWT, passed in rather than read here so the rule
     *                   is testable without a security context
     */
    public AccountRateDTO forClient(String clientUuid, boolean maySeeCost) {
        Double weighted = weightedRate(clientUuid);
        if (!maySeeCost) {
            return new AccountRateDTO(weighted, null, null);
        }
        Double breakEven = firmBreakEven();
        Double target = breakEven == null ? null : round(breakEven * TARGET_MARGIN);
        return new AccountRateDTO(weighted, breakEven, target);
    }

    /**
     * Hours-weighted hourly rate over consultants on contracts that are running today.
     * Null when nothing is running: an average over no contracts is nothing, and a zero
     * would render as a catastrophic rate rather than as an absence.
     */
    Double weightedRate(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return null;
        }
        Query query = em.createNativeQuery("""
                select sum(cc.rate * cc.hours), sum(cc.hours)
                  from contract_consultants cc
                  join contracts c on c.uuid = cc.contractuuid
                 where c.clientuuid = :clientUuid
                   and c.status in ('SIGNED', 'TIME', 'BUDGET')
                   and cc.rate > 0
                   and cc.hours > 0
                   and (cc.activefrom is null or cc.activefrom <= :today)
                   and (cc.activeto is null or cc.activeto >= :today)
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setParameter("today", LocalDate.now());

        Object[] row = (Object[]) query.getSingleResult();
        if (row == null || row[0] == null || row[1] == null) {
            return null;
        }
        double weightedSum = ((Number) row[0]).doubleValue();
        double hours = ((Number) row[1]).doubleValue();
        return hours <= 0 ? null : round(weightedSum / hours);
    }

    /**
     * One firm-wide break-even rate: the consultant-count-weighted mean of the per-career-
     * level break-even rates.
     *
     * <p>Weighted by head count rather than a flat mean, because the levels are very
     * differently sized and a flat mean would let a two-person level move the firm's number
     * as much as a thirty-person one. Levels whose break-even could not be computed (no net
     * available hours, no utilisation) are skipped rather than treated as zero.
     */
    Double firmBreakEven() {
        CareerLevelEconomicsDTO economics = careerLevelEconomics.getCareerLevelEconomics(null);
        if (economics == null || economics.getCareerLevels() == null) {
            return null;
        }
        double weighted = 0;
        int people = 0;
        for (CareerLevelEconomicsItemDTO item : economics.getCareerLevels()) {
            if (item == null || item.getBreakEvenRateTarget() == null || item.getConsultantCount() <= 0) {
                continue;
            }
            weighted += item.getBreakEvenRateTarget() * item.getConsultantCount();
            people += item.getConsultantCount();
        }
        return people == 0 ? null : round(weighted / people);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
