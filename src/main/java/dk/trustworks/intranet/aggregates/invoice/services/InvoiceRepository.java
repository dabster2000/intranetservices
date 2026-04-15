package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * Thin repository wrapper over Invoice's static Panache methods.
 * Exists so the orchestrator can be tested with a mock — Panache static
 * methods cannot be mocked directly with Mockito.
 *
 * SPEC-INV-001 §7.1.
 */
@ApplicationScoped
public class InvoiceRepository {

    public Optional<Invoice> findByUuid(String uuid) {
        return Optional.ofNullable(Invoice.findById(uuid));
    }

    @Transactional
    public void persist(Invoice invoice) {
        Invoice.getEntityManager().merge(invoice);
    }
}
