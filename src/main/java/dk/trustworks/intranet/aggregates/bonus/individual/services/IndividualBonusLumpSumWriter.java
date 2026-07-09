package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MaterializedPayoutCommand;
import dk.trustworks.intranet.aggregates.users.services.SalaryLumpSumService;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.RoundingMode;
import java.util.UUID;

/**
 * Writes a single individual-bonus lump sum AND its reproducibility snapshot in ONE transaction
 * (REQUIRES_NEW) so that a unique-constraint race on {@code salary_lump_sum.source_reference} (or the
 * snapshot's {@code individual_bonus_payout.source_reference}) rolls back only THIS payout — never the
 * whole materialisation batch. Called through a CDI proxy (not intra-bean) so the REQUIRES_NEW boundary
 * actually applies.
 * <p>
 * Idempotent: an existing sourceReference is a no-op for each row (the lump sum returns false; a present
 * snapshot is skipped); a concurrent insert that loses the race surfaces as a PersistenceException which
 * the caller ({@link IndividualBonusPayoutService#materializeDue}) treats as benign. Amounts are GROSS
 * (D3) and always strictly positive here — the payout gate upstream skips zero and NEGATIVE (CLAWBACK)
 * amounts (Danløn cannot export a negative løntype-41 line).
 */
@JBossLog
@ApplicationScoped
public class IndividualBonusLumpSumWriter {

    @Inject SalaryLumpSumService salaryLumpSumService;

    /**
     * Idempotently write the lump sum and freeze its audit snapshot. The snapshot ({@code spec_json} +
     * resolved inputs) makes a past payout replayable even after the rule is later edited/soft-deleted; it
     * is written in the SAME transaction as the lump sum so a paid row always carries its snapshot.
     *
     * @return true if a NEW lump sum was written; false if one already existed for this sourceRef.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean writeIfAbsent(MaterializedPayoutCommand cmd) {
        boolean lumpSumExists = SalaryLumpSum.find("sourceReference", cmd.sourceReference())
                .firstResultOptional().isPresent();
        if (!lumpSumExists) {
            SalaryLumpSum ls = new SalaryLumpSum();
            ls.setUuid(UUID.randomUUID().toString());
            ls.setUseruuid(cmd.userUuid());
            ls.setSalaryType(LumpSumSalaryType.INDIVIDUAL_PROD_BONUS);
            ls.setLumpSum(cmd.amount().setScale(0, RoundingMode.HALF_UP).doubleValue());
            ls.setPension(cmd.pension());
            ls.setMonth(cmd.month());
            ls.setDescription(cmd.ruleName());
            ls.setSourceReference(cmd.sourceReference());
            salaryLumpSumService.create(ls);
            log.infof("Created INDIVIDUAL_PROD_BONUS for %s: %s (%s)",
                    cmd.userUuid(), cmd.amount(), cmd.sourceReference());
        }

        // Reproducibility snapshot — idempotent on source_reference, independent of whether the lump sum
        // was newly created or already present (so a run that previously crashed after the lump sum but
        // before the snapshot backfills it).
        if (IndividualBonusPayout.find("sourceReference", cmd.sourceReference()).firstResultOptional().isEmpty()) {
            IndividualBonusPayout.snapshot(cmd).persist();
        }
        return !lumpSumExists;
    }
}
