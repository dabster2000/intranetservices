package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.ItExpenseCategory;
import dk.trustworks.intranet.model.ItExpenseItem;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ItExpenseService {

    public List<ItExpenseItem> findExpensesByUseruuid(String useruuid) {
        return ItExpenseItem.find("useruuid like ?1", useruuid).list();
    }

    @Transactional
    public void saveExpense(ItExpenseItem expenseItem) {
        attachCategory(expenseItem);

        if(ItExpenseItem.findByIdOptional(expenseItem.getId()).isPresent()) {
            updateExpense(expenseItem);
            return;
        }

        ItExpenseItem.persist(expenseItem);
    }

    @Transactional
    public void updateExpense(ItExpenseItem expenseItem) {
        attachCategory(expenseItem);
        ItExpenseItem.update("description = ?1, " +
                        "price = ?2, " +
                        "invoicedate = ?3, " +
                        "status = ?4 " +
                        "where id = ?5 ",
                expenseItem.getDescription(),
                expenseItem.getPrice(),
                expenseItem.getInvoicedate(),
                expenseItem.getStatus(),
                expenseItem.getId());
    }

    private void attachCategory(ItExpenseItem expenseItem) {
        if (expenseItem.getCategory() == null) return;

        int categoryId = expenseItem.getCategory().getId();
        if (categoryId <= 0) {
            throw new WebApplicationException("Category id is required", 400);
        }

        ItExpenseCategory managedCategory = ItExpenseCategory.findById(categoryId);
        if (managedCategory == null) {
            throw new WebApplicationException("Category not found", 400);
        }

        expenseItem.setCategory(managedCategory);
    }

    @Transactional
    public void deleteExpense(int id) {
        ItExpenseItem.deleteById(id);
    }
}
