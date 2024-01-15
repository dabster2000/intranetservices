package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.ItExpenseCategory;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ItExpenseCategoryService {
    public List<ItExpenseCategory> findAll() {
        return ItExpenseCategory.listAll();
    }
}
