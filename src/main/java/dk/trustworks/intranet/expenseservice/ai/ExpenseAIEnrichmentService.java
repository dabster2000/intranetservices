package dk.trustworks.intranet.expenseservice.ai;

import dk.trustworks.intranet.apis.openai.ExpenseMetadata;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.ai.model.ExpenseInsight;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@JBossLog
@ApplicationScoped
public class ExpenseAIEnrichmentService {

    @Inject ExpenseFileService expenseFileService;
    @Inject OpenAIService openAIService;
    @Inject ExpenseInsightRepository insightRepository;
    @Inject TaxonomyAwareExpenseMapper mapper;
    @Inject TaxonomyService taxonomyService;

    @Transactional
    public void enrichIfMissing(String expenseId) {
        Expense expense = Expense.findById(expenseId);
        if (expense == null) { log.warnf("Expense %s not found", expenseId); return; }
        if (insightRepository.existsByExpenseUuid(expenseId)) { return; }

        ExpenseFile file;
        try {
            file = expenseFileService.getFileById(expenseId);
        } catch (Exception e) {
            log.errorf(e, "Failed to fetch receipt for %s", expenseId);
            return;
        }
        if (file == null || file.getExpensefile() == null || file.getExpensefile().isBlank()) {
            log.warnf("No receipt image for %s", expenseId);
            return;
        }

        String base64Image = stripPrefix(file.getExpensefile());
        String hints = buildBusinessHints(expense);
        ExpenseMetadata md;
        try {
            md = openAIService.extractExpenseMetadata(base64Image, hints);
        } catch (Exception e) {
            log.errorf(e, "OpenAI extraction failed for %s", expenseId);
            return;
        }

        ExpenseInsight insight = mapper.toEntity(expense, md);
        insight.setCreatedAt(OffsetDateTime.now());
        insightRepository.persist(insight);
        insightRepository.persistLineItems(insight, md.getLineItems());
        // Compute taxonomy-based tags + merge with any model tags
        Set<String> tags = new LinkedHashSet<>(mapper.computeTags(md, insight));
        if (md.getTags() != null) tags.addAll(md.getTags());
        insightRepository.persistTags(insight, new ArrayList<>(tags));
        log.infof("Enriched expense %s with AI metadata", expenseId);
    }

    private String stripPrefix(String value) {
        int idx = value.indexOf(";base64,");
        return (idx > 0 ? value.substring(idx + 8) : value).trim();
    }

    private String buildBusinessHints(Expense expense) {
        List<String> allowed = taxonomyService != null && taxonomyService.isReady() ?
                taxonomyService.getLimitedCategoryIds(80) : List.of();
        return ("""
            Extract structured expense data for analytics and tax.
            You must classify merchantCategory and each lineItems[i].itemCategory using THESE CATEGORY IDS ONLY (exact id strings):
            %s
            If unsure, choose the closest leaf category. Do not invent new ids.
            Always return ISO date yyyy-MM-dd if possible.
            Declared account name: %s
            Declared amount: %s DKK
        """
        ).formatted(String.join(", ", allowed), expense.getAccountname(), String.valueOf(expense.getAmount()));
    }
}
