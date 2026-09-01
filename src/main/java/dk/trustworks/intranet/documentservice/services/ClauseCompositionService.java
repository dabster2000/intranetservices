package dk.trustworks.intranet.documentservice.services;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.data.DocxRenderData;
import dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO;
import dk.trustworks.intranet.documentservice.model.ClauseAddendumShellEntity;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClauseEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClauseLinkEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClausePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClauseVersionEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.enums.ClauseRenderMode;
import dk.trustworks.intranet.documentservice.model.enums.ClauseStatus;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import dk.trustworks.intranet.utils.NumberUtils;
import dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO;
import dk.trustworks.intranet.utils.services.WordDocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a clause selection into rendered signing documents
 * (template-clauses spec §5):
 * <ol>
 *   <li><b>INLINE</b> clauses merge into the first base document carrying
 *       the {@code {{CLAUSES}}} anchor, in display order, via poi-tl
 *       sub-document rendering ({@link DocxRenderData}). An INLINE clause
 *       selected on a template without the anchor falls back to ADDENDUM
 *       with a notice — never a silent drop.</li>
 *   <li><b>ADDENDUM</b> clauses and free-text "Individuel aftale" entries
 *       render as numbered points in ONE combined tillæg document built
 *       from the shared shell ({@code clause_addendum_shells}, or a
 *       minimal built-in shell when none is uploaded).</li>
 *   <li>The value map is merged: base template values + every selected
 *       clause's (type-formatted) parameter values — collision-free by
 *       the link-time validation, re-checked here.</li>
 * </ol>
 * Zero selected clauses and no required links ⇒ {@link CompositionPlan#isEmpty()}
 * and the caller's path is byte-identical to the pre-clause behavior.
 */
@JBossLog
@ApplicationScoped
public class ClauseCompositionService {

    /** Rendered document name of the combined tillæg. */
    public static final String ADDENDUM_DOCUMENT_NAME = "Tillæg til ansættelsesaftale";

    @Inject
    WordDocumentService wordDocumentService;

    // ---- Plan ------------------------------------------------------------------

    /** One resolved item of the bundle, ready to render and to snapshot. */
    public record ResolvedClauseItem(
            String clauseUuid,
            String clauseVersionUuid,
            String clauseKey,
            String title,
            ClauseRenderMode declaredMode,
            ClauseRenderMode effectiveMode,
            Map<String, String> parameterValues,
            String customTitle,
            String customText,
            int displayOrder,
            String fragmentFileUuid
    ) {
        public boolean isCustom() {
            return clauseUuid == null;
        }
    }

    /** The resolved, validated composition for one prepare/send. */
    public record CompositionPlan(
            List<ResolvedClauseItem> items,
            List<String> notices,
            /** fileUuid of the first base document carrying the anchor; null when none. */
            String anchorDocFileUuid,
            /**
             * The derived company's facts as {@code COMPANY_<KEY>} render
             * tokens. Clause fragments may reference {@code {{COMPANY_*}}}
             * tags without declaring them as parameters (the tag-diff exempts
             * them as server-resolved) — these tokens are what resolves them,
             * since {@code applyCompanyFacts} only covers placeholders
             * declared on the base template.
             */
            Map<String, String> companyFactTokens
    ) {
        public CompositionPlan(List<ResolvedClauseItem> items, List<String> notices, String anchorDocFileUuid) {
            this(items, notices, anchorDocFileUuid, Map.of());
        }

        public static CompositionPlan empty() {
            return new CompositionPlan(List.of(), List.of(), null);
        }

        public CompositionPlan withCompanyFactTokens(Map<String, String> tokens) {
            return new CompositionPlan(items, notices, anchorDocFileUuid,
                    tokens == null ? Map.of() : Map.copyOf(tokens));
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public List<ResolvedClauseItem> inlineItems() {
            return items.stream().filter(i -> i.effectiveMode() == ClauseRenderMode.INLINE).toList();
        }

        public List<ResolvedClauseItem> addendumItems() {
            return items.stream().filter(i -> i.effectiveMode() == ClauseRenderMode.ADDENDUM).toList();
        }

        /** All selected clauses' formatted parameter values, merged. */
        public Map<String, String> clauseValues() {
            Map<String, String> merged = new LinkedHashMap<>();
            for (ResolvedClauseItem item : items) {
                merged.putAll(item.parameterValues());
            }
            return merged;
        }
    }

    /**
     * Resolve and validate a clause selection against a template.
     * <p>
     * Enforced here (all → {@link IllegalArgumentException} → 400 in the
     * resource layer): the clause exists, is ACTIVE, is offered on the
     * template (explicit link or category-wide offer), has a published
     * active version; required-linked clauses are auto-included; required
     * parameters have a value (or default); parameter keys collide with
     * neither the base template's nor another clause's keys; a custom
     * entry carries title and text.
     *
     * @return an empty plan when nothing is selected and no link is
     *         required — the caller then skips composition entirely
     */
    /**
     * Variant that loads the template's document list from the database —
     * for flows without a client-supplied document list (the dossier).
     */
    public CompositionPlan resolveForTemplateDocuments(String templateUuid, List<SelectedClauseDTO> selections) {
        List<TemplateDocumentDTO> documents = templateUuid == null || templateUuid.isBlank()
                ? List.of()
                : dk.trustworks.intranet.documentservice.model.TemplateDocumentEntity
                        .findByTemplateUuid(templateUuid).stream()
                        .map(doc -> TemplateDocumentDTO.builder()
                                .uuid(doc.getUuid())
                                .documentName(doc.getDocumentName())
                                .fileUuid(doc.getFileUuid())
                                .originalFilename(doc.getOriginalFilename())
                                .displayOrder(doc.getDisplayOrder())
                                .build())
                        .toList();
        return resolveForTemplate(templateUuid, documents, selections);
    }

    public CompositionPlan resolveForTemplate(String templateUuid,
                                              List<TemplateDocumentDTO> templateDocuments,
                                              List<SelectedClauseDTO> selections) {
        List<SelectedClauseDTO> requested = selections == null ? List.of() : selections;
        List<TemplateClauseLinkEntity> links = templateUuid == null || templateUuid.isBlank()
                ? List.of()
                : TemplateClauseLinkEntity.findByTemplate(templateUuid);

        List<SelectedClauseDTO> effective = withRequiredLinks(requested, links);
        if (effective.isEmpty()) {
            return CompositionPlan.empty();
        }
        if (templateUuid == null || templateUuid.isBlank()) {
            throw new IllegalArgumentException("Clauses require a template context");
        }
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(templateUuid);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateUuid);
        }

        Set<String> linkedClauseUuids = links.stream()
                .map(TemplateClauseLinkEntity::getClauseUuid)
                .collect(Collectors.toSet());
        Set<String> baseKeys = TemplatePlaceholderEntity.findByTemplate(template).stream()
                .map(TemplatePlaceholderEntity::getPlaceholderKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        boolean anyInline = false;
        List<String> notices = new ArrayList<>();
        List<ResolvedClauseItem> items = new ArrayList<>();
        Set<String> seenClauseUuids = new LinkedHashSet<>();
        Set<String> seenKeys = new LinkedHashSet<>(baseKeys);

        int fallbackOrder = 0;
        for (SelectedClauseDTO selection : effective) {
            int order = selection.orderOrDefault(fallbackOrder);
            fallbackOrder = order + 1;
            if (selection.isCustom()) {
                String title = trimToNull(selection.customTitle());
                String text = trimToNull(selection.customText());
                if (title == null || text == null) {
                    throw new IllegalArgumentException(
                            "An Individuel aftale requires both a title and the agreement text");
                }
                items.add(new ResolvedClauseItem(null, null, null, title,
                        ClauseRenderMode.ADDENDUM, ClauseRenderMode.ADDENDUM,
                        Map.of(), title, text, order, null));
                continue;
            }

            TemplateClauseEntity clause = TemplateClauseEntity.findById(selection.clauseUuid());
            if (clause == null) {
                throw new IllegalArgumentException("Clause not found: " + selection.clauseUuid());
            }
            if (!seenClauseUuids.add(clause.getUuid())) {
                throw new IllegalArgumentException("Clause '" + clause.getName() + "' is selected twice");
            }
            if (clause.getStatus() != ClauseStatus.ACTIVE) {
                throw new IllegalArgumentException("Clause '" + clause.getName() + "' is not active");
            }
            boolean offered = linkedClauseUuids.contains(clause.getUuid())
                    || (clause.isOfferOnCategory() && clause.getCategory() == template.getCategory());
            if (!offered) {
                throw new IllegalArgumentException(
                        "Clause '" + clause.getName() + "' is not offered on template '" + template.getName() + "'");
            }
            TemplateClauseVersionEntity version = clause.getActiveVersionUuid() == null
                    ? null
                    : TemplateClauseVersionEntity.findById(clause.getActiveVersionUuid());
            if (version == null) {
                throw new IllegalArgumentException(
                        "Clause '" + clause.getName() + "' has no published wording version");
            }

            List<TemplateClausePlaceholderEntity> parameters =
                    TemplateClausePlaceholderEntity.findByClause(clause.getUuid());
            Map<String, String> values = resolveParameterValues(clause.getName(), parameters,
                    selection.parameterValues());
            for (String key : values.keySet()) {
                if (!seenKeys.add(key)) {
                    throw new IllegalArgumentException(
                            "Placeholder key '" + key + "' of clause '" + clause.getName()
                                    + "' collides with the template or another selected clause");
                }
            }

            if (clause.getRenderMode() == ClauseRenderMode.INLINE) {
                anyInline = true;
            }
            items.add(new ResolvedClauseItem(clause.getUuid(), version.getUuid(), clause.getClauseKey(),
                    clause.getName(), clause.getRenderMode(), clause.getRenderMode(),
                    values, null, null, order, version.getFileUuid()));
        }

        items.sort(Comparator.comparingInt(ResolvedClauseItem::displayOrder));

        // Anchor detection only when an INLINE clause is on the bundle —
        // it costs one S3 fetch per base document.
        String anchorDocFileUuid = null;
        if (anyInline) {
            anchorDocFileUuid = findAnchorDocument(templateDocuments);
            if (anchorDocFileUuid == null) {
                List<ResolvedClauseItem> reassigned = new ArrayList<>(items.size());
                for (ResolvedClauseItem item : items) {
                    if (item.effectiveMode() == ClauseRenderMode.INLINE) {
                        notices.add("Klausulen '" + item.title() + "' er markeret til at indgå i kontrakten, "
                                + "men skabelonen har intet {{CLAUSES}}-anker — den medtages i stedet som tillæg.");
                        reassigned.add(new ResolvedClauseItem(item.clauseUuid(), item.clauseVersionUuid(),
                                item.clauseKey(), item.title(), item.declaredMode(), ClauseRenderMode.ADDENDUM,
                                item.parameterValues(), item.customTitle(), item.customText(),
                                item.displayOrder(), item.fragmentFileUuid()));
                    } else {
                        reassigned.add(item);
                    }
                }
                items = reassigned;
            }
        }

        return new CompositionPlan(List.copyOf(items), List.copyOf(notices), anchorDocFileUuid);
    }

    // ---- Rendering -------------------------------------------------------------

    /**
     * The render value map for one base document: the effective (fact-
     * resolved, formatted) base values + all clause parameter values, and
     * — when this document carries the anchor and INLINE items exist —
     * the {@code CLAUSES} sub-document block.
     */
    public Map<String, Object> renderValuesForBaseDocument(CompositionPlan plan,
                                                           String documentFileUuid,
                                                           Map<String, String> effectiveValues) {
        Map<String, Object> values = new HashMap<>(effectiveValues);
        values.putAll(plan.clauseValues());
        values.putAll(plan.companyFactTokens());
        List<ResolvedClauseItem> inline = plan.inlineItems();
        if (!inline.isEmpty() && documentFileUuid != null && documentFileUuid.equals(plan.anchorDocFileUuid())) {
            byte[] block = buildItemsBlock(inline, values, false);
            values.put(ClauseService.CLAUSES_ANCHOR_KEY, new DocxRenderData(block, null));
        }
        return values;
    }

    /**
     * Build the combined tillæg document (docx) for the plan's ADDENDUM
     * items, or {@code null} when there are none. Uses the uploaded
     * shared shell when one is active, otherwise a minimal built-in shell
     * — the send never fails on missing ops setup.
     */
    public byte[] buildAddendumDocx(CompositionPlan plan, Map<String, String> effectiveValues) {
        List<ResolvedClauseItem> addendum = plan.addendumItems();
        if (addendum.isEmpty()) {
            return null;
        }
        Map<String, Object> values = new HashMap<>(effectiveValues);
        values.putAll(plan.clauseValues());
        values.putAll(plan.companyFactTokens());
        byte[] block = buildItemsBlock(addendum, values, true);

        byte[] shell = ClauseAddendumShellEntity.findActive()
                .map(active -> wordDocumentService.getWordTemplate(active.getFileUuid()))
                .orElseGet(ClauseCompositionService::buildDefaultShell);

        values.put(ClauseService.CLAUSES_ANCHOR_KEY, new DocxRenderData(block, null));
        return renderDocx(shell, values);
    }

    /**
     * Render fragments/custom texts into one block document. Each item is
     * a {@code {{+ITEM_n}}} sub-document merge: fragments render with the
     * full merged value map; custom texts become plain paragraphs. With
     * {@code numbered}, each item is preceded by a bold "n. Title"
     * heading (plain text — Word auto-numbering restarts on merge, spec
     * §5.5).
     */
    byte[] buildItemsBlock(List<ResolvedClauseItem> items, Map<String, Object> mergedValues, boolean numbered) {
        byte[] container = buildBlockContainer(items, numbered);
        Map<String, Object> values = new HashMap<>();
        // Sub-template models must be plain data — strip any DocxRenderData
        // (the anchor entry) so fragment rendering stays a single level deep.
        Map<String, Object> fragmentModel = new HashMap<>();
        mergedValues.forEach((key, value) -> {
            if (!(value instanceof DocxRenderData)) {
                fragmentModel.put(key, value);
            }
        });
        for (int i = 0; i < items.size(); i++) {
            ResolvedClauseItem item = items.get(i);
            if (item.isCustom()) {
                values.put("ITEM" + i, new DocxRenderData(buildTextDocx(item.customText()), null));
            } else {
                byte[] fragment = wordDocumentService.getWordTemplate(item.fragmentFileUuid());
                values.put("ITEM" + i, new DocxRenderData(fragment, List.of(fragmentModel)));
            }
        }
        return renderDocx(container, values);
    }

    /** Container docx: optional headings + one {@code {{+ITEMn}}} anchor per item. */
    static byte[] buildBlockContainer(List<ResolvedClauseItem> items, boolean numbered) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < items.size(); i++) {
                ResolvedClauseItem item = items.get(i);
                if (numbered) {
                    XWPFParagraph heading = document.createParagraph();
                    heading.setSpacingBefore(i == 0 ? 0 : 240);
                    XWPFRun headingRun = heading.createRun();
                    headingRun.setBold(true);
                    headingRun.setFontSize(12);
                    headingRun.setText((i + 1) + ". " + item.title());
                }
                XWPFParagraph anchor = document.createParagraph();
                anchor.createRun().setText("{{+ITEM" + i + "}}");
            }
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build clause block container: " + e.getMessage(), e);
        }
    }

    /** Free text → docx paragraphs (blank lines preserved). */
    static byte[] buildTextDocx(String text) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : text.split("\r?\n", -1)) {
                XWPFParagraph paragraph = document.createParagraph();
                if (!line.isBlank()) {
                    paragraph.createRun().setText(line);
                }
            }
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Individuel aftale document: " + e.getMessage(), e);
        }
    }

    /**
     * Minimal built-in tillæg shell: title + the {@code {{CLAUSES}}}
     * anchor. The uploaded shared shell (with person/company/date header
     * placeholders) replaces this the moment HR uploads one.
     */
    static byte[] buildDefaultShell() {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph();
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            titleRun.setText(ADDENDUM_DOCUMENT_NAME);
            document.createParagraph();
            XWPFParagraph anchor = document.createParagraph();
            anchor.createRun().setText("{{" + ClauseService.CLAUSES_ANCHOR_KEY + "}}");
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build default tillæg shell: " + e.getMessage(), e);
        }
    }

    /** In-memory poi-tl render (no PDF conversion). */
    static byte[] renderDocx(byte[] docx, Map<String, Object> values) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTemplate template = XWPFTemplate.compile(
                    new ByteArrayInputStream(docx),
                    dk.trustworks.intranet.utils.converter.LocalWordToPdfConverter.configureFor(values));
            template.render(values);
            template.writeAndClose(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Clause composition render failed: " + e.getMessage(), e);
        }
    }

    /**
     * The derived company's facts as {@code COMPANY_<KEY>} render tokens —
     * merged into every composed render so clause fragments can reference
     * {@code {{COMPANY_*}}} tags without the base template declaring them.
     * Empty when no company could be derived.
     */
    public Map<String, String> companyFactTokensFor(
            java.util.Optional<CompanyPlaceholderResolver.CompanyContext> company) {
        if (company == null || company.isEmpty()) {
            return Map.of();
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        dk.trustworks.intranet.documentservice.model.CompanyFactEntity
                .factMap(company.get().companyUuid())
                .forEach((key, value) -> tokens.put("COMPANY_" + key, value));
        return tokens;
    }

    // ---- Selection helpers -----------------------------------------------------

    /**
     * Required-linked clauses are always part of the bundle: append any
     * the preparer did not (de)select, with empty parameter values —
     * defaults and requiredness validation apply downstream.
     */
    static List<SelectedClauseDTO> withRequiredLinks(List<SelectedClauseDTO> requested,
                                                     List<TemplateClauseLinkEntity> links) {
        Set<String> selectedUuids = requested.stream()
                .map(SelectedClauseDTO::clauseUuid)
                .filter(uuid -> uuid != null && !uuid.isBlank())
                .collect(Collectors.toSet());
        List<SelectedClauseDTO> effective = new ArrayList<>(requested);
        int order = requested.stream()
                .map(s -> s.displayOrder() == null ? 0 : s.displayOrder())
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
        for (TemplateClauseLinkEntity link : links) {
            if (link.isRequired() && !selectedUuids.contains(link.getClauseUuid())) {
                effective.add(new SelectedClauseDTO(link.getClauseUuid(), Map.of(), null, null, order++));
            }
        }
        return effective;
    }

    /**
     * Apply defaults, enforce required parameters and type-format values
     * (CURRENCY/DECIMAL → Danish formats), mirroring the base form's
     * {@code PlaceholderFormattingService}.
     */
    static Map<String, String> resolveParameterValues(String clauseName,
                                                      List<TemplateClausePlaceholderEntity> parameters,
                                                      Map<String, String> supplied) {
        Map<String, String> raw = supplied == null ? Map.of() : supplied;
        Map<String, String> resolved = new LinkedHashMap<>();
        for (TemplateClausePlaceholderEntity parameter : parameters) {
            String value = raw.get(parameter.getPlaceholderKey());
            if (value == null || value.isBlank()) {
                value = parameter.getDefaultValue();
            }
            if (value == null || value.isBlank()) {
                if (parameter.isRequired()) {
                    throw new IllegalArgumentException("Clause '" + clauseName + "' requires a value for '"
                            + parameter.getLabel() + "' (" + parameter.getPlaceholderKey() + ")");
                }
                value = "";
            }
            resolved.put(parameter.getPlaceholderKey(), formatValue(value, parameter.getFieldType()));
        }
        // Unknown keys are refused — a typo would render a blank merge field.
        for (String key : raw.keySet()) {
            if (!resolved.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Clause '" + clauseName + "' has no parameter '" + key + "'");
            }
        }
        return resolved;
    }

    static String formatValue(String value, FieldType type) {
        if (value == null || value.isBlank() || type == null) {
            return value;
        }
        try {
            return switch (type) {
                case CURRENCY -> NumberUtils.formatCurrency(Double.parseDouble(value));
                case DECIMAL -> NumberUtils.formatDouble(Double.parseDouble(value));
                default -> value;
            };
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /** First base document (in list order) whose docx carries the anchor. */
    private String findAnchorDocument(List<TemplateDocumentDTO> templateDocuments) {
        if (templateDocuments == null) {
            return null;
        }
        for (TemplateDocumentDTO document : templateDocuments) {
            String fileUuid = document.getFileUuid();
            if (fileUuid == null || fileUuid.isBlank()) {
                continue;
            }
            try {
                byte[] docx = wordDocumentService.getWordTemplate(fileUuid);
                if (hasClausesAnchor(docx)) {
                    return fileUuid;
                }
            } catch (Exception e) {
                log.warnf("Anchor detection skipped document %s: %s", fileUuid, e.getMessage());
            }
        }
        return null;
    }

    /** Does the document text carry {@code {{CLAUSES}}} (or {@code {{+CLAUSES}}})? */
    boolean hasClausesAnchor(byte[] docx) {
        String text = wordDocumentService.extractDocumentText(docx);
        return hasClausesAnchorText(text);
    }

    static boolean hasClausesAnchorText(String documentText) {
        if (documentText == null) {
            return false;
        }
        return documentText.contains("{{" + ClauseService.CLAUSES_ANCHOR_KEY + "}}")
                || documentText.contains("{{+" + ClauseService.CLAUSES_ANCHOR_KEY + "}}");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
