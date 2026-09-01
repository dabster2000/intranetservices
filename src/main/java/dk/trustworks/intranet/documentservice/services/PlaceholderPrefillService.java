package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.documentservice.dto.PlaceholderPrefillResponse;
import dk.trustworks.intranet.documentservice.dto.PlaceholderPrefillResponse.FactSuggestion;
import dk.trustworks.intranet.documentservice.dto.PlaceholderPrefillResponse.PrefillField;
import dk.trustworks.intranet.documentservice.model.CompanyFactEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.enums.CompanyFactKey;
import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.services.CompanyPlaceholderResolver.CompanyContext;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Decides, per template placeholder, who fills the field and with what
 * (template-clauses spec §5.1): company facts and system dates are not
 * inputs; person fields prefill from the profile (employee flow) or the
 * candidate record (dossier flow) with provenance; interview facts
 * surface as click-to-apply suggestions only; the remainder is what the
 * preparer actually has to type.
 * <p>
 * Sensitive person fields (CPR, current monthly salary) never leave the
 * backend through this response: they are flagged {@code serverResolved}
 * with a masked preview, and {@link #applyServerResolvedPersonFields}
 * injects the real values into the value map at document-generation
 * time — no browser round-trip.
 */
@JBossLog
@ApplicationScoped
public class PlaceholderPrefillService {

    // Explicit USER source-field names (spec §5.1).
    static final String FIELD_NAME = "NAME";
    static final String FIELD_FIRSTNAME = "FIRSTNAME";
    static final String FIELD_LASTNAME = "LASTNAME";
    static final String FIELD_EMAIL = "EMAIL";
    static final String FIELD_PHONE = "PHONE";
    static final String FIELD_ADDRESS = "ADDRESS";
    static final String FIELD_TITLE = "TITLE";
    static final String FIELD_CPR = "CPR";
    static final String FIELD_HIRE_DATE = "HIRE_DATE";
    static final String FIELD_CURRENT_MONTHLY_SALARY = "CURRENT_MONTHLY_SALARY";

    static final String PROVENANCE_COMPANY_FACT = "COMPANY_FACT";
    static final String PROVENANCE_PROFILE = "PROFILE";
    static final String PROVENANCE_CANDIDATE = "CANDIDATE";
    static final String PROVENANCE_SYSTEM = "SYSTEM";

    static final String MASKED_CPR = "••••••-••••";
    static final String MASKED_SALARY = "••.••• kr.";

    @Inject
    CompanyPlaceholderResolver companyResolver;

    @Inject
    StatusService statusService;

    @Inject
    SalaryService salaryService;

    /** The candidate slice the dossier prefill needs — no recruitment import. */
    public record CandidateSubject(String firstName, String lastName, String email, String phone,
                                   String targetCompanyUuid) {
    }

    /** Employee flow: profile + company facts, sensitive fields server-resolved. */
    public PlaceholderPrefillResponse prefillForEmployee(String templateUuid, String userUuid) {
        Optional<CompanyContext> company = companyResolver.deriveForUser(userUuid);
        User user = User.findById(userUuid);
        return build(templateUuid, company, "EMPLOYEE", new PersonValues(user), Map.of());
    }

    /**
     * Dossier flow: candidate record + candidate's target company.
     * Employee-only fields (CPR, salary, hire date, title, address) stay
     * manual; interview-fact suggestions are supplied by the recruitment
     * resource (comp-tier already enforced there).
     *
     * @param factSuggestions fact key → suggestion (newest readable value),
     *                        already visibility-filtered by the caller
     */
    public PlaceholderPrefillResponse prefillForCandidate(String templateUuid, CandidateSubject candidate,
                                                          Map<String, FactSuggestion> factSuggestions) {
        Optional<CompanyContext> company = companyResolver.deriveForCompanyUuid(candidate.targetCompanyUuid());
        return build(templateUuid, company, "CANDIDATE", new PersonValues(candidate), factSuggestions);
    }

    /**
     * Generation-time server fill (employee flow): placeholders whose
     * resolved USER field is CPR or CURRENT_MONTHLY_SALARY get their real
     * value injected when the client left them blank. A preparer-typed
     * value always wins.
     */
    public void applyServerResolvedPersonFields(String templateUuid, Map<String, String> values, String userUuid) {
        if (templateUuid == null || templateUuid.isBlank() || userUuid == null || userUuid.isBlank()) {
            return;
        }
        List<TemplatePlaceholderEntity> placeholders =
                TemplatePlaceholderEntity.find("template.uuid = ?1", templateUuid).list();
        PersonValues person = null;
        for (TemplatePlaceholderEntity placeholder : placeholders) {
            if (placeholder.getSource() != DataSource.USER) {
                continue;
            }
            String field = effectiveUserField(placeholder.getPlaceholderKey(), placeholder.getSourceField());
            if (!FIELD_CPR.equals(field) && !FIELD_CURRENT_MONTHLY_SALARY.equals(field)) {
                continue;
            }
            String existing = values.get(placeholder.getPlaceholderKey());
            if (existing != null && !existing.isBlank()) {
                continue;
            }
            if (person == null) {
                person = new PersonValues(User.findById(userUuid));
            }
            String resolved = person.resolve(field);
            if (resolved != null && !resolved.isBlank()) {
                values.put(placeholder.getPlaceholderKey(), resolved);
                log.infof("Server-resolved sensitive field %s for placeholder %s (template %s)",
                        field, placeholder.getPlaceholderKey(), templateUuid);
            }
        }
    }

    // ---- Core ------------------------------------------------------------------

    private PlaceholderPrefillResponse build(String templateUuid, Optional<CompanyContext> company,
                                             String companySource, PersonValues person,
                                             Map<String, FactSuggestion> factSuggestions) {
        List<TemplatePlaceholderEntity> placeholders =
                TemplatePlaceholderEntity.find("template.uuid = ?1 ORDER BY displayOrder, label", templateUuid).list();
        Map<String, String> facts = company
                .map(c -> CompanyFactEntity.factMap(c.companyUuid()))
                .orElse(Map.of());

        List<String> missingFacts = new ArrayList<>();
        List<PrefillField> fields = new ArrayList<>(placeholders.size());
        for (TemplatePlaceholderEntity placeholder : placeholders) {
            fields.add(prefillField(placeholder, facts, company.isPresent(), person, factSuggestions, missingFacts));
        }

        return new PlaceholderPrefillResponse(
                company.map(CompanyContext::companyUuid).orElse(null),
                company.map(CompanyContext::companyName).orElse(null),
                companySource,
                missingFacts,
                fields);
    }

    private PrefillField prefillField(TemplatePlaceholderEntity placeholder, Map<String, String> facts,
                                      boolean hasCompany, PersonValues person,
                                      Map<String, FactSuggestion> factSuggestions, List<String> missingFacts) {
        String key = placeholder.getPlaceholderKey();
        String sourceField = placeholder.getSourceField();
        DataSource source = placeholder.getSource() == null ? DataSource.MANUAL : placeholder.getSource();

        switch (source) {
            case COMPANY -> {
                boolean explicit = sourceField != null && !sourceField.isBlank();
                String factKey = explicit
                        ? sourceField.trim().toUpperCase(Locale.ROOT)
                        : CompanyFactKey.factKeyForPlaceholder(key).orElse(null);
                String value = factKey != null ? facts.get(factKey) : null;
                if (explicit && (value == null || value.isBlank())) {
                    String missing = hasCompany ? factKey : factKey + " (no company derived)";
                    if (!missingFacts.contains(missing)) {
                        missingFacts.add(missing);
                    }
                    value = null;
                }
                // Legacy COMPANY placeholders without a resolvable fact stay
                // editable inputs (pre-facts behavior) — not auto-resolved.
                boolean autoResolved = explicit || value != null;
                return new PrefillField(key, source.name(), sourceField, value,
                        value != null ? PROVENANCE_COMPANY_FACT : null, autoResolved, false, null, List.of());
            }
            case SYSTEM_DATE -> {
                return new PrefillField(key, source.name(), sourceField,
                        LocalDate.now().toString(), PROVENANCE_SYSTEM, true, false, null, List.of());
            }
            case USER -> {
                String field = effectiveUserField(key, sourceField);
                if (person.serverResolvedOnly(field)) {
                    String masked = FIELD_CPR.equals(field) ? MASKED_CPR : MASKED_SALARY;
                    boolean resolvable = person.canServerResolve(field);
                    return new PrefillField(key, source.name(), sourceField, null,
                            resolvable ? person.provenance() : null, false, resolvable,
                            resolvable ? masked : null, List.of());
                }
                String value = person.resolve(field);
                boolean resolved = value != null && !value.isBlank();
                return new PrefillField(key, source.name(), sourceField,
                        resolved ? value : null, resolved ? person.provenance() : null,
                        false, false, null, List.of());
            }
            case INTERVIEW_FACT -> {
                FactSuggestion suggestion = sourceField == null ? null
                        : factSuggestions.get(sourceField.trim().toUpperCase(Locale.ROOT));
                return new PrefillField(key, source.name(), sourceField, null, null, false, false, null,
                        suggestion == null ? List.of() : List.of(suggestion));
            }
            default -> {
                return new PrefillField(key, source.name(), sourceField, null, null, false, false, null, List.of());
            }
        }
    }

    /**
     * The USER field a placeholder resolves: the explicit
     * {@code source_field} when set, otherwise the legacy keyword match on
     * the placeholder key (mirrors the frontend seeding heuristics).
     */
    static String effectiveUserField(String placeholderKey, String sourceField) {
        if (sourceField != null && !sourceField.isBlank()) {
            return sourceField.trim().toUpperCase(Locale.ROOT);
        }
        String key = placeholderKey == null ? "" : placeholderKey.toUpperCase(Locale.ROOT);
        if (key.contains("FIRSTNAME") || key.contains("FIRST_NAME") || key.contains("FORNAVN")) {
            return FIELD_FIRSTNAME;
        }
        if (key.contains("LASTNAME") || key.contains("LAST_NAME") || key.contains("EFTERNAVN")) {
            return FIELD_LASTNAME;
        }
        if (key.contains("NAME") || key.contains("NAVN")) {
            return FIELD_NAME;
        }
        if (key.contains("EMAIL") || key.contains("MAIL")) {
            return FIELD_EMAIL;
        }
        if (key.contains("PHONE") || key.contains("TELEFON") || key.contains("TLF")) {
            return FIELD_PHONE;
        }
        if (key.contains("CPR")) {
            return FIELD_CPR;
        }
        if (key.contains("ADDRESS") || key.contains("ADRESSE")) {
            return FIELD_ADDRESS;
        }
        if (key.contains("SALARY") || key.contains("LOEN") || key.contains("LØN")) {
            return FIELD_CURRENT_MONTHLY_SALARY;
        }
        if (key.contains("TITLE") || key.contains("STILLING")) {
            return FIELD_TITLE;
        }
        if (key.contains("HIRE") || key.contains("ANSAT_DATO") || key.contains("ANSAETTELSESDATO")) {
            return FIELD_HIRE_DATE;
        }
        return FIELD_NAME;
    }

    /**
     * Person-value resolution for either subject kind. Employee resolves
     * the full profile (sensitive fields only server-side); candidate
     * resolves name/email/phone and leaves employee-only fields manual.
     */
    private class PersonValues {

        private final User user;
        private final CandidateSubject candidate;

        PersonValues(User user) {
            this.user = user;
            this.candidate = null;
        }

        PersonValues(CandidateSubject candidate) {
            this.user = null;
            this.candidate = candidate;
        }

        String provenance() {
            return user != null ? PROVENANCE_PROFILE : PROVENANCE_CANDIDATE;
        }

        /** CPR and current salary never travel through the prefill response. */
        boolean serverResolvedOnly(String field) {
            return user != null && (FIELD_CPR.equals(field) || FIELD_CURRENT_MONTHLY_SALARY.equals(field));
        }

        boolean canServerResolve(String field) {
            String value = resolve(field);
            return value != null && !value.isBlank();
        }

        String resolve(String field) {
            if (candidate != null) {
                return switch (field) {
                    case FIELD_NAME -> joinName(candidate.firstName(), candidate.lastName());
                    case FIELD_FIRSTNAME -> candidate.firstName();
                    case FIELD_LASTNAME -> candidate.lastName();
                    case FIELD_EMAIL -> candidate.email();
                    case FIELD_PHONE -> candidate.phone();
                    // Employee-only fields stay manual for candidates.
                    default -> null;
                };
            }
            if (user == null) {
                return null;
            }
            return switch (field) {
                case FIELD_NAME -> joinName(user.getFirstname(), user.getLastname());
                case FIELD_FIRSTNAME -> user.getFirstname();
                case FIELD_LASTNAME -> user.getLastname();
                case FIELD_EMAIL -> user.getEmail();
                case FIELD_PHONE -> user.getPhone();
                case FIELD_CPR -> user.getCpr();
                case FIELD_ADDRESS -> currentAddress(user.getUuid());
                case FIELD_TITLE -> currentTitle(user.getUuid());
                case FIELD_HIRE_DATE -> user.getHireDate() != null ? user.getHireDate().toString() : null;
                case FIELD_CURRENT_MONTHLY_SALARY -> currentMonthlySalary(user.getUuid());
                default -> null;
            };
        }

        private String currentAddress(String userUuid) {
            UserContactinfo contactInfo = UserContactinfo.findCurrentByUseruuid(userUuid);
            if (contactInfo == null) {
                return null;
            }
            StringBuilder address = new StringBuilder();
            if (contactInfo.getStreetname() != null && !contactInfo.getStreetname().isBlank()) {
                address.append(contactInfo.getStreetname().trim());
            }
            String cityPart = joinName(contactInfo.getPostalcode(), contactInfo.getCity());
            if (cityPart != null && !cityPart.isBlank()) {
                if (!address.isEmpty()) {
                    address.append(", ");
                }
                address.append(cityPart);
            }
            return address.isEmpty() ? null : address.toString();
        }

        private String currentTitle(String userUuid) {
            UserStatus status = statusService.getLatestEmploymentStatus(userUuid);
            return status != null && status.getType() != null ? status.getType().name() : null;
        }

        private String currentMonthlySalary(String userUuid) {
            Salary salary = salaryService.getUserSalaryByMonth(userUuid, LocalDate.now());
            if (salary == null || salary.getSalary() <= 0 || salary.getType() == SalaryType.HOURLY) {
                return null;
            }
            return String.valueOf(salary.getSalary());
        }
    }

    private static String joinName(String first, String last) {
        String joined = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return joined.isEmpty() ? null : joined;
    }

    /** Suggestions map builder used by the recruitment resource — keeps the LinkedHashMap ordering. */
    public static Map<String, FactSuggestion> suggestionMap() {
        return new LinkedHashMap<>();
    }
}
