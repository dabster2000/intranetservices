package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.aggregates.users.danlon.dto.ReconciliationResult;
import dk.trustworks.intranet.domain.user.entity.DanlonAssignmentProposal;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detection-only safety net (spec §8). Re-derives "qualifying employment
 * with no OPEN Danløn and no open proposal" and raises MINT proposals via
 * {@link DanlonAssignmentService#proposeIfNeeded}; withdraws stale CLOSE
 * proposals whose triggering deletion was undone (spec §7). Never mints,
 * never closes — those stay HR-approved.
 */
@JBossLog
@ApplicationScoped
public class DanlonReconciliationService {

    @Inject
    DanlonAssignmentService assignmentService;

    /** Panel-open path: reconcile one company for one month. */
    public ReconciliationResult reconcileCompanyMonth(String companyUuid, LocalDate month) {
        LocalDate m = month.withDayOfMonth(1);
        int raised = 0, withdrawn = 0;

        for (Candidate c : findMissingIdActives(companyUuid, m)) {
            DanlonEventType ev = c.hasHistory() ? DanlonEventType.RE_EMPLOYMENT : DanlonEventType.FIRST_EMPLOYMENT;
            ProposalOutcome out = assignmentService.proposeIfNeeded(c.useruuid(), m, ev, companyUuid, "system-reconciliation");
            if (out == ProposalOutcome.CREATED || out == ProposalOutcome.REOPEN_PROPOSED) raised++;
        }
        for (String stalePid : findStaleCloseProposalUuids(companyUuid, m)) {
            if (withdraw(stalePid)) withdrawn++;
        }
        if (raised > 0 || withdrawn > 0)
            log.infof("Reconcile company=%s month=%s → raised=%d withdrawn=%d", companyUuid, m, raised, withdrawn);
        return new ReconciliationResult(raised, withdrawn);
    }

    /** Org-wide reconcile for a month (scheduled). */
    public ReconciliationResult reconcileMonth(LocalDate month) {
        int raised = 0, withdrawn = 0;
        for (String companyUuid : allCompanyUuids()) {
            ReconciliationResult r = reconcileCompanyMonth(companyUuid, month);
            raised += r.proposalsRaised();
            withdrawn += r.proposalsWithdrawn();
        }
        return new ReconciliationResult(raised, withdrawn);
    }

    /** Nightly at 02:30 (after the ~02:00 prod→staging refresh window). */
    @Scheduled(cron = "0 30 2 * * ?")
    void scheduledReconcile() {
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        ReconciliationResult r = reconcileMonth(month);
        log.infof("Scheduled Danløn reconcile for %s → raised=%d withdrawn=%d",
                month, r.proposalsRaised(), r.proposalsWithdrawn());
    }

    // ---- read helpers (each carries its own session/tx) ----

    record Candidate(String useruuid, boolean hasHistory) {}

    @Transactional
    List<Candidate> findMissingIdActives(String companyUuid, LocalDate month) {
        LocalDate monthEnd = month.plusMonths(1).minusDays(1);
        List<UserStatus> statuses = UserStatus.<UserStatus>find(
                "company.uuid = ?1 and statusdate <= ?2 order by useruuid asc, statusdate desc",
                companyUuid, monthEnd).list();
        Map<String, UserStatus> latestByUser = new LinkedHashMap<>();
        for (UserStatus s : statuses) latestByUser.putIfAbsent(s.getUseruuid(), s); // first per user = latest
        List<Candidate> result = new ArrayList<>();
        for (Map.Entry<String, UserStatus> e : latestByUser.entrySet()) {
            StatusType st = e.getValue().getStatus();
            if (st == StatusType.TERMINATED || st == StatusType.PREBOARDING) continue;
            if (UserDanlonHistory.findDanlonAsOf(e.getKey(), month) != null) continue; // already has OPEN id
            result.add(new Candidate(e.getKey(), UserDanlonHistory.hasHistory(e.getKey())));
        }
        return result;
    }

    @Transactional
    List<String> findStaleCloseProposalUuids(String companyUuid, LocalDate month) {
        List<DanlonAssignmentProposal> closes = DanlonAssignmentProposal.<DanlonAssignmentProposal>find(
                "companyUuid = ?1 and effectiveMonth = ?2 and intent = ?3 and status = ?4",
                companyUuid, month, ProposalIntent.CLOSE, ProposalStatus.PENDING).list();
        List<String> stale = new ArrayList<>();
        for (DanlonAssignmentProposal p : closes) if (isCloseStale(p)) stale.add(p.getUuid());
        return stale;
    }

    /** A CLOSE is stale if a qualifying (non-terminated/preboarding) status now exists
     *  again for the user/company as-of the month (the deletion was undone). */
    private boolean isCloseStale(DanlonAssignmentProposal p) {
        LocalDate monthEnd = p.getEffectiveMonth().plusMonths(1).minusDays(1);
        UserStatus latest = UserStatus.<UserStatus>find(
                "useruuid = ?1 and company.uuid = ?2 and statusdate <= ?3 order by statusdate desc",
                p.getUseruuid(), p.getCompanyUuid(), monthEnd).firstResult();
        return latest != null
                && latest.getStatus() != StatusType.TERMINATED
                && latest.getStatus() != StatusType.PREBOARDING;
    }

    @Transactional
    boolean withdraw(String proposalUuid) {
        DanlonAssignmentProposal p = DanlonAssignmentProposal.findById(proposalUuid);
        if (p == null || p.getStatus() != ProposalStatus.PENDING) return false;
        p.setStatus(ProposalStatus.WITHDRAWN);
        p.setResolvedDate(LocalDateTime.now());
        p.setResolvedBy("system-reconciliation");
        p.setResolutionNote("withdrawn by reconciliation: triggering condition no longer holds");
        log.infof("WITHDRAWN stale proposal %s", proposalUuid);
        return true;
    }

    @Transactional
    List<String> allCompanyUuids() {
        return Company.<Company>listAll().stream().map(Company::getUuid).toList();
    }
}
