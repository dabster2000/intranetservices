package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.AutoRunResultDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingCandidateDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingRequestDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingRowDto;
import dk.trustworks.intranet.dao.crm.model.Client;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates Trustworks client ↔ e-conomic customer pairing for a single
 * Trustworks company (one e-conomic agreement per company). Thin service: all
 * heavy lifting — index building, auth wiring, row matching — lives in the
 * supporting collaborators ({@link EconomicsCustomerIndexCache},
 * {@link EconomicsCustomersApiClientFactory}, {@link EconomicsCustomerIndex}).
 *
 * <p>The service exposes two narrow seams — {@link ClientLookup} and
 * {@link AgreementResolver} — so unit tests can mock the Panache/Integration
 * surfaces without booting Quarkus.
 *
 * <p>SPEC-INV-001 §3.3.1, §7.4.
 */
@ApplicationScoped
public class EconomicsCustomerPairingService {

    private static final Logger LOG = Logger.getLogger(EconomicsCustomerPairingService.class);
    /** Max candidates returned from the free-text search endpoint. */
    private static final int SEARCH_LIMIT = 50;

    private final ClientEconomicsCustomerRepository repo;
    private final EconomicsCustomerIndexCache cache;
    private final ClientLookup clientLookup;
    private final AgreementResolver agreementResolver;
    private final AgreementDefaultsRegistry agreementDefaults;

    @Inject
    public EconomicsCustomerPairingService(ClientEconomicsCustomerRepository repo,
                                           EconomicsCustomerIndexCache cache,
                                           ClientLookup clientLookup,
                                           AgreementResolver agreementResolver,
                                           AgreementDefaultsRegistry agreementDefaults) {
        this.repo = repo;
        this.cache = cache;
        this.clientLookup = clientLookup;
        this.agreementResolver = agreementResolver;
        this.agreementDefaults = agreementDefaults;
    }

    // --------------------------------------------------- GET /pairing

    /**
     * Returns one row per active Trustworks client (CLIENT + PARTNER types),
     * indicating whether it is paired with an e-conomic customer for the given
     * Trustworks company.
     */
    public List<PairingRowDto> listPairingRows(UUID companyUuid) {
        String company = companyUuid.toString();
        List<Client> clients = clientLookup.listActive();
        EconomicsCustomerIndex idx = cache.getIndex(company);

        List<PairingRowDto> out = new ArrayList<>(clients.size());
        for (Client c : clients) {
            Optional<ClientEconomicsCustomer> paired = repo.findByClientAndCompany(c.getUuid(), company);
            if (paired.isPresent()) {
                out.add(pairedRow(c, paired.get()));
            } else {
                out.add(classifyUnpaired(c, idx));
            }
        }
        return out;
    }

    private PairingRowDto pairedRow(Client c, ClientEconomicsCustomer row) {
        return new PairingRowDto(
                c.getUuid(), c.getName(), c.getCvr(),
                c.getType() == null ? null : c.getType().name(),
                "PAIRED", row.getPairingSource(),
                row.getCustomerNumber(),
                List.of());
    }

    private PairingRowDto classifyUnpaired(Client c, EconomicsCustomerIndex idx) {
        // Priority 1: CVR match (single or ambiguous).
        if (c.getCvr() != null && !c.getCvr().isBlank()) {
            List<Integer> cvrHits = idx.allCvrMatches(c.getCvr());
            if (cvrHits.size() > 1) {
                return unmatchedRow(c, "AMBIGUOUS", candidatesFromIndex(cvrHits, idx, "CVR"));
            }
            if (cvrHits.size() == 1) {
                // Single CVR match — present as a candidate so the UI can auto-propose.
                return unmatchedRow(c, "UNMATCHED", candidatesFromIndex(cvrHits, idx, "CVR"));
            }
        }
        // Priority 2: name match.
        List<Integer> nameHits = idx.allNameMatches(c.getName());
        if (nameHits.size() > 1) {
            return unmatchedRow(c, "AMBIGUOUS", candidatesFromIndex(nameHits, idx, "NAME"));
        }
        if (nameHits.size() == 1) {
            return unmatchedRow(c, "UNMATCHED", candidatesFromIndex(nameHits, idx, "NAME"));
        }
        return unmatchedRow(c, "UNMATCHED", List.of());
    }

    private PairingRowDto unmatchedRow(Client c, String status, List<PairingCandidateDto> candidates) {
        return new PairingRowDto(
                c.getUuid(), c.getName(), c.getCvr(),
                c.getType() == null ? null : c.getType().name(),
                status, null, null, candidates);
    }

    // --------------------------------------------------- POST /pair/auto-run

    /**
     * Pairs every currently-unmatched client we can — first by CVR, then by
     * normalised name. Ambiguous matches are left UNMATCHED.
     */
    @Transactional
    public AutoRunResultDto autoRun(UUID companyUuid) {
        String company = companyUuid.toString();
        List<Client> clients = clientLookup.listActive();
        EconomicsCustomerIndex idx = cache.getIndex(company);

        int paired = 0, unchanged = 0, ambiguous = 0, unmatched = 0;
        List<String> errors = new ArrayList<>();

        for (Client c : clients) {
            if (repo.findByClientAndCompany(c.getUuid(), company).isPresent()) {
                unchanged++;
                continue;
            }
            try {
                AutoMatch match = tryMatch(c, idx);
                switch (match.outcome()) {
                    case PAIRED_CVR -> {
                        upsertPairing(c.getUuid(), company, match.customerNumber(), PairingSource.AUTO_CVR);
                        paired++;
                    }
                    case PAIRED_NAME -> {
                        upsertPairing(c.getUuid(), company, match.customerNumber(), PairingSource.AUTO_NAME);
                        paired++;
                    }
                    case AMBIGUOUS -> ambiguous++;
                    case UNMATCHED -> unmatched++;
                }
            } catch (Exception ex) {
                LOG.warnf(ex, "Auto-pair failed for client %s", c.getUuid());
                errors.add(c.getUuid());
            }
        }
        return new AutoRunResultDto(paired, unchanged, ambiguous, unmatched, errors);
    }

    private AutoMatch tryMatch(Client c, EconomicsCustomerIndex idx) {
        if (c.getCvr() != null && !c.getCvr().isBlank()) {
            Optional<Integer> cvrHit = idx.findByCvr(c.getCvr());
            if (cvrHit.isPresent()) return AutoMatch.paired(AutoMatchOutcome.PAIRED_CVR, cvrHit.get());
            if (idx.isAmbiguousCvr(c.getCvr())) return AutoMatch.ambiguous();
        }
        Optional<Integer> nameHit = idx.findByName(c.getName());
        if (nameHit.isPresent()) return AutoMatch.paired(AutoMatchOutcome.PAIRED_NAME, nameHit.get());
        if (idx.isAmbiguousName(c.getName())) return AutoMatch.ambiguous();
        return AutoMatch.unmatched();
    }

    private enum AutoMatchOutcome { PAIRED_CVR, PAIRED_NAME, AMBIGUOUS, UNMATCHED }

    private record AutoMatch(AutoMatchOutcome outcome, Integer customerNumber) {
        static AutoMatch paired(AutoMatchOutcome o, Integer n) { return new AutoMatch(o, n); }
        static AutoMatch ambiguous()                           { return new AutoMatch(AutoMatchOutcome.AMBIGUOUS, null); }
        static AutoMatch unmatched()                           { return new AutoMatch(AutoMatchOutcome.UNMATCHED, null); }
    }

    // --------------------------------------------------- POST /pair

    @Transactional
    public void pairManually(PairingRequestDto request) {
        Objects.requireNonNull(request, "request must not be null");
        upsertPairing(request.getClientUuid(), request.getCompanyUuid(),
                request.getEconomicsCustomerNumber(), request.getPairingSource());
    }

    // --------------------------------------------------- DELETE /pair

    @Transactional
    public void unpair(UUID clientUuid, UUID companyUuid) {
        String client = clientUuid.toString();
        String company = companyUuid.toString();
        repo.findByClientAndCompany(client, company).ifPresent(repo::delete);
    }

    // --------------------------------------------------- GET /search

    public List<PairingCandidateDto> searchEconomicsCustomers(UUID companyUuid, String query) {
        if (query == null || query.isBlank()) return List.of();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        EconomicsCustomerIndex idx = cache.getIndex(companyUuid.toString());

        // Substring name match plus exact CVR match (index exposes the raw lists).
        List<PairingCandidateDto> matches = new ArrayList<>();
        for (EconomicsCustomerDto dto : idx.allCustomers()) {
            String name = dto.getName() == null ? "" : dto.getName().toLowerCase(Locale.ROOT);
            String cvr  = dto.getCvrNo() == null ? "" : dto.getCvrNo();
            if (name.contains(needle)) {
                matches.add(candidate(dto, "NAME"));
            } else if (cvr.equals(needle)) {
                matches.add(candidate(dto, "CVR"));
            }
            if (matches.size() >= SEARCH_LIMIT) break;
        }
        return matches;
    }

    // --------------------------------------------------- POST /pair/.../create

    /**
     * Creates a new e-conomic customer from the Trustworks client, grants it
     * "access" (Phase G0 §6.8 finding — customers default to access=false), and
     * stores the pairing row. The agreement's index cache is invalidated so
     * the new customer shows up on the next pairing load.
     */
    @Transactional
    public PairingRowDto createAndPair(UUID clientUuid, UUID companyUuid) {
        String client = clientUuid.toString();
        String company = companyUuid.toString();
        Client c = clientLookup.findByUuid(client).orElseThrow(
                () -> new IllegalArgumentException("Unknown client: " + client));

        EconomicsCustomerApiClient api = agreementResolver.apiFor(company);
        EconomicsCustomerIndex idx = cache.getIndex(company);

        AgreementDefaults defaults = agreementDefaults.requireFor(company);
        EconomicsCustomerDto body = toCreateBody(c, idx, defaults);
        EconomicsCustomerDto created = api.createCustomer(body);
        int customerNumber = Objects.requireNonNull(created.getCustomerNumber(),
                "e-conomic returned no customerNumber for client " + client);

        // Phase G0 §6.8: newly-created customers need PUT with access=true to appear.
        EconomicsCustomerDto accessPatch = new EconomicsCustomerDto();
        accessPatch.setCustomerNumber(customerNumber);
        accessPatch.setName(created.getName());
        accessPatch.setCvrNo(created.getCvrNo());
        accessPatch.setCustomerGroupNumber(created.getCustomerGroupNumber());
        accessPatch.setZone(created.getZone());
        accessPatch.setCurrency(created.getCurrency());
        accessPatch.setPaymentTermId(created.getPaymentTermId());
        accessPatch.setAccess(Boolean.TRUE);
        accessPatch.setObjectVersion(created.getObjectVersion());
        api.updateCustomer(customerNumber, accessPatch);

        upsertPairing(client, company, customerNumber, PairingSource.CREATED);
        cache.invalidate(company);

        return new PairingRowDto(
                c.getUuid(), c.getName(), c.getCvr(),
                c.getType() == null ? null : c.getType().name(),
                "PAIRED", PairingSource.CREATED, customerNumber, List.of());
    }

    // --------------------------------------------------- helpers

    private void upsertPairing(String clientUuid, String companyUuid, int customerNumber, PairingSource source) {
        Optional<ClientEconomicsCustomer> existing = repo.findByClientAndCompany(clientUuid, companyUuid);
        ClientEconomicsCustomer row = existing.orElseGet(() -> {
            ClientEconomicsCustomer r = new ClientEconomicsCustomer();
            r.setUuid(UUID.randomUUID().toString());
            r.setClientUuid(clientUuid);
            r.setCompanyUuid(companyUuid);
            return r;
        });
        row.setCustomerNumber(customerNumber);
        row.setPairingSource(source);
        row.setSyncedAt(LocalDateTime.now());
        repo.persist(row);
    }

    private List<PairingCandidateDto> candidatesFromIndex(List<Integer> customerNumbers,
                                                         EconomicsCustomerIndex idx,
                                                         String reason) {
        List<PairingCandidateDto> out = new ArrayList<>(customerNumbers.size());
        for (Integer n : customerNumbers) {
            idx.getByCustomerNumber(n).ifPresent(dto -> out.add(candidate(dto, reason)));
        }
        return out;
    }

    private static PairingCandidateDto candidate(EconomicsCustomerDto dto, String reason) {
        return new PairingCandidateDto(
                dto.getCustomerNumber() == null ? 0 : dto.getCustomerNumber(),
                dto.getName(),
                dto.getCvrNo(),
                reason);
    }

    private static EconomicsCustomerDto toCreateBody(Client c,
                                                     EconomicsCustomerIndex idx,
                                                     AgreementDefaults defaults) {
        EconomicsCustomerDto body = new EconomicsCustomerDto();
        // Phase G0 §6.8 finding: use CVR as customerNumber where available so
        // POST /Customers can reuse the natural key rather than rely on
        // e-conomic's auto-assign (which it doesn't actually do — missing
        // customerNumber returns 400). Non-numeric / missing CVR → next
        // available number from the pre-warmed index (max + 1).
        Integer customerNumber = null;
        if (c.getCvr() != null && !c.getCvr().isBlank()) {
            try {
                customerNumber = Integer.parseInt(c.getCvr().trim());
            } catch (NumberFormatException ignore) {
                // fall through to index-max fallback
            }
        }
        if (customerNumber == null) {
            customerNumber = nextCustomerNumber(idx);
        }
        body.setCustomerNumber(customerNumber);
        body.setName(c.getName());
        body.setCvrNo(c.getCvr());

        // Required by POST /Customers: customerGroupNumber. Map from Trustworks
        // client type to the configured e-conomic group per agreement.
        body.setCustomerGroupNumber(defaults.groupNumberFor(c.getType()));
        body.setZone(defaults.vatZoneNumber());
        body.setCurrency(defaults.currency());
        body.setPaymentTermId(defaults.paymentTermId());
        return body;
    }

    private static int nextCustomerNumber(EconomicsCustomerIndex idx) {
        int max = idx.allCustomers().stream()
                .map(EconomicsCustomerDto::getCustomerNumber)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(1000);
        return max + 1;
    }

}
