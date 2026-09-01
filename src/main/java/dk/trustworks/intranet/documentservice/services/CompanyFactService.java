package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.model.CompanyFactEntity;
import dk.trustworks.intranet.documentservice.model.enums.CompanyFactKey;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CRUD for the per-company fact store (template-clauses spec §4.9).
 * Mutations ride the template-admin posture: the resource enforces
 * HR/ADMIN via {@link dk.trustworks.intranet.documentservice.security.TemplateAccessPolicy},
 * and every row carries the standard audit columns via
 * {@link dk.trustworks.intranet.security.AuditEntityListener}.
 */
@JBossLog
@ApplicationScoped
public class CompanyFactService {

    public List<CompanyFactEntity> findByCompany(String companyUuid) {
        requireCompany(companyUuid);
        return CompanyFactEntity.findByCompany(companyUuid);
    }

    public List<CompanyFactEntity> findAll() {
        return CompanyFactEntity.listAll();
    }

    /**
     * Create-or-update one fact for one company. The natural key is
     * {@code (company, factKey)} — upserting keeps the Settings editor a
     * simple value grid with no uuid bookkeeping.
     */
    @Transactional
    public CompanyFactEntity upsert(String companyUuid, String factKey, String factValue) {
        requireCompany(companyUuid);
        String normalizedKey = normalizeKey(factKey);
        String normalizedValue = factValue == null ? "" : factValue.trim();
        if (normalizedValue.isEmpty()) {
            throw new WebApplicationException("Fact value is required — delete the fact instead of blanking it", 400);
        }
        if (normalizedValue.length() > 500) {
            throw new WebApplicationException("Fact value must be at most 500 characters", 400);
        }

        CompanyFactEntity fact = CompanyFactEntity.findByCompanyAndKey(companyUuid, normalizedKey)
                .orElseGet(() -> {
                    CompanyFactEntity created = new CompanyFactEntity();
                    created.setCompanyUuid(companyUuid);
                    created.setFactKey(normalizedKey);
                    return created;
                });
        fact.setFactValue(normalizedValue);
        fact.persist();
        log.infof("Company fact upserted: company=%s key=%s", companyUuid, normalizedKey);
        return fact;
    }

    @Transactional
    public void delete(String companyUuid, String factKey) {
        requireCompany(companyUuid);
        String normalizedKey = normalizeKey(factKey);
        long deleted = CompanyFactEntity.delete("companyUuid = ?1 AND factKey = ?2", companyUuid, normalizedKey);
        if (deleted == 0) {
            throw new WebApplicationException("Fact not found: " + normalizedKey, 404);
        }
        log.infof("Company fact deleted: company=%s key=%s", companyUuid, normalizedKey);
    }

    /** One company's facts as {@code factKey → factValue}. */
    public Map<String, String> factMap(String companyUuid) {
        return CompanyFactEntity.factMap(companyUuid);
    }

    private static String normalizeKey(String factKey) {
        String normalized = factKey == null ? "" : factKey.trim().toUpperCase(Locale.ROOT);
        if (!CompanyFactKey.isValidKey(normalized)) {
            throw new WebApplicationException(
                    "Invalid fact key '" + factKey + "' — use uppercase letters, digits and underscores (e.g. PENSION_PROVIDER)",
                    400);
        }
        return normalized;
    }

    private static void requireCompany(String companyUuid) {
        if (companyUuid == null || companyUuid.isBlank() || Company.findById(companyUuid) == null) {
            throw new WebApplicationException("Company not found: " + companyUuid, 404);
        }
    }
}
