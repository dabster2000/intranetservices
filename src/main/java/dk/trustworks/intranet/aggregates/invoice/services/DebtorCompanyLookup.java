package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Thin wrapper around {@link Company#findById(Object)} so that
 * {@link InvoiceFinalizationOrchestrator} can be unit-tested without
 * requiring a live Panache session.
 *
 * Follows the same pattern as {@link InvoiceRepository} wrapping
 * {@code Invoice.findById}.
 */
@ApplicationScoped
public class DebtorCompanyLookup {

    public Optional<Company> findByUuid(String uuid) {
        return Optional.ofNullable(Company.findById(uuid));
    }
}
