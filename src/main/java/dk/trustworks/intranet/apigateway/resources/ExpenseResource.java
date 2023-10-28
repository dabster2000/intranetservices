package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseAccount;
import dk.trustworks.intranet.expenseservice.model.ExpenseCategory;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.resources.AccountPlanResource;
import dk.trustworks.intranet.expenseservice.resources.UserAccountResource;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.Valid;
import javax.ws.rs.*;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Tag(name = "accounting")
@JBossLog
@RequestScoped
@Path("/accounting")
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"SYSTEM", "USER"})
@SecurityRequirement(name = "jwt")
public class ExpenseResource {

    @Inject
    dk.trustworks.intranet.expenseservice.resources.ExpenseResource expenseAPI;

    @Inject
    UserAccountResource userAccountAPI;

    @Inject
    AccountPlanResource accountPlanAPI;

    @GET
    @Path("/receipts/{uuid}")
    public Expense findByUuid(@PathParam("uuid") String uuid) {
        return expenseAPI.findByUuid(uuid);
    }

    @GET
    @Path("/receipts/file/{uuid}")
    public ExpenseFile getFileById(@PathParam("uuid") String uuid) {
        return expenseAPI.getFileById(uuid);
    }

    @GET
    @Path("/receipts/user/{useruuid}")
    public List<Expense> findByUser(@PathParam("useruuid") String useruuid, @QueryParam("limit") Optional<String> limit, @QueryParam("page") Optional<String> page) {
        if(limit.isPresent() && page.isPresent())
            return expenseAPI.findByUser(useruuid, limit.get(), page.get());
        else
            return expenseAPI.findByUser(useruuid);
    }

    @GET
    @Path("/receipts/project/{projectuuid}/search/period")
    public List<Expense> findByProjectAndPeriod(@PathParam("projectuuid") String projectuuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByProjectAndPeriod(projectuuid, fromdate, todate);
    }

    @GET
    @Path("/receipts/user/{useruuid}/search/period")
    public List<Expense> findByUserAndPeriod(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByUserAndPeriod(useruuid, fromdate, todate);
    }

    @GET
    @Path("/receipts/search/period")
    public List<Expense> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByPeriod(fromdate, todate);
    }

    @POST
    @Path("/receipts")
    public void save(Expense expense) throws IOException, InterruptedException {
        if(expense.getUseruuid().equals("173ee0b6-4ee5-11e7-b114-b2f933d5fe66")) return;
        expenseAPI.saveExpense(expense);
    }

    @PUT
    @Path("/receipts/{uuid}")
    public void updateOne(@PathParam("uuid") String uuid, Expense expense) {
        expenseAPI.updateOne(uuid, expense);
    }

    @DELETE
    @Path("/receipts/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        expenseAPI.delete(uuid);
    }

    // UserAccount Resource

    @GET
    @Path("/user-accounts/{useruuid}")
    public UserAccount getUserAccountByUser(@PathParam("useruuid") String useruuid) {
        return userAccountAPI.getAccountByUser(useruuid);
    }

    @GET
    @Path("/user-accounts/search/findByAccountNumber")
    public UserAccount getAccount(@QueryParam("account") int account) throws IOException {
        if(account<=0) return new UserAccount(0, "No account found");
        return userAccountAPI.getAccount(account);
    }

    @POST
    @Path("/user-accounts")
    public void saveUserAccount(UserAccount userAccount) {
        userAccountAPI.saveAccount(userAccount);
    }

    @PUT
    @Path("/user-accounts/{useruuid}")
    public void updateUserAccount(@PathParam("useruuid") String useruuid, UserAccount userAccount) {
        userAccountAPI.updateAccount(useruuid, userAccount);
    }

    // AccountPlan Resource

    @GET
    @Path("/expense-accounts/{account_no}")
    public ExpenseAccount findExpenseAccountByAccountNo(@PathParam("account_no") String account_no) {
        return accountPlanAPI.findAccountByAccountNo(account_no);
    }

    @POST
    @Path("/expense-accounts")
    @Transactional
    public void saveExpenseAccount(@Valid ExpenseAccount expenseAccount) {
        accountPlanAPI.saveExpenseAccount(expenseAccount);
    }

    @PUT
    @Path("/expense-accounts/{account-no}")
    @Transactional
    public void updateExpenseAccount(@PathParam("account-no") String account_no, ExpenseAccount expenseAccount) {
        accountPlanAPI.updateExpenseAccount(account_no, expenseAccount);
    }

    @GET
    @Path("/account-categories")
    public List<ExpenseCategory> findAllExpenseCategories() {
        return accountPlanAPI.findAll();
    }

    @GET
    @Path("/expense-categories/{uuid}")
    public ExpenseCategory findExpenseCategoryByUuid(@PathParam("uuid") String uuid) {
        return accountPlanAPI.findCategoryByUuid(uuid);
    }

    @GET
    @Path("/account-categories/active")
    public List<ExpenseCategory> findAllActiveExpenseCategories() {
        return accountPlanAPI.findAllActive();
    }

    @GET
    @Path("/account-categories/inactive")
    public List<ExpenseCategory> findAllInactiveExpenseCategories() {
        return accountPlanAPI.findAllInactive();
    }

    @POST
    @Path("/expense-categories")
    @Transactional
    public void saveExpenseCategory(@Valid ExpenseCategory expenseCategory) {
        accountPlanAPI.saveExpenseCategory(expenseCategory);
    }

    @PUT
    @Path("/expense-categories/{uuid}")
    @Transactional
    public void updateExpenseCategory(@PathParam("uuid") String uuid, ExpenseCategory expenseCategory) {
        accountPlanAPI.updateExpenseCategory(uuid, expenseCategory);
    }
}

