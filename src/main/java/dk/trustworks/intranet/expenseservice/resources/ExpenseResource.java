package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseCategory;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
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
        return Expense.find("useruuid", Sort.by("expensedate").descending(), useruuid).page(Page.of(pageInt, limitInt)).list();
    }

    public List<Expense> findByUser(@PathParam("useruuid") String useruuid) {
        return Expense.find("useruuid", useruuid).list();
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
        return Expense.find("datecreated >= ?1 and expensedate <= ?2", localFromDate, localToDate).list();
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

    //todo
    //validate expense is found in status created, otherwise return error
    @PUT
    @Path("/{uuid}")
    @Transactional
    public void updateOne(@PathParam("uuid") String uuid, Expense expense) {
        Expense.update("amount = ?1, " +
                        "account = ?2, " +
                        "description = ?3, " +
                        "projectuuid = ?4, " +
                        "expensedate = ?5 " +
                        "WHERE uuid like ?6 ",
                expense.getAmount(),
                expense.getAccount(),
                expense.getDescription(),
                expense.getProjectuuid(),
                expense.getExpensedate(),
                uuid);
    }

    //todo
    //validate expense is found in status created, otherwise return error
    @DELETE
    @Path("/{uuid}")
    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        Expense expense = Expense.findById(uuid);
        if(!expense.isLocked()) Expense.update("status = ?1, datemodified = ?2 WHERE uuid like ?2", "DELETED", LocalDate.now(), uuid);
    }
}
