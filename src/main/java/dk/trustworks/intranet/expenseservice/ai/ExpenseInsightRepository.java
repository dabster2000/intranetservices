package dk.trustworks.intranet.expenseservice.ai;

import dk.trustworks.intranet.apis.openai.ExpenseMetadata;
import dk.trustworks.intranet.expenseservice.ai.model.ExpenseInsight;
import dk.trustworks.intranet.expenseservice.ai.model.ExpenseLineItemEntity;
import dk.trustworks.intranet.expenseservice.ai.model.ExpenseTagEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class ExpenseInsightRepository implements PanacheRepositoryBase<ExpenseInsight, String> {

    @PersistenceContext
    EntityManager em;

    public boolean existsByExpenseUuid(String expenseUuid) {
        return findById(expenseUuid) != null;
    }

    @Transactional
    public void persistLineItems(ExpenseInsight insight, List<ExpenseMetadata.LineItem> items) {
        if (items == null || items.isEmpty()) return;
        for (ExpenseMetadata.LineItem li : items) {
            ExpenseLineItemEntity e = new ExpenseLineItemEntity();
            e.setInsight(insight);
            e.setDescription(li.getDescription());
            e.setQuantity(li.getQuantity());
            e.setUnitPrice(li.getUnitPrice());
            e.setTotal(li.getTotal());
            e.setItemCategory(li.getItemCategory());
            em.persist(e);
        }
    }

    @Transactional
    public void persistTags(ExpenseInsight insight, List<String> tags) {
        if (tags == null || tags.isEmpty()) return;
        for (String tag : tags) {
            ExpenseTagEntity t = new ExpenseTagEntity();
            t.setInsight(insight);
            t.setTag(tag);
            t.setConfidence(null);
            em.persist(t);
        }
    }
}
