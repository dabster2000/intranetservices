package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.users.services.SalaryLumpSumService;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Writes a single individual-bonus lump sum in its OWN transaction (REQUIRES_NEW) so that a
 * unique-constraint race on {@code salary_lump_sum.source_reference} rolls back only THIS payout —
 * never the whole materialisation batch. Called through a CDI proxy (not intra-bean) so the
 * REQUIRES_NEW boundary actually applies.
 * <p>
 * Idempotent: an existing sourceReference is a no-op (returns false); a concurrent insert that loses
 * the race surfaces as a PersistenceException which the caller
 * ({@link IndividualBonusPayoutService#materializeDue}) treats as benign. Amounts are GROSS (D3).
 */
@JBossLog
@ApplicationScoped
public class IndividualBonusLumpSumWriter {

    @Inject SalaryLumpSumService salaryLumpSumService;

    /** @return true if a new lump sum was written; false if one already existed for this sourceRef. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean writeIfAbsent(String userUuid, BigDecimal amount, LocalDate month,
                                 String description, String sourceRef, boolean pension) {
        if (SalaryLumpSum.find("sourceReference", sourceRef).firstResultOptional().isPresent()) {
            return false;
        }
        SalaryLumpSum ls = new SalaryLumpSum();
        ls.setUuid(UUID.randomUUID().toString());
        ls.setUseruuid(userUuid);
        ls.setSalaryType(LumpSumSalaryType.INDIVIDUAL_PROD_BONUS);
        // NEGATIVE amounts are accepted here on purpose: a negative Danløn '41' line = a bonus CLAWBACK
        // deduction from a year-end true-up / final settlement. Negatives only ever reach this point when
        // the rule's negativeHandling is the explicit CLAWBACK; the default WRITE_OFF clamps a negative
        // true-up to 0 upstream in IndividualBonusScheduleService, and the payout gate only forwards
        // negatives for TRUEUP / FINAL_SETTLEMENT kinds.
        // TODO(D1): confirm Danløn løntype 41 accepts a negative line; otherwise route CLAWBACK to a
        // manual deduction rather than a negative lump sum.
        ls.setLumpSum(amount.setScale(0, RoundingMode.HALF_UP).doubleValue());
        ls.setPension(pension);
        ls.setMonth(month);
        ls.setDescription(description);
        ls.setSourceReference(sourceRef);
        salaryLumpSumService.create(ls);
        log.infof("Created INDIVIDUAL_PROD_BONUS for %s: %s (%s)", userUuid, amount, sourceRef);
        return true;
    }
}
