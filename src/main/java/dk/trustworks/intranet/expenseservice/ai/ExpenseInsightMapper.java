package dk.trustworks.intranet.expenseservice.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.ExpenseMetadata;
import dk.trustworks.intranet.expenseservice.ai.model.ExpenseInsight;
import dk.trustworks.intranet.expenseservice.model.Expense;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public class ExpenseInsightMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ExpenseInsight toEntity(Expense expense, ExpenseMetadata md) {
        ExpenseInsight e = new ExpenseInsight();
        e.setExpenseUuid(expense.getUuid());
        e.setUseruuid(expense.getUseruuid());
        e.setMerchantName(md.getMerchantName());
        e.setMerchantCategory(normalizeCategory(md.getMerchantCategory()));
        e.setConfidence(md.getConfidence());
        e.setExpenseDate(parseDate(md.getExpenseDate(), expense));
        e.setCurrency(md.getCurrency());
        e.setTotalAmount(md.getTotalAmount());
        e.setSubtotalAmount(md.getSubtotalAmount());
        e.setVatAmount(md.getVatAmount());
        e.setPaymentMethod(md.getPaymentMethod());
        e.setCountry(md.getCountry());
        e.setCity(md.getCity());
        // Beverage rollups from explicit fields if present
        e.setAlcoholTotal(nullSafe(md.getAlcoholTotal()));
        e.setCoffeeTotal(nullSafe(md.getCoffeeTotal()));
        e.setJuiceTotal(nullSafe(md.getJuiceTotal()));
        e.setWaterTotal(nullSafe(md.getWaterTotal()));
        e.setSoftDrinkTotal(nullSafe(md.getSoftDrinkTotal()));
        // If drinksTotal missing, compute from line items or subtypes
        Double drinksTotal = md.getDrinksTotal();
        if (drinksTotal == null) {
            drinksTotal = sumBeveragesFromLineItems(md.getLineItems());
            if (drinksTotal == null) {
                drinksTotal = sumNonNull(e.getAlcoholTotal(), e.getCoffeeTotal(), e.getJuiceTotal(), e.getWaterTotal(), e.getSoftDrinkTotal());
            }
        }
        e.setDrinksTotal(drinksTotal);
        e.setModelName("gpt-5-mini");
        try { e.setRawJson(MAPPER.writeValueAsString(md)); } catch (Exception ignored) {}
        return e;
    }

    private static LocalDate parseDate(String s, Expense fallback) {
        try { return (s != null && !s.isBlank()) ? LocalDate.parse(s) : fallback.getExpensedate(); }
        catch (Exception ex) { return fallback.getExpensedate(); }
    }

    private static String normalizeCategory(String input) {
        if (input == null) return "OTHER";
        String c = input.trim().toUpperCase(Locale.ROOT);
        // Roll broader groupings
        if (c.contains("INTERNET")) return "INTERNET_HOME";
        if (c.contains("RESTAURANT") || c.contains("FOOD") || c.contains("MEAL") || c.contains("LUNCH")) return "FOOD";
        if (c.contains("CAFE") || c.contains("COFFEE")) return "CAFE";
        if (c.contains("GROCERY") || c.contains("SUPERMARKET")) return "GROCERIES";
        if (c.contains("DRINK")) return "DRINKS";
        return c;
    }

    private static Double sumBeveragesFromLineItems(List<ExpenseMetadata.LineItem> items) {
        if (items == null || items.isEmpty()) return null;
        double sum = 0.0;
        boolean any = false;
        for (ExpenseMetadata.LineItem li : items) {
            String cat = li.getItemCategory();
            if (cat == null) continue;
            String up = cat.toUpperCase(Locale.ROOT);
            if (up.equals("ALCOHOL") || up.equals("COFFEE") || up.equals("JUICE") || up.equals("WATER") || up.equals("SOFT_DRINK") || up.equals("DRINKS")) {
                if (li.getTotal() != null) {
                    sum += li.getTotal(); any = true;
                }
            }
        }
        return any ? sum : null;
    }

    private static Double sumNonNull(Double... values) {
        double s = 0.0; boolean any=false;
        for (Double v : values) { if (v != null) { s += v; any=true; } }
        return any ? s : null;
    }

    private static Double nullSafe(Double v) { return v; }
}
