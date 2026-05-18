package dk.trustworks.intranet.expenseservice.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewListItemDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
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
            @QueryParam("state") String state,
            @QueryParam("fromDate") String fromDate,
            @QueryParam("toDate") String toDate) {

        LocalDate from = fromDate != null ? LocalDate.parse(fromDate) : null;
        LocalDate to = toDate != null ? LocalDate.parse(toDate) : null;

        List<Expense> rows = switch (state == null ? "PENDING_HR" : state) {
            case "PENDING_HR" -> listReviewQueue(
                    List.of("PENDING_HR"),
                    Sort.by("datemodified", Sort.Direction.Ascending),
                    from, to);
            case "AWAITING_EMPLOYEE" -> listReviewQueue(
                    List.of("NEEDS_FIX", "NEEDS_JUSTIFICATION", "HR_SENT_BACK"),
                    Sort.by("datemodified", Sort.Direction.Ascending),
                    from, to);
            case "HR_SENT_BACK" -> listReviewQueue(
                    List.of("HR_SENT_BACK"),
                    Sort.by("datemodified", Sort.Direction.Descending),
                    from, to);
            case "STUCK" -> listStuckQueue(
                    Sort.by("datemodified", Sort.Direction.Ascending),
                    from, to);
            default -> throw new BadRequestException(
                    "state must be PENDING_HR, AWAITING_EMPLOYEE, HR_SENT_BACK, or STUCK");
        };

        return rows.stream().map(this::toDTO).toList();
    }

    private List<Expense> listReviewQueue(List<String> states, Sort sort, LocalDate from, LocalDate to) {
        StringBuilder query = new StringBuilder("reviewState in ?1");
        List<Object> params = new ArrayList<>();
        params.add(states);
        appendExpenseDateFilters(query, params, from, to);
        return Expense.list(query.toString(), sort, params.toArray());
    }

    private List<Expense> listStuckQueue(Sort sort, LocalDate from, LocalDate to) {
        StringBuilder query = new StringBuilder("reviewState in ?1 and datemodified < ?2");
        List<Object> params = new ArrayList<>();
        params.add(List.of("NEEDS_FIX", "NEEDS_JUSTIFICATION"));
        params.add(LocalDate.now().minusDays(7));
        appendExpenseDateFilters(query, params, from, to);
        return Expense.list(query.toString(), sort, params.toArray());
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
