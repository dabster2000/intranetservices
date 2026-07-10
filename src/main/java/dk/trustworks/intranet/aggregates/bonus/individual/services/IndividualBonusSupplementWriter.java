package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.users.services.SalarySupplementService;
import dk.trustworks.intranet.domain.user.entity.SalarySupplement;
import dk.trustworks.intranet.userservice.model.enums.SalarySupplementType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes / maintains the single recurring PREPAID {@link SalarySupplement} that delivers a
 * PREPAID_SUPPLEMENT rule's fixed monthly advance for one fiscal year, in its OWN transaction
 * (REQUIRES_NEW) so a failure trims/writes only THIS supplement, never the whole materialisation batch.
 * Called through a CDI proxy (not intra-bean) so the REQUIRES_NEW boundary actually applies.
 * <p>
 * Idempotency is backed by the {@code salary_supplement.source_reference} UNIQUE index (migration V392),
 * mirroring {@code salary_lump_sum.source_reference} — NOT a free-text description marker. The writer
 * finds the existing supplement by sourceReference and updates it in place; on a create/create race the
 * losing insert throws a unique-constraint violation which the CALLER
 * ({@code IndividualBonusPayoutService.maintainPrepaidSupplement}) treats as benign.
 */
@JBossLog
@ApplicationScoped
public class IndividualBonusSupplementWriter {

    private static final int MAX_DESCRIPTION = 255;

    @Inject SalarySupplementService salarySupplementService;

    /** Stable idempotency key persisted in salary_supplement.source_reference (unique index). */
    static String sourceRef(String ruleUuid, int fyYear) {
        return "individual:" + ruleUuid + ":supplement:" + fyYear + "_" + (fyYear + 1);
    }

    /**
     * Find-or-update the PREPAID supplement for one rule × fiscal year, keyed on {@code sourceRef}. The
     * window ({@code fromMonth..toMonth}) is supplied by the caller from the termination- and
     * effective-window-aware projection, so an early leave trims {@code toMonth} on the next run.
     *
     * @return true if a NEW supplement was created; false if an existing one was updated in place.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean upsertPrepaidSupplement(String userUuid, BigDecimal monthlyValue, LocalDate fromMonth,
                                           LocalDate toMonth, String ruleName, String sourceRef, boolean pension) {
        Optional<SalarySupplement> existing = SalarySupplement
                .<SalarySupplement>find("sourceReference", sourceRef).firstResultOptional();
        boolean created = existing.isEmpty();
        SalarySupplement s = existing.orElseGet(SalarySupplement::new);
        if (created) s.setUuid(UUID.randomUUID().toString());
        s.setUseruuid(userUuid);
        s.setType(SalarySupplementType.PREPAID);
        s.setValue(monthlyValue.setScale(0, RoundingMode.HALF_UP).doubleValue());
        s.setWithPension(pension);
        s.setFromMonth(fromMonth.withDayOfMonth(1));
        s.setToMonth(toMonth != null ? toMonth.withDayOfMonth(1) : null);
        s.setSourceReference(sourceRef);
        s.setDescription(truncate(ruleName, MAX_DESCRIPTION));
        salarySupplementService.create(s);

        log.infof("%s PREPAID supplement for %s: %s..%s = %s (%s)",
                created ? "Created" : "Updated", userUuid, s.getFromMonth(), s.getToMonth(), s.getValue(), sourceRef);
        return created;
    }

    private static String truncate(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
