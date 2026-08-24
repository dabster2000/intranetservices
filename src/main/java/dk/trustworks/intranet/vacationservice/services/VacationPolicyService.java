package dk.trustworks.intranet.vacationservice.services;

import dk.trustworks.intranet.vacationservice.dto.CreateVacationPolicyRequest;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PolicyRate;
import dk.trustworks.intranet.vacationservice.model.VacationPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Temporal accrual rates. Forward-only by design: a new row must start on the
 * first of a future month, and existing rows are never edited or deleted —
 * history stays exactly as it was when balances were computed against it.
 */
@JBossLog
@ApplicationScoped
public class VacationPolicyService {

    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    public List<VacationPolicy> list() {
        return VacationPolicy.listOrdered();
    }

    public List<PolicyRate> rates() {
        return list().stream()
                .map(p -> new PolicyRate(p.getEffectiveFrom(), p.getFerieDaysPerMonth(), p.getFeriefridageDaysPerMonth()))
                .toList();
    }

    @Transactional
    public VacationPolicy create(CreateVacationPolicyRequest request, String actorUuid) {
        if (request == null || request.effectiveFrom() == null) {
            throw new BadRequestException("effectiveFrom is required");
        }
        if (request.effectiveFrom().getDayOfMonth() != 1) {
            throw new BadRequestException("Rates take effect on the first of a month");
        }
        LocalDate today = LocalDate.now(COPENHAGEN);
        if (!request.effectiveFrom().isAfter(today)) {
            throw new BadRequestException("Rates can only change forward in time — pick a future month");
        }
        validateRate(request.ferieDaysPerMonth(), "ferieDaysPerMonth");
        validateRate(request.feriefridageDaysPerMonth(), "feriefridageDaysPerMonth");
        if (VacationPolicy.count("effectiveFrom", request.effectiveFrom()) > 0) {
            throw new BadRequestException("A rate change already exists for " + request.effectiveFrom());
        }

        VacationPolicy policy = new VacationPolicy();
        policy.setUuid(UUID.randomUUID().toString());
        policy.setEffectiveFrom(request.effectiveFrom());
        policy.setFerieDaysPerMonth(request.ferieDaysPerMonth());
        policy.setFeriefridageDaysPerMonth(request.feriefridageDaysPerMonth());
        policy.setCreatedBy(actorUuid);
        policy.persist();
        log.infof("vacation: policy change by %s — from %s: ferie %.2f/md, feriefridage %.2f/md",
                actorUuid, request.effectiveFrom(), request.ferieDaysPerMonth(), request.feriefridageDaysPerMonth());
        return policy;
    }

    private static void validateRate(double rate, String field) {
        if (rate < 0 || rate > 10) {
            throw new BadRequestException(field + " must be between 0 and 10 days per month");
        }
    }
}
