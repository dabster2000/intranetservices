package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewListItemDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Path("/expenses/review")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExpenseReviewResource {

    @GET
    @RolesAllowed({"expenses:review"})
    public List<ExpenseReviewListItemDTO> queue(
            @QueryParam("state") String state,
            @QueryParam("fromDate") String fromDate,
            @QueryParam("toDate") String toDate) {

        LocalDate from = fromDate != null ? LocalDate.parse(fromDate) : LocalDate.now().minusMonths(6);
        LocalDate to = toDate != null ? LocalDate.parse(toDate) : LocalDate.now();

        List<Expense> rows = switch (state == null ? "PENDING_HR" : state) {
            case "PENDING_HR" -> Expense.list(
                    "reviewState = ?1 and expensedate between ?2 and ?3",
                    Sort.by("datemodified", Sort.Direction.Ascending),
                    "PENDING_HR", from, to);
            case "HR_SENT_BACK" -> Expense.list(
                    "reviewState = ?1 and expensedate between ?2 and ?3",
                    Sort.by("datemodified", Sort.Direction.Descending),
                    "HR_SENT_BACK", from, to);
            case "STUCK" -> Expense.list(
                    "reviewState in ?1 and datemodified < ?2",
                    Sort.by("datemodified", Sort.Direction.Ascending),
                    List.of("NEEDS_FIX", "NEEDS_JUSTIFICATION"),
                    LocalDate.now().minusDays(7));
            default -> throw new BadRequestException(
                    "state must be PENDING_HR, HR_SENT_BACK, or STUCK");
        };

        return rows.stream().map(this::toDTO).toList();
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
