package dk.trustworks.intranet.expenseservice.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.ExpenseMetadata;
import dk.trustworks.intranet.expenseservice.ai.model.ExpenseInsight;
import dk.trustworks.intranet.expenseservice.model.Expense;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.*;

@ApplicationScoped
public class TaxonomyAwareExpenseMapper {

    @Inject TaxonomyService taxonomy;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ExpenseInsight toEntity(Expense expense, ExpenseMetadata md) {
        ExpenseInsight e = new ExpenseInsight();
        e.setExpenseUuid(expense.getUuid());
        e.setUseruuid(expense.getUseruuid());
        e.setMerchantName(md.getMerchantName());
        String normalizedPrimary = normalize(md.getMerchantCategory());
        e.setMerchantCategory(normalizedPrimary != null ? normalizedPrimary : md.getMerchantCategory());
        e.setConfidence(md.getConfidence());
        e.setExpenseDate(parseDate(md.getExpenseDate(), expense));
        e.setCurrency(md.getCurrency());
        e.setTotalAmount(md.getTotalAmount());
        e.setSubtotalAmount(md.getSubtotalAmount());
        e.setVatAmount(md.getVatAmount());
        e.setPaymentMethod(md.getPaymentMethod());
        e.setCountry(md.getCountry());
        e.setCity(md.getCity());

        // Beverage totals from taxonomy rollups and specific leafs
        Totals t = computeBeverageTotals(md.getLineItems());
        // Use model-provided totals if present, otherwise computed
        e.setAlcoholTotal(firstNonNull(md.getAlcoholTotal(), t.alcohol));
        e.setCoffeeTotal(firstNonNull(md.getCoffeeTotal(), t.coffee));
        e.setJuiceTotal(firstNonNull(md.getJuiceTotal(), t.juice));
        e.setWaterTotal(firstNonNull(md.getWaterTotal(), t.water));
        e.setSoftDrinkTotal(firstNonNull(md.getSoftDrinkTotal(), t.soft));
        Double drinks = md.getDrinksTotal();
        if (drinks == null) drinks = t.all;
        e.setDrinksTotal(drinks);

        e.setModelName("gpt-5-mini");
        try { e.setRawJson(MAPPER.writeValueAsString(md)); } catch (Exception ignored) {}
        return e;
    }

    public Set<String> computeTags(ExpenseMetadata md, ExpenseInsight insight) {
        Set<String> tags = new LinkedHashSet<>();
        String primary = insight.getMerchantCategory();
        if (primary != null) tags.addAll(taxonomy.expandParents(primary));
        if (md.getLineItems() != null) {
            for (ExpenseMetadata.LineItem li : md.getLineItems()) {
                String norm = normalize(li.getItemCategory());
                if (norm != null) tags.addAll(taxonomy.expandParents(norm));
            }
        }
        // Expand to rollups
        tags = taxonomy.expandToRollups(tags);
        return tags;
    }

    private Totals computeBeverageTotals(List<ExpenseMetadata.LineItem> items) {
        Totals t = new Totals();
        if (items == null) return t;
        Set<String> all = taxonomy.getRollupIncludes("drinks_all");
        Set<String> alcoholic = taxonomy.getRollupIncludes("drinks_alcoholic");
        for (ExpenseMetadata.LineItem li : items) {
            if (li == null || li.getTotal() == null) continue;
            String cat = normalize(li.getItemCategory());
            if (cat == null) continue;
            if (all.contains(cat)) t.all += safe(li.getTotal());
            if (alcoholic.contains(cat)) t.alcohol += safe(li.getTotal());
            if ("coffee_tea".equals(cat)) t.coffee += safe(li.getTotal());
            if ("juice_smoothies".equals(cat)) t.juice += safe(li.getTotal());
            if ("water".equals(cat)) t.water += safe(li.getTotal());
            if ("soda_energy".equals(cat)) t.soft += safe(li.getTotal());
        }
        return t;
    }

    private String normalize(String cat) {
        String n = taxonomy.normalizeCategory(cat);
        return n != null ? n : (cat != null ? cat.trim().toLowerCase(Locale.ROOT) : null);
    }

    private static double safe(Double d) { return d != null ? d : 0.0; }

    private static <T> T firstNonNull(T a, T b) { return a != null ? a : b; }

    private static LocalDate parseDate(String s, Expense fallback) {
        try { return (s != null && !s.isBlank()) ? LocalDate.parse(s) : fallback.getExpensedate(); }
        catch (Exception ex) { return fallback.getExpensedate(); }
    }

    private static class Totals { double all=0, alcohol=0, coffee=0, juice=0, water=0, soft=0; }
}
