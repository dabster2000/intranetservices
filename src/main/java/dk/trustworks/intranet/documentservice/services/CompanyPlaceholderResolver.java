package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.documentservice.model.CompanyFactEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.enums.CompanyFactKey;
import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code COMPANY}-sourced placeholder values from
 * {@code company_facts} for the person's <b>derived</b> company
 * (template-clauses spec §4.9).
 * <p>
 * The company is derived, never chosen: the employee flow uses the
 * target employee's active {@code UserStatus} company, the recruitment
 * flow the candidate's target company. No prepare API takes a company
 * parameter.
 * <p>
 * Fail-closed rule: a placeholder that explicitly names a fact
 * ({@code source=COMPANY} + {@code source_field}) MUST resolve — a
 * missing fact raises {@link MissingCompanyFactException} naming the
 * fact and pointing at Settings → Selskaber, never a silently blank
 * document (poi-tl's DiscardHandler deletes unmatched tags). Legacy
 * placeholders ({@code source_field} NULL) resolve best-effort from the
 * key-derived fact and otherwise keep the client-supplied value, so
 * pre-facts templates keep working unchanged.
 */
@JBossLog
@ApplicationScoped
public class CompanyPlaceholderResolver {

    /** {@code ${COMPANY_*}} tokens in default-signer name/email fields. */
    private static final Pattern COMPANY_TOKEN = Pattern.compile("\\$\\{(COMPANY_[A-Z0-9_]+)}");

    @Inject
    StatusService statusService;

    /** The derived company: uuid + display name for chips and error texts. */
    public record CompanyContext(String companyUuid, String companyName) {
    }

    /**
     * A template references company facts the derived company has not
     * filled. Extends {@link IllegalArgumentException} so the signing
     * resource's existing validation catch maps it to 400.
     */
    public static class MissingCompanyFactException extends IllegalArgumentException {
        private final List<String> missingFactKeys;

        public MissingCompanyFactException(String companyName, List<String> missingFactKeys) {
            super("Missing company fact" + (missingFactKeys.size() == 1 ? "" : "s") + " "
                    + String.join(", ", missingFactKeys) + " for " + companyName
                    + " — fill " + (missingFactKeys.size() == 1 ? "it" : "them")
                    + " under Settings → Selskaber before preparing this document");
            this.missingFactKeys = List.copyOf(missingFactKeys);
        }

        public List<String> getMissingFactKeys() {
            return missingFactKeys;
        }
    }

    /** The employee flow's derived company: the active employment status' company. */
    public Optional<CompanyContext> deriveForUser(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            return Optional.empty();
        }
        UserStatus status = statusService.getLatestEmploymentStatus(userUuid);
        if (status == null || status.getCompany() == null) {
            return Optional.empty();
        }
        return Optional.of(new CompanyContext(status.getCompany().getUuid(), status.getCompany().getName()));
    }

    /** The recruitment flow's derived company: the candidate's target company. */
    public Optional<CompanyContext> deriveForCompanyUuid(String companyUuid) {
        if (companyUuid == null || companyUuid.isBlank()) {
            return Optional.empty();
        }
        Company company = Company.findById(companyUuid);
        if (company == null) {
            return Optional.empty();
        }
        return Optional.of(new CompanyContext(company.getUuid(), company.getName()));
    }

    /**
     * Merge fact-resolved values for every COMPANY-sourced placeholder of
     * the template into {@code values} (in place; server values win over
     * client-supplied ones so the fact store stays authoritative).
     *
     * @param templateUuid the template whose placeholders define what to resolve
     * @param values       mutable value map about to be rendered
     * @param company      derived company, or empty when the person has none
     * @throws MissingCompanyFactException when an explicitly mapped fact is
     *                                     missing, or when the template
     *                                     explicitly references facts but no
     *                                     company could be derived
     */
    public void applyCompanyFacts(String templateUuid, Map<String, String> values,
                                  Optional<CompanyContext> company) {
        if (templateUuid == null || templateUuid.isBlank()) {
            return;
        }
        List<TemplatePlaceholderEntity> placeholders =
                TemplatePlaceholderEntity.find("template.uuid = ?1", templateUuid).list();
        List<PlaceholderRef> refs = placeholders.stream()
                .map(p -> new PlaceholderRef(p.getPlaceholderKey(), p.getSource(), p.getSourceField()))
                .toList();

        if (company.isEmpty()) {
            List<String> explicit = refs.stream()
                    .filter(PlaceholderRef::isExplicitCompanyFact)
                    .map(PlaceholderRef::sourceField)
                    .toList();
            if (!explicit.isEmpty()) {
                throw new MissingCompanyFactException("the person's company (no active company found)", explicit);
            }
            return;
        }

        Map<String, String> facts = CompanyFactEntity.factMap(company.get().companyUuid());
        mergeCompanyValues(refs, facts, company.get().companyName(), values);
    }

    /**
     * Resolve {@code ${COMPANY_*}} tokens in a default-signer name/email
     * against the company's facts. Tokens without a matching fact are left
     * untouched — {@link #requireNoUnresolvedCompanyTokens} fails the send
     * when one survives all resolution steps.
     */
    public String resolveCompanyTokens(String value, Optional<CompanyContext> company) {
        if (value == null || company.isEmpty() || !value.contains("${COMPANY_")) {
            return value;
        }
        Map<String, String> facts = CompanyFactEntity.factMap(company.get().companyUuid());
        return substituteCompanyTokens(value, facts);
    }

    /**
     * Fail-closed guard for the counter-signer path: a signer field still
     * carrying a {@code ${COMPANY_*}} token would reach NextSign as a
     * literal, so the send is refused naming the missing facts.
     */
    public void requireNoUnresolvedCompanyTokens(List<String> signerFields, Optional<CompanyContext> company) {
        List<String> unresolved = new ArrayList<>();
        for (String field : signerFields) {
            if (field == null) {
                continue;
            }
            Matcher matcher = COMPANY_TOKEN.matcher(field);
            while (matcher.find()) {
                String factKey = CompanyFactKey.factKeyForPlaceholder(matcher.group(1)).orElse(matcher.group(1));
                if (!unresolved.contains(factKey)) {
                    unresolved.add(factKey);
                }
            }
        }
        if (!unresolved.isEmpty()) {
            String companyName = company.map(CompanyContext::companyName)
                    .orElse("the person's company (no active company found)");
            throw new MissingCompanyFactException(companyName, unresolved);
        }
    }

    // ---- Pure core (DB-free, unit-tested) --------------------------------------

    /** The slice of a placeholder the resolver needs. */
    public record PlaceholderRef(String key, DataSource source, String sourceField) {
        boolean isExplicitCompanyFact() {
            return source == DataSource.COMPANY && sourceField != null && !sourceField.isBlank();
        }
    }

    /**
     * Merge company-fact values into {@code values}. Explicitly mapped
     * placeholders fail closed on a missing fact; legacy ones fall back to
     * the client value.
     */
    static void mergeCompanyValues(List<PlaceholderRef> placeholders, Map<String, String> facts,
                                   String companyName, Map<String, String> values) {
        List<String> missing = new ArrayList<>();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (PlaceholderRef placeholder : placeholders) {
            if (placeholder.source() != DataSource.COMPANY) {
                continue;
            }
            if (placeholder.isExplicitCompanyFact()) {
                String factKey = placeholder.sourceField().trim().toUpperCase(java.util.Locale.ROOT);
                String value = facts.get(factKey);
                if (value == null || value.isBlank()) {
                    missing.add(factKey);
                } else {
                    resolved.put(placeholder.key(), value);
                }
            } else {
                // Legacy keyword fallback: resolve when the derived fact
                // exists, otherwise keep whatever the client supplied.
                CompanyFactKey.factKeyForPlaceholder(placeholder.key())
                        .map(facts::get)
                        .filter(v -> !v.isBlank())
                        .ifPresent(v -> resolved.put(placeholder.key(), v));
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingCompanyFactException(companyName, missing);
        }
        values.putAll(resolved);
    }

    /** Replace {@code ${COMPANY_*}} tokens with fact values where known. */
    static String substituteCompanyTokens(String value, Map<String, String> facts) {
        Matcher matcher = COMPANY_TOKEN.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String factKey = CompanyFactKey.factKeyForPlaceholder(matcher.group(1)).orElse(null);
            String fact = factKey != null ? facts.get(factKey) : null;
            matcher.appendReplacement(out, Matcher.quoteReplacement(fact != null ? fact : matcher.group(0)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
