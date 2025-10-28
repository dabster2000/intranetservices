package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseCategory;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.resteasy.annotations.jaxrs.PathParam;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@JBossLog
@Path("/expenses")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"USER", "SYSTEM"})
public class ExpenseResource {

    @Inject
    ExpenseService expenseService;

    @Inject
    UserService userService;

    @Inject
    ExpenseFileService expenseFileService;

    @Inject
    EntityManager em;

    @GET
    @Path("/{uuid}")
    public Expense findByUuid(@PathParam("uuid") String uuid) {
        return Expense.findById(uuid);
    }

    @GET
    @Path("/file/{uuid}")
    public ExpenseFile getFileById(@PathParam("uuid") String uuid) {
        return expenseFileService.getFileById(uuid);
    }

    @GET
    @Path("/user/{useruuid}")
    public List<Expense> findByUser(@PathParam("useruuid") String useruuid, @QueryParam("limit") String limit, @QueryParam("page") String page) {
        int pageInt = Integer.parseInt(page);
        int limitInt = Integer.parseInt(limit);
        return Expense.find("useruuid = ?1 and status not like ?2", Sort.by("expensedate").descending(), useruuid, "DELETED").page(Page.of(pageInt, limitInt)).list();
    }

    public List<Expense> findByUser(@PathParam("useruuid") String useruuid) {
        return Expense.find("useruuid = ?1 and status not like ?2", useruuid, "DELETED").list();
    }

    @GET
    @Path("/project/{projectuuid}/search/period")
    public List<Expense> findByProjectAndPeriod(@PathParam("projectuuid") String projectuuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate localFromDate = LocalDate.parse(fromdate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDate localToDate = LocalDate.parse(todate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return Expense.find("projectuuid like ?1 and expensedate >= ?2 and expensedate <= ?3", projectuuid, localFromDate, localToDate).list();
    }

    @GET
    @Path("/user/{useruuid}/search/period")
    public List<Expense> findByUserAndPeriod(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate localFromDate = LocalDate.parse(fromdate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDate localToDate = LocalDate.parse(todate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return Expense.find("useruuid like ?1 and expensedate >= ?2 and expensedate <= ?3", useruuid, localFromDate, localToDate).list();
    }

    @GET
    @Path("/search/period")
    public List<Expense> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate localFromDate = LocalDate.parse(fromdate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDate localToDate = LocalDate.parse(todate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        // Filter by paidOut date to match ExpenseManagementView expectations
        // Use LocalDateTime for paidOut field and include full last day with < endDate+1
        return Expense.find("paidOut >= ?1 and paidOut < ?2",
            localFromDate.atStartOfDay(),
            localToDate.plusDays(1).atStartOfDay()).list();
    }

    @GET
    @Path("/search/statuses")
    @RolesAllowed({"ADMIN", "HR", "SYSTEM"})
    public List<Expense> findByStatuses(@QueryParam("statuses") String statusesParam) {
        if (statusesParam == null || statusesParam.isEmpty()) {
            return List.of();
        }
        String[] statuses = statusesParam.split(",");
        StringBuilder queryBuilder = new StringBuilder("status IN (");
        for (int i = 0; i < statuses.length; i++) {
            queryBuilder.append("?").append(i + 1);
            if (i < statuses.length - 1) {
                queryBuilder.append(", ");
            }
        }
        queryBuilder.append(")");

        return Expense.find(queryBuilder.toString(), Sort.by("datecreated").descending(), (Object[]) statuses).list();
    }

    @GET
    @Path("/categories")
    public List<ExpenseCategory> getCategories(@QueryParam("useruuid") String useruuid) {
        User user = userService.findById(useruuid, false);
        Company company = userService.getUserStatus(user, LocalDate.now()).getCompany();

        int mostFrequentAccount = findMostFrequentAccount();

        List<ExpenseCategory> expenseCategories = ExpenseCategory.listAll();
        expenseCategories.forEach(expenseCategory -> expenseCategory.getExpenseAccounts().removeIf(expenseAccount -> !expenseAccount.isActive() || !expenseAccount.getCompanyuuid().equals(company.getUuid())));
        return expenseCategories
                .stream()
                .filter(expenseCategory -> expenseCategory.isActive() && !expenseCategory.getExpenseAccounts().isEmpty())
                .peek(expenseCategory -> expenseCategory.getExpenseAccounts().forEach(expenseAccount -> expenseAccount.setDefaultAccount(expenseAccount.getAccountNumber() == mostFrequentAccount)))
                .toList();
    }

    @Transactional
    public int findMostFrequentAccount() {
        Query query = em.createQuery("SELECT e.account, COUNT(e) AS occurrences FROM Expense e GROUP BY e.account ORDER BY occurrences DESC");
        query.setMaxResults(1);
        Object result = query.getSingleResult();
        return Integer.parseInt(result != null ? (String) ((Object[]) result)[0] : null); // Cast and return the account
    }

    @POST
    @Transactional
    public void saveExpense(@Valid Expense expense) throws IOException {
        log.info("ExpenseResource.saveExpense");
        log.info("expense = " + expense);
        expenseService.processExpense(expense);
    }

    @PUT
    @Path("/{uuid}")
    @Transactional
    @RolesAllowed({"USER", "ADMIN", "HR", "SYSTEM"})
    public void updateOne(@PathParam("uuid") String uuid, Expense expense) {
        Expense existing = Expense.findById(uuid);
        if (existing == null) {
            throw new WebApplicationException("Expense not found", 404);
        }

        // Build dynamic update query based on what's being updated
        StringBuilder updateQuery = new StringBuilder();
        List<Object> params = new java.util.ArrayList<>();

        if (expense.getAmount() != null) {
            updateQuery.append("amount = ?").append(params.size() + 1).append(", ");
            params.add(expense.getAmount());
        }
        if (expense.getAccount() != null) {
            updateQuery.append("account = ?").append(params.size() + 1).append(", ");
            params.add(expense.getAccount());
        }
        if (expense.getDescription() != null) {
            updateQuery.append("description = ?").append(params.size() + 1).append(", ");
            params.add(expense.getDescription());
        }
        if (expense.getProjectuuid() != null) {
            updateQuery.append("projectuuid = ?").append(params.size() + 1).append(", ");
            params.add(expense.getProjectuuid());
        }
        if (expense.getExpensedate() != null) {
            updateQuery.append("expensedate = ?").append(params.size() + 1).append(", ");
            params.add(expense.getExpensedate());
        }
        // Allow HR/ADMIN to update status (for retry functionality)
        if (expense.getStatus() != null) {
            updateQuery.append("status = ?").append(params.size() + 1).append(", ");
            params.add(expense.getStatus());
        }

        if (updateQuery.length() == 0) {
            return; // Nothing to update
        }

        // Remove trailing comma and space
        updateQuery.setLength(updateQuery.length() - 2);
        updateQuery.append(" WHERE uuid = ?").append(params.size() + 1);
        params.add(uuid);

        Expense.update(updateQuery.toString(), params.toArray());
    }

    @DELETE
    @Path("/{uuid}")
    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        log.info("Deleting expense with uuid: "+ uuid);
        Expense expense = Expense.findById(uuid);
        if (expense == null) {
            throw new WebApplicationException("Expense not found", 404);
        }
        if (!expense.isLocked()) {
            Expense.update("status = ?1, datemodified = ?2 WHERE uuid = ?3", "DELETED", LocalDate.now(), uuid);
        } else {
            throw new WebApplicationException("Cannot delete a locked expense", 400);
        }
    }

}
