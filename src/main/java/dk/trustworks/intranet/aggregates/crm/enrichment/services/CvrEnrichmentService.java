package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.aggregates.crm.enrichment.ai.CvrCandidateFinder;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.ClientEnrichment;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.CvrEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.trustlink.services.TrustLinkCompanyMatcher;
import dk.trustworks.intranet.dao.crm.client.CvrApiResponse;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import dk.trustworks.intranet.dao.crm.services.CvrLookupService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The nightly CVR verification for one client (decision set of 2026-09-14).
 *
 * <p>Two halves, by whether the row has a CVR:
 * <ul>
 *   <li><b>CVR present:</b> the registry is asked about it and its answer overwrites the
 *       row ({@link RegistryApplier}). A CVR the registry does not know is
 *       {@code INVALID}; one another client already carries is {@code DUPLICATE}.</li>
 *   <li><b>CVR missing:</b> the registry's own name search is tried first (free, exact),
 *       then the model with web search proposes a number. Either proposal is looked up in
 *       the registry and assigned only when the registry's legal name is the client's
 *       name after normalisation — {@link TrustLinkCompanyMatcher#isSameCompany}, the same
 *       strict tier the TrustLink seeder trusts unattended. Otherwise the proposal is
 *       stored as a {@code CANDIDATE} for a person to confirm with one click.</li>
 * </ul>
 *
 * <p><b>No transaction spans a round-trip.</b> The snapshot is read in one, the registry
 * and the model are called with none open, and the write is its own — the §P9 M1 rule.
 *
 * <p>A Virkdata quota answer ({@code 429}) aborts the whole night's CVR pass through
 * {@link QuotaExhausted}: every remaining client would get the same answer and be filed
 * {@code FAILED} for nothing.
 */
@JBossLog
@ApplicationScoped
public class CvrEnrichmentService {

    /** The registry said no more tonight. */
    public static class QuotaExhausted extends RuntimeException {
        public QuotaExhausted() {
            super("Virkdata quota exhausted");
        }
    }

    /** One registry lookup that did not produce a company. {@code status} is the HTTP status our service mapped it to. */
    static class LookupFailed extends Exception {
        final int status;

        LookupFailed(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    record Snapshot(String uuid, String name, String cvr, String country, String city, String zipcode) {}

    @Inject
    CvrLookupService cvrLookupService;

    @Inject
    CvrCandidateFinder finder;

    @Inject
    ClientEnrichmentRepository repository;

    @Inject
    ClientActivityLogService activityLog;

    /**
     * Verifies one client. Runs outside any transaction; returns the status it filed, or
     * empty when the client no longer exists.
     *
     * @throws QuotaExhausted when the registry refused for quota — the caller stops the pass
     */
    public Optional<CvrEnrichmentStatus> verify(String clientUuid) {
        Snapshot s = QuarkusTransaction.requiringNew().call(() -> snapshot(clientUuid));
        if (s == null) return Optional.empty();
        if (s.country() != null && !s.country().isBlank() && !"DK".equalsIgnoreCase(s.country().trim())) {
            return Optional.of(record(s.uuid(), CvrEnrichmentStatus.SKIPPED, null, null, false));
        }
        if (s.cvr() != null && !s.cvr().isBlank()) {
            return Optional.of(verifyStored(s));
        }
        return Optional.of(findMissing(s));
    }

    /** The person accepted the proposed CVR: look it up again and apply, or explain why not. */
    public CvrEnrichmentStatus acceptCandidate(String clientUuid) {
        Snapshot s = QuarkusTransaction.requiringNew().call(() -> snapshot(clientUuid));
        if (s == null) throw new WebApplicationException("Client not found", 404);
        String candidate = QuarkusTransaction.requiringNew().call(() ->
                repository.find(clientUuid).map(ClientEnrichment::getCvrCandidate).orElse(null));
        if (candidate == null || candidate.isBlank()) {
            throw new WebApplicationException("There is no proposed CVR to accept", 409);
        }
        Optional<Client> other = repository.otherClientWithCvr(candidate, clientUuid);
        if (other.isPresent()) {
            record(clientUuid, CvrEnrichmentStatus.DUPLICATE, "CVR " + candidate + " is already on " + other.get().getName(), null, true);
            throw new WebApplicationException("CVR " + candidate + " is already on " + other.get().getName(), 409);
        }
        CvrApiResponse registry;
        try {
            registry = lookup(candidate);
        } catch (LookupFailed e) {
            throw new WebApplicationException("The registry lookup for CVR " + candidate + " failed: " + e.getMessage(),
                    e.status == 404 ? 404 : 502);
        }
        return applyRegistry(s, registry, candidate, true);
    }

    // ------------------------------------------------------------------------
    // The two halves
    // ------------------------------------------------------------------------

    private CvrEnrichmentStatus verifyStored(Snapshot s) {
        String cvr = s.cvr().trim();
        Optional<Client> other = repository.otherClientWithCvr(cvr, s.uuid());
        if (other.isPresent()) {
            return record(s.uuid(), CvrEnrichmentStatus.DUPLICATE,
                    "CVR " + cvr + " is also on " + other.get().getName(), null, true);
        }
        CvrApiResponse registry;
        try {
            registry = lookup(cvr);
        } catch (LookupFailed e) {
            if (e.status == 404 || e.status == 400) {
                return record(s.uuid(), CvrEnrichmentStatus.INVALID,
                        "The CVR registry knows no company with CVR " + cvr, null, true);
            }
            return record(s.uuid(), CvrEnrichmentStatus.FAILED, "Registry lookup failed: " + e.getMessage(), null, true);
        }
        return applyRegistry(s, registry, cvr, true);
    }

    private CvrEnrichmentStatus findMissing(Snapshot s) {
        // 1. The registry's own name search — free, and exact when it matches.
        try {
            CvrApiResponse byName = cvrLookupService.searchByName(s.name(), "dk");
            String cvr = eightDigits(byName.vat);
            if (cvr != null && TrustLinkCompanyMatcher.isSameCompany(s.name(), byName.name)) {
                Optional<Client> other = repository.otherClientWithCvr(cvr, s.uuid());
                if (other.isPresent()) {
                    return record(s.uuid(), CvrEnrichmentStatus.DUPLICATE,
                            "CVR " + cvr + " (" + byName.name + ") is already on " + other.get().getName(), null, true);
                }
                return applyRegistry(s, byName, cvr, true);
            }
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 429) throw new QuotaExhausted();
            log.debugf("Registry name search for '%s' gave nothing usable (status=%s) — asking the model",
                    s.name(), e.getResponse() != null ? e.getResponse().getStatus() : "?");
        } catch (RuntimeException e) {
            log.debugf("Registry name search for '%s' failed (%s) — asking the model", s.name(), e.getClass().getSimpleName());
        }

        // 2. The model, with web search.
        Optional<CvrCandidateFinder.Candidate> proposal = finder.find(s.name(), s.city(), s.zipcode());
        if (proposal.isEmpty()) {
            return record(s.uuid(), CvrEnrichmentStatus.NOT_FOUND, "No CVR could be found for this name", null, true);
        }
        CvrCandidateFinder.Candidate candidate = proposal.get();
        Optional<Client> other = repository.otherClientWithCvr(candidate.cvr(), s.uuid());
        if (other.isPresent()) {
            return record(s.uuid(), CvrEnrichmentStatus.DUPLICATE,
                    "CVR " + candidate.cvr() + " is already on " + other.get().getName(), null, true);
        }
        CvrApiResponse registry;
        try {
            registry = lookup(candidate.cvr());
        } catch (LookupFailed e) {
            if (e.status == 404 || e.status == 400) {
                return record(s.uuid(), CvrEnrichmentStatus.NOT_FOUND,
                        "The model proposed CVR " + candidate.cvr() + " but the registry knows no such company", null, true);
            }
            return record(s.uuid(), CvrEnrichmentStatus.FAILED, "Registry lookup failed: " + e.getMessage(), null, true);
        }
        if (TrustLinkCompanyMatcher.isSameCompany(s.name(), registry.name)) {
            return applyRegistry(s, registry, candidate.cvr(), true);
        }
        return recordCandidate(s.uuid(), candidate.cvr(), registry.name, candidate.sourceUrl());
    }

    // ------------------------------------------------------------------------
    // Registry access
    // ------------------------------------------------------------------------

    /** {@code CvrLookupService} reports every failure as a {@code WebApplicationException}; this sorts them. */
    private CvrApiResponse lookup(String cvr) throws LookupFailed {
        try {
            return cvrLookupService.lookupByCvr(cvr, "dk");
        } catch (WebApplicationException e) {
            int status = e.getResponse() != null ? e.getResponse().getStatus() : 0;
            if (status == 429) throw new QuotaExhausted();
            throw new LookupFailed(status, e.getMessage() != null ? e.getMessage() : "HTTP " + status);
        } catch (RuntimeException e) {
            throw new LookupFailed(0, e.getClass().getSimpleName());
        }
    }

    static String eightDigits(long vat) {
        if (vat <= 0) return null;
        String s = String.format("%08d", vat);
        return s.length() == 8 ? s : null;
    }

    // ------------------------------------------------------------------------
    // Writes — each its own transaction
    // ------------------------------------------------------------------------

    private CvrEnrichmentStatus applyRegistry(Snapshot s, CvrApiResponse registry, String cvr, boolean countAttempt) {
        QuarkusTransaction.requiringNew().run(() -> {
            Client client = Client.findById(s.uuid());
            if (client == null) return;
            String entityName = client.getName();
            List<RegistryApplier.FieldChange> changes = RegistryApplier.apply(client, registry, cvr);
            for (RegistryApplier.FieldChange change : changes) {
                activityLog.logFieldChange(s.uuid(), ClientActivityLog.TYPE_CLIENT, s.uuid(), entityName,
                        change.field(), change.oldValue(), change.newValue());
            }
            ClientEnrichment row = repository.ensure(s.uuid());
            if (row == null) return;
            LocalDateTime now = LocalDateTime.now();
            row.setCvr(CvrEnrichmentStatus.VERIFIED);
            row.setCvrCheckedAt(now);
            row.setCvrVerifiedAt(now);
            row.setCvrError(null);
            row.clearCvrCandidate();
            if (countAttempt) row.setCvrAttempts(row.getCvrAttempts() + 1);
            log.infof("CVR verified: client=%s cvr=%s changes=%d", s.uuid(), cvr, changes.size());
        });
        return CvrEnrichmentStatus.VERIFIED;
    }

    private CvrEnrichmentStatus recordCandidate(String clientUuid, String cvr, String registryName, String sourceUrl) {
        QuarkusTransaction.requiringNew().run(() -> {
            ClientEnrichment row = repository.ensure(clientUuid);
            if (row == null) return;
            row.setCvr(CvrEnrichmentStatus.CANDIDATE);
            row.setCvrCheckedAt(LocalDateTime.now());
            row.setCvrCandidate(cvr);
            row.setCvrCandidateName(registryName);
            row.setCvrCandidateSource(sourceUrl);
            row.setCvrError(null);
            row.setCvrAttempts(row.getCvrAttempts() + 1);
        });
        log.infof("CVR candidate filed: client=%s cvr=%s registryName=%s", clientUuid, cvr, registryName);
        return CvrEnrichmentStatus.CANDIDATE;
    }

    CvrEnrichmentStatus record(String clientUuid, CvrEnrichmentStatus status, String error, String unused, boolean countAttempt) {
        QuarkusTransaction.requiringNew().run(() -> {
            ClientEnrichment row = repository.ensure(clientUuid);
            if (row == null) return;
            row.setCvr(status);
            row.setCvrCheckedAt(LocalDateTime.now());
            row.setCvrError(error == null ? null : CvrCandidateFinder.truncate(error, 255));
            row.clearCvrCandidate();
            if (countAttempt) row.setCvrAttempts(row.getCvrAttempts() + 1);
        });
        if (status != CvrEnrichmentStatus.SKIPPED) {
            log.infof("CVR check filed: client=%s status=%s%s", clientUuid, status, error == null ? "" : " (" + error + ")");
        }
        return status;
    }

    static Snapshot snapshot(String clientUuid) {
        Client c = Client.findById(clientUuid);
        if (c == null) return null;
        return new Snapshot(c.getUuid(), c.getName(), c.getCvr(), c.getBillingCountry(), c.getBillingCity(), c.getBillingZipcode());
    }
}
