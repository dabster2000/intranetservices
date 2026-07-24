package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared eligibility logic for the 5-year post-termination retention
 * machinery (spec §6.10): used by both the nightly retention batchlet and
 * the settings tab's dry-run preview endpoint, so what the admin sees in
 * the arming modal is exactly what the job would do.
 *
 * <p>Latest-status semantics: a user is an ex-employee when their LATEST
 * status row (statusdate ≤ today) is TERMINATED — deliberately NOT
 * {@code User.getUserStatus} / {@code StatusService.getLatestEmploymentStatus}
 * (both answer a different question; the spec calls out the known
 * divergence). The termination date drives the retention clock.</p>
 */
@JBossLog
@ApplicationScoped
public class EmployeeDocumentRetentionService {

    @Inject
    EntityManager em;

    /** One ex-employee whose documents are past the retention deadline. */
    public record RetentionCandidate(
            String userUuid,
            String displayName,
            LocalDate terminatedDate,
            LocalDate deleteAfter,
            long documentCount) { }

    /**
     * Every user who (a) still has {@code employee_documents} rows and
     * (b) whose latest status is TERMINATED with
     * {@code statusdate + retentionYears < today}. Ordered oldest
     * termination first — the nightly cap erases the longest-overdue
     * users first.
     */
    public List<RetentionCandidate> eligibleUsers(int retentionYears) {
        LocalDate today = LocalDate.now();

        @SuppressWarnings("unchecked")
        List<Object[]> userDocCounts = em.createQuery(
                        "select d.userUuid, count(d) from EmployeeDocument d group by d.userUuid")
                .getResultList();

        List<RetentionCandidate> eligible = new ArrayList<>();
        for (Object[] row : userDocCounts) {
            String userUuid = (String) row[0];
            long documentCount = (Long) row[1];

            UserStatus latest = UserStatus
                    .find("useruuid = ?1 and statusdate <= ?2 order by statusdate desc", userUuid, today)
                    .firstResult();
            if (latest == null || latest.getStatus() != StatusType.TERMINATED) continue;

            LocalDate terminatedDate = latest.getStatusdate();
            LocalDate deleteAfter = terminatedDate.plusYears(retentionYears);
            if (!deleteAfter.isBefore(today)) continue;

            eligible.add(new RetentionCandidate(
                    userUuid, resolveName(userUuid), terminatedDate, deleteAfter, documentCount));
        }
        eligible.sort(Comparator.comparing(RetentionCandidate::terminatedDate));
        return eligible;
    }

    private static String resolveName(String userUuid) {
        User user = User.findById(userUuid);
        if (user == null) return userUuid;
        String name = ((user.getFirstname() == null ? "" : user.getFirstname()) + " "
                + (user.getLastname() == null ? "" : user.getLastname())).trim();
        return name.isEmpty() ? (user.getUsername() == null ? userUuid : user.getUsername()) : name;
    }
}
