package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P1: the Pipeline tab's ops board — every expense the e-conomic machinery is
 * failing to move, in one list regardless of which flag tells the story:
 *
 * <ul>
 *   <li>{@code FAILED} — status UP_FAILED / NO_FILE / NO_USER (P0 requeue/close territory)</li>
 *   <li>{@code ORPHANED} — {@code is_orphaned}: the voucher vanished from e-conomic</li>
 *   <li>{@code SYNC_MISS} — {@code sync_miss_count > 0}: the nightly sync keeps missing the voucher</li>
 *   <li>{@code RETRY_EXHAUSTED} — retry_count reached the upload job's cap; nothing retries it anymore</li>
 * </ul>
 *
 * Terminal rows are excluded — the board is work, not history. That includes
 * BOOKED: prod carries ~3,350 successfully-booked rows with a stale
 * {@code is_orphaned} flag (nothing clears the flag once the voucher verifies),
 * and a booked row is never actionable here.
 */
@JBossLog
@Path("/expenses/pipeline")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"expenses:review"})
public class ExpensePipelineBoardResource {

    public record PipelineRowDTO(Expense expense, String employeeName,
                                 boolean hasVoucher, Integer syncMissCount,
                                 List<String> cohorts, int daysStuck) {}

    @GET
    @Path("/board")
    public List<PipelineRowDTO> board() {
        List<Expense> rows = Expense.list(
            "(status in ('UP_FAILED','NO_FILE','NO_USER') or isOrphaned = true or syncMissCount > 0) " +
            "and status <> 'DELETED' " +
            "and (state is null or state not in ('REJECTED','CLOSED_MANUAL','DELETED','BOOKED'))",
            Sort.by("datemodified", Sort.Direction.Ascending));

        Map<String, String> nameCache = new HashMap<>();
        return rows.stream().map(e -> {
            String name = e.getUseruuid() == null ? null : nameCache.computeIfAbsent(e.getUseruuid(), u -> {
                User user = User.findById(u);
                return user == null ? null : user.getFirstname() + " " + user.getLastname();
            });
            LocalDate anchor = e.getAttentionSince() != null ? e.getAttentionSince().toLocalDate()
                    : (e.getDatemodified() != null ? e.getDatemodified() : e.getDatecreated());
            int daysStuck = anchor == null ? 0 : (int) ChronoUnit.DAYS.between(anchor, LocalDate.now());
            return new PipelineRowDTO(e, name, e.getVouchernumber() > 0,
                    e.getSyncMissCount(), cohortsOf(e), daysStuck);
        }).toList();
    }

    /** Which stories this row tells. Package-private static for plain unit tests. */
    static List<String> cohortsOf(Expense e) {
        List<String> cohorts = new ArrayList<>();
        String status = e.getStatus();
        if ("UP_FAILED".equals(status) || "NO_FILE".equals(status) || "NO_USER".equals(status)) {
            cohorts.add("FAILED");
        }
        if (Boolean.TRUE.equals(e.getIsOrphaned())) {
            cohorts.add("ORPHANED");
        }
        if (e.getSyncMissCount() != null && e.getSyncMissCount() > 0) {
            cohorts.add("SYNC_MISS");
        }
        if (e.getRetryCount() != null && e.getRetryCount() >= ExpenseService.MAX_RETRY_ATTEMPTS) {
            cohorts.add("RETRY_EXHAUSTED");
        }
        return cohorts;
    }
}
