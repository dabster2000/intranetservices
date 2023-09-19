package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.ExpenseAccount;
import dk.trustworks.intranet.expenseservice.model.ExpenseCategory;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.RequestScoped;
import javax.transaction.Transactional;
import javax.validation.Valid;
import javax.ws.rs.*;
import java.util.List;

@JBossLog
@Path("/account-plan")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
public class AccountPlanResource {

    @GET
    @Path("/expense-account/{account_no}")
    public ExpenseAccount findAccountByAccountNo(@PathParam("account_no") String account_no) {
        return ExpenseAccount.findById(account_no);
    }

    @POST
    @Path("/expense-account")
    @Transactional
    public void saveExpenseAccount(@Valid ExpenseAccount expenseAccount) {
        //validate account is created in active category
        ExpenseCategory cat = ExpenseCategory.findById(expenseAccount.getExpense_category_uuid());
        if (cat.getIs_active()) {
            expenseAccount.persist();
        } else {
            log.error("Expense category must be active: " + expenseAccount.getExpense_category_uuid());
            throw new NotAllowedException("Expense category must be active: " + expenseAccount.getExpense_category_uuid());
        }
    }

    @PUT
    @Path("/expense-account/{account-no}")
    @Transactional
    public void updateExpenseAccount(@PathParam("account-no") String account_no, ExpenseAccount expenseAccount) {

        ExpenseAccount acc = ExpenseAccount.findById(account_no);
        ExpenseCategory cat = ExpenseCategory.findById(acc.getExpense_category_uuid());
        ExpenseCategory expenseCategory = ExpenseCategory.findById(expenseAccount.getExpense_category_uuid());

        //validate internal/external status when changing category
        if ((acc.getExpense_category_uuid().equals(expenseAccount.getExpense_category_uuid())
                || cat.getInternal_expense().equals(expenseCategory.getInternal_expense()))
                //validate account status against category status when changing category
                && (expenseAccount.getIs_active().equals(expenseCategory.getIs_active())
                || expenseCategory.getIs_active())) {

            ExpenseAccount.update("account_name = ?1, " +
                            "is_active = ?2, " +
                            "expense_category_uuid = ?3 " +
                            "WHERE account_no like ?4 ",
                    expenseAccount.getAccount_name(),
                    expenseAccount.getIs_active(),
                    expenseAccount.getExpense_category_uuid(),
                    account_no);
        } else {
            log.error("ExpenseAccount update between categories with different internal/external status not allowed: " + expenseAccount.getExpense_category_uuid());
            throw new NotAllowedException("ExpenseAccount update between categories with different internal/external status not allowed: " + expenseAccount.getExpense_category_uuid());
        }
    }

    @GET
    @Path("/expense-category/{uuid}")
    public ExpenseCategory findCategoryByUuid(@PathParam("uuid") String uuid) {
        return ExpenseCategory.findById(uuid);
    }

    @GET
    public List<ExpenseCategory> findAll() {
        return ExpenseCategory.findAll().list();
    }

    @GET
    @Path("/account-category/active")
    public List<ExpenseCategory> findAllActive() {
        return ExpenseCategory.find("from ExpenseCategory cat left join fetch cat.expenseAccounts acc where cat.is_active = true and acc.is_active = true").list();
    }

    @GET
    @Path("/account-category/inactive")
    public List<ExpenseCategory> findAllInactive() {
        return ExpenseCategory.find("select distinct cat from ExpenseCategory cat left join fetch cat.expenseAccounts acc where cat.is_active = false or acc.is_active = false").list();
    }

    @POST
    @Path("/expense-category")
    @Transactional
    public void saveExpenseCategory(@Valid ExpenseCategory expenseCategory) {
        expenseCategory.persist();
    }

    @PUT
    @Path("/expense-category/{uuid}")
    @Transactional
    public void updateExpenseCategory(@PathParam("uuid") String uuid, ExpenseCategory expenseCategory) {
        //validate category with active account is not deactivated
        ExpenseCategory cat = ExpenseCategory.findById(uuid);
        List<ExpenseAccount> expenseAccounts = cat.getExpenseAccounts();
        long countActiveAccounts = expenseAccounts.stream().filter(ExpenseAccount::getIs_active).count();
        if (countActiveAccounts > 0 && !expenseCategory.getIs_active()) {
            log.error("Deactivating category with active account not allowed: " + cat);
            throw new NotAllowedException("Deactivating category with active account not allowed: " + cat);
        } else {
            ExpenseCategory.update("category_name = ?1, " +
                            "is_active = ?2 " +
                            "WHERE uuid like ?3 ",
                    expenseCategory.getCategory_name(),
                    expenseCategory.getIs_active(),
                    uuid);
        }
    }

}
