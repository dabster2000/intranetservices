package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledCodeMap;
import dk.trustworks.intranet.aggregates.invoice.services.UserCompanyResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves a Magnit code to a consultant (via the map) and the issuer company; confirms mappings. */
@ApplicationScoped
public class SelfBilledCodeResolver {

    @Inject UserCompanyResolver userCompanyResolver;

    /** Mapped consultant uuid for a code, or null when unmapped. */
    public String resolve(String agreementCompanyUuid, int accountNumber, String code) {
        SelfBilledCodeMap m = SelfBilledCodeMap.findMapping(agreementCompanyUuid, accountNumber, code);
        return m != null ? m.consultantUuid : null;
    }

    /** Consultant's company as-of the work-period first day (issuer side of the transfer); null if unresolved. */
    public String resolveIssuerCompany(String consultantUuid, int workYear, int workMonth) {
        if (consultantUuid == null) return null;
        LocalDate asOf = LocalDate.of(workYear, workMonth, 1);
        Map<String, String> companies = userCompanyResolver.resolveCompanies(Set.of(consultantUuid), asOf);
        return companies.get(consultantUuid);
    }

    /** Confirm one code→consultant mapping (review-queue action). Re-capture re-resolves the lines. */
    @Transactional
    public void confirmMapping(String agreementCompanyUuid, int accountNumber, String code,
                              String consultantUuid, String createdBy) {
        SelfBilledCodeMap m = SelfBilledCodeMap.findMapping(agreementCompanyUuid, accountNumber, code);
        boolean isNew = (m == null);
        if (isNew) {
            m = new SelfBilledCodeMap();
            m.uuid = UUID.randomUUID().toString();
            m.agreementCompanyUuid = agreementCompanyUuid;
            m.accountNumber = accountNumber;
            m.code = code;
            m.createdAt = LocalDateTime.now();
        }
        m.consultantUuid = consultantUuid;   // NOT NULL — must be set BEFORE persist() on the create path
        m.createdBy = createdBy;
        if (isNew) m.persist();
    }
}
