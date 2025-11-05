package dk.trustworks.intranet.aggregates.invoice.repositories;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceV2;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.ProcessingState;
import dk.trustworks.intranet.aggregates.invoice.model.enums.QueueReason;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Panache repository for InvoiceV2 entity.
 * Provides custom query methods for invoice management operations.
 */
@ApplicationScoped
public class InvoiceV2Repository implements PanacheRepositoryBase<InvoiceV2, String> {

    /**
     * Find all invoices queued for promotion (internal invoices waiting for source invoice to be paid).
     *
     * @return List of invoices in DRAFT state, QUEUED for processing, with AWAIT_SOURCE_PAID reason
     */
    public List<InvoiceV2> findQueuedForPromotion() {
        return find("lifecycleStatus = ?1 AND processingState = ?2 AND queueReason = ?3",
                    LifecycleStatus.DRAFT, ProcessingState.QUEUED, QueueReason.AWAIT_SOURCE_PAID)
               .list();
    }

    /**
     * Find all invoices that need PDF generation.
     * These are CREATED invoices without a PDF URL or SHA-256 hash.
     *
     * @return List of invoices in CREATED state missing PDF artifacts
     */
    public List<InvoiceV2> findPendingPdfGeneration() {
        return find("lifecycleStatus = ?1 AND (pdfUrl IS NULL OR pdfSha256 IS NULL)",
                    LifecycleStatus.CREATED)
               .list();
    }

    /**
     * Find all invoices by issuer company UUID.
     *
     * @param issuerCompanyUuid The company UUID
     * @return List of invoices for the company
     */
    public List<InvoiceV2> findByIssuerCompany(String issuerCompanyUuid) {
        return find("issuerCompanyuuid", issuerCompanyUuid).list();
    }

    /**
     * Find all invoices by customer company UUID.
     *
     * @param customerCompanyUuid The customer company UUID
     * @return List of invoices for the customer
     */
    public List<InvoiceV2> findByCustomerCompany(String customerCompanyUuid) {
        return find("customerCompanyuuid", customerCompanyUuid).list();
    }

    /**
     * Find all invoices by lifecycle status.
     *
     * @param status The lifecycle status
     * @return List of invoices with the specified status
     */
    public List<InvoiceV2> findByLifecycleStatus(LifecycleStatus status) {
        return find("lifecycleStatus", status).list();
    }

    /**
     * Find all invoices by processing state.
     *
     * @param state The processing state
     * @return List of invoices with the specified processing state
     */
    public List<InvoiceV2> findByProcessingState(ProcessingState state) {
        return find("processingState", state).list();
    }

    /**
     * Find invoices by source invoice UUID (for internal invoices).
     *
     * @param sourceInvoiceUuid The source invoice UUID
     * @return List of internal invoices derived from the source invoice
     */
    public List<InvoiceV2> findBySourceInvoice(String sourceInvoiceUuid) {
        return find("sourceInvoiceUuid", sourceInvoiceUuid).list();
    }
}
