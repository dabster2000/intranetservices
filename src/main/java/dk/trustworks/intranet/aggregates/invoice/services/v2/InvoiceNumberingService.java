package dk.trustworks.intranet.aggregates.invoice.services.v2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureQuery;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

/**
 * Service for atomic invoice number generation using database sequences.
 *
 * Uses the next_invoice_number() stored procedure for race-free number assignment.
 *
 * @see <a href="/docs/new-features/invoice-status-design/backend-developer_guide.md">Backend Developer Guide - Section 4.2</a>
 */
@JBossLog
@ApplicationScoped
public class InvoiceNumberingService {

    @Inject
    EntityManager em;

    /**
     * Get next invoice number for given issuer and optional series.
     * Thread-safe and race-free through database-level atomicity.
     *
     * @param issuerCompanyUuid Company UUID issuing the invoice
     * @param series Optional series code (e.g., "EXPORT", "INTERNAL"). Use null for default.
     * @return Next sequential invoice number for this issuer/series combination
     */
    @Transactional
    public int getNextInvoiceNumber(String issuerCompanyUuid, String series) {
        log.debugf("Getting next invoice number for issuer=%s, series=%s", issuerCompanyUuid, series);

        StoredProcedureQuery query = em.createStoredProcedureQuery("next_invoice_number");
        query.registerStoredProcedureParameter("p_issuer", String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter("p_series", String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter("p_next", Integer.class, ParameterMode.OUT);

        query.setParameter("p_issuer", issuerCompanyUuid);
        query.setParameter("p_series", series);

        query.execute();

        Integer nextNumber = (Integer) query.getOutputParameterValue("p_next");

        if (nextNumber == null || nextNumber <= 0) {
            throw new IllegalStateException("Failed to generate invoice number for issuer " + issuerCompanyUuid);
        }

        log.infof("Generated invoice number %d for issuer %s (series: %s)", nextNumber, issuerCompanyUuid, series);
        return nextNumber;
    }

    /**
     * Get next invoice number for issuer (default series).
     */
    @Transactional
    public int getNextInvoiceNumber(String issuerCompanyUuid) {
        return getNextInvoiceNumber(issuerCompanyUuid, null);
    }
}
