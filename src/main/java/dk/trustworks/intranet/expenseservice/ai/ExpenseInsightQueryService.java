package dk.trustworks.intranet.expenseservice.ai;

import dk.trustworks.intranet.expenseservice.ai.model.ExpenseInsight;
import dk.trustworks.intranet.expenseservice.ai.model.ExpenseLineItemEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@ApplicationScoped
public class ExpenseInsightQueryService {

    @PersistenceContext
    EntityManager em;

    private static final List<String> DRINK_CATS = List.of("ALCOHOL","COFFEE","JUICE","WATER","SOFT_DRINK","DRINKS");

    public double sumBeverageSubtype(String subtype, LocalDate from, LocalDate to) {
        String up = subtype.toUpperCase();
        double lineItemSum;
        if ("DRINKS".equals(up)) {
            lineItemSum = sumLineItemsForCategories(DRINK_CATS, from, to);
            double fallback = sumInsightDrinksTotals(from, to);
            return lineItemSum + fallback;
        } else {
            lineItemSum = sumLineItemsForCategories(List.of(up), from, to);
            return lineItemSum;
        }
    }

    private double sumLineItemsForCategories(List<String> cats, LocalDate from, LocalDate to) {
        Double res = em.createQuery(
                "select coalesce(sum(li.total),0) from ExpenseLineItemEntity li " +
                        "join li.insight i " +
                        "where upper(li.itemCategory) in :cats " +
                        "and (:from is null or i.expenseDate >= :from) " +
                        "and (:to is null or i.expenseDate <= :to)", Double.class)
                .setParameter("cats", cats)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return res != null ? res : 0.0;
    }

    private double sumInsightDrinksTotals(LocalDate from, LocalDate to) {
        // Sum drinksTotal only for insights that have no beverage line items to avoid double counting
        Double res = em.createQuery(
                "select coalesce(sum(i.drinksTotal),0) from ExpenseInsight i " +
                        "where i.drinksTotal is not null " +
                        "and not exists (select 1 from ExpenseLineItemEntity li where li.insight = i and upper(li.itemCategory) in :cats) " +
                        "and (:from is null or i.expenseDate >= :from) " +
                        "and (:to is null or i.expenseDate <= :to)", Double.class)
                .setParameter("cats", DRINK_CATS)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return res != null ? res : 0.0;
    }

    public double sumLunchByUserAndMonth(String useruuid, YearMonth ym) {
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        Double res = em.createQuery(
                "select coalesce(sum(i.totalAmount),0) from ExpenseInsight i " +
                        "join ExpenseTagEntity t on t.insight = i " +
                        "where t.tag = 'LUNCH' and i.useruuid = :user " +
                        "and i.expenseDate between :from and :to", Double.class)
                .setParameter("user", useruuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return res != null ? res : 0.0;
    }

    public double sumByMerchant(String merchant, LocalDate from, LocalDate to) {
        Double res = em.createQuery(
                "select coalesce(sum(i.totalAmount),0) from ExpenseInsight i " +
                        "where lower(i.merchantName) = lower(:m) " +
                        "and (:from is null or i.expenseDate >= :from) " +
                        "and (:to is null or i.expenseDate <= :to)", Double.class)
                .setParameter("m", merchant)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return res != null ? res : 0.0;
    }
}
