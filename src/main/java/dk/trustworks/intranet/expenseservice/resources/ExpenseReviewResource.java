package dk.trustworks.intranet.expenseservice.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewListItemDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import dk.trustworks.intranet.expenseservice.services.AIConfigSnapshot;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Path("/expenses/review")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExpenseReviewResource {

    private static final ObjectReader AI_PRESET_REASONS_READER =
            new ObjectMapper().readerForListOf(String.class);
    private static final String STATUS_DELETED = "DELETED";

    @Inject
    AIConfigSnapshot aiConfigSnapshot;

    @GET
    @Path("/preset-reasons")
    @RolesAllowed({"expenses:review"})
    public List<String> presetReasons() {
        String raw = aiConfigSnapshot.getParameter("hr_approve_reason_presets", "[]");
        try {
            return AI_PRESET_REASONS_READER.readValue(raw);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    @GET
    @RolesAllowed({"expenses:review"})
    public List<ExpenseReviewListItemDTO> queue(
            @QueryParam("segment") String segment,
            @QueryParam("state") String legacyState, // back-compat until Phase 2 re-points the BFF
            @QueryParam("fromDate") String fromDate,
            @QueryParam("toDate") String toDate) {

        LocalDate from = fromDate != null ? LocalDate.parse(fromDate) : null;
        LocalDate to = toDate != null ? LocalDate.parse(toDate) : null;

        // Forward param is `segment`; fall back to the legacy param so the current FE still works.
        String seg = segment != null ? segment : legacyState;
        if (seg == null) seg = "ACCOUNTING";
        // Backward-compat: map the pre-Phase-1 review_state labels to the new segments.
        switch (seg) {
            case "PENDING_HR"        -> seg = "ACCOUNTING";
            case "AWAITING_EMPLOYEE",
                 "HR_SENT_BACK"      -> seg = "EMPLOYEE";
            case "STUCK"             -> seg = "OVERDUE";
            default -> { /* already a new segment or invalid */ }
        }

        List<Expense> rows = switch (seg) {
            // Your decision: accounting-owned exceptions.
            case "ACCOUNTING" -> listInbox(ExpenseStateDeriver.OWNER_ACCOUNTING, null, from, to);
            // Waiting on employee: read-only context for accounting.
            case "EMPLOYEE"   -> listInbox(ExpenseStateDeriver.OWNER_EMPLOYEE, null, from, to);
            // Overdue: anything in the inbox older than 7 days (replaces "STUCK").
            case "OVERDUE"    -> listInbox(null, LocalDate.now().minusDays(7), from, to);
            // All open exceptions.
            case "ALL"        -> listInbox(null, null, from, to);
            default -> throw new BadRequestException(
                    "segment must be ACCOUNTING, EMPLOYEE, OVERDUE, or ALL");
        };

        return rows.stream().map(this::toDTO).toList();
    }

    /**
     * One inbox query over {@code state=NEEDS_ATTENTION}. {@code owner} filters by
     * attention_owner when non-null; {@code olderThan} adds a datemodified ceiling
     * (overdue) when non-null. Always excludes DELETED defensively.
     */
    private List<Expense> listInbox(String owner, LocalDate olderThan, LocalDate from, LocalDate to) {
        StringBuilder query = new StringBuilder("state = ?1 and status <> ?2");
        List<Object> params = new ArrayList<>();
        params.add(ExpenseStateDeriver.NEEDS_ATTENTION);
        params.add(STATUS_DELETED);
        if (owner != null) {
            query.append(" and attentionOwner = ?").append(params.size() + 1);
            params.add(owner);
        }
        if (olderThan != null) {
            query.append(" and datemodified < ?").append(params.size() + 1);
            params.add(olderThan);
        }
        appendExpenseDateFilters(query, params, from, to);
        return Expense.list(query.toString(), Sort.by("datemodified", Sort.Direction.Ascending), params.toArray());
    }

    private void appendExpenseDateFilters(StringBuilder query, List<Object> params, LocalDate from, LocalDate to) {
        if (from != null) {
            query.append(" and expensedate >= ?").append(params.size() + 1);
            params.add(from);
        }
        if (to != null) {
            query.append(" and expensedate <= ?").append(params.size() + 1);
            params.add(to);
        }
    }

    private ExpenseReviewListItemDTO toDTO(Expense e) {
        User u = User.findById(e.getUseruuid());
        String name = u != null ? (u.getFirstname() + " " + u.getLastname()) : null;
        // photoUrl is fetched separately by the frontend via /files/photo/{useruuid}; leave null here
        String photo = null;
        LocalDate base = e.getDatemodified() != null ? e.getDatemodified() : e.getDatecreated();
        int days = base != null ? (int) ChronoUnit.DAYS.between(base, LocalDate.now()) : 0;
        return new ExpenseReviewListItemDTO(e, name, photo,
                e.getEmployeeJustification(), e.getAiRuleId(), e.getAiRuleIds(), days);
    }
}
