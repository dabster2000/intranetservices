package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import dk.trustworks.intranet.aggregates.crm.trustlink.client.TrustLinkClient;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkAliasDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.TrustLinkCompanyAlias;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.AliasSource;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which TrustLink company names a client is (CRM spec §3.5) — the join the whole feature
 * hangs off.
 *
 * <p>TrustLink has no idea what an Intra client uuid is. The only thing it can be asked
 * about is a company NAME, exactly spelled and case-sensitively, and it fragments a single
 * organisation across several of them: {@code Novo Nordisk} holds 203 tier-5 connections
 * while {@code Novo Nordisk A/S} holds 2, and the Intra client is called
 * {@code NOVO NORDISK A/S}. Everything in this class exists because that mapping is
 * many-to-many and cannot be computed reliably enough to be left implicit.
 *
 * <h2>The seeder guesses; a person decides</h2>
 * {@link #seedAuto} writes {@link AliasSource#AUTO} rows and <b>never touches a
 * {@link AliasSource#MANUAL} row in either direction</b> — it will not enable one, disable
 * one, or re-add a name a person has switched off. {@link #replaceManualAliases} is the
 * other side: what a person asserts becomes MANUAL, and a name they removed becomes a
 * disabled MANUAL row rather than a deletion, so tonight's seeder cannot quietly put it
 * back. Nothing here ever deletes a row — decision 4 applies to the mapping as much as to
 * the connections.
 *
 * <h2>Names are stored verbatim</h2>
 * The search is exact and case-sensitive ({@code "novo nordisk"} returns nothing), so a
 * stored name is TrustLink's spelling, untouched. {@link TrustLinkCompanyMatcher} exists
 * only to decide whether a candidate is worth adding; its normalised forms are never
 * stored and never sent upstream.
 */
@JBossLog
@ApplicationScoped
public class TrustLinkCompanyAliasService {

    /** A TrustLink company name is a VARCHAR(255); anything longer is not one. */
    public static final int MAX_COMPANY_NAME_CHARS = 255;

    /** Enough for the most fragmented organisation we have; a guard against a paste accident. */
    public static final int MAX_ALIASES_PER_CLIENT = 50;

    /** What the seeder asks the typeahead for. The editor passes its own, smaller, limit. */
    public static final int TYPEAHEAD_LIMIT = 10;

    @Inject
    TrustLinkClient trustLinkClient;

    @Inject
    ClientService clientService;

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /**
     * The client's names, enabled and disabled alike.
     *
     * <p>The disabled ones are returned on purpose: a suppressed name has to look
     * suppressed in the editor, or somebody reads it as "the seeder never found this" and
     * adds it back by hand — undoing the one durable way to say "not this company".
     */
    public List<TrustLinkAliasDTO> aliasesFor(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            throw new WebApplicationException("A client uuid is required", Response.Status.BAD_REQUEST);
        }
        List<TrustLinkCompanyAlias> rows =
                TrustLinkCompanyAlias.list("clientUuid = ?1 order by companyName", clientUuid.trim());
        return rows.stream().map(TrustLinkCompanyAliasService::toDTO).toList();
    }

    /**
     * Every enabled alias as {@code companyName → clientUuids}. The sync queries the keys
     * and fans each returned person back out over the values.
     *
     * <p>The value is a LIST and not a single uuid on purpose: two clients may both claim
     * the same TrustLink company, and each of them is entitled to the connection. Dropping
     * that to one uuid would give the company to whichever client happened to be read
     * first, silently and forever.
     */
    public Map<String, List<String>> enabledCompanyIndex() {
        return companyIndex(TrustLinkCompanyAlias.list("enabled", true));
    }

    /**
     * Pure index builder, kept apart from the query so it can be tested without a database.
     * Disabled rows are dropped here too, so a caller cannot query a suppressed name by
     * handing in the wrong list.
     */
    public static Map<String, List<String>> companyIndex(List<TrustLinkCompanyAlias> rows) {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (TrustLinkCompanyAlias row : rows) {
            if (row == null || !row.isEnabled() || row.getCompanyName() == null || row.getCompanyName().isBlank()) {
                continue;
            }
            List<String> clients = index.computeIfAbsent(row.getCompanyName(), key -> new ArrayList<>());
            if (!clients.contains(row.getClientUuid())) {
                clients.add(row.getClientUuid());
            }
        }
        return index;
    }

    /**
     * Clients that already have at least one alias row of ANY kind.
     *
     * <p>The seeder skips these, and that is what makes a steady-state run cost zero
     * typeahead calls. "Any kind" includes a disabled MANUAL row: a client whose only row
     * is a name somebody switched off has been decided about, and re-running the guess
     * every night would be an argument with them.
     */
    public Set<String> clientUuidsWithAnyAlias() {
        Set<String> uuids = new HashSet<>();
        for (TrustLinkCompanyAlias row : TrustLinkCompanyAlias.<TrustLinkCompanyAlias>listAll()) {
            uuids.add(row.getClientUuid());
        }
        return uuids;
    }

    /**
     * Company-name typeahead for the alias editor, proxied.
     *
     * <p>The term is re-checked here and not only in the resource: TrustLink answers a
     * parameter it does not understand by ignoring it and returning arbitrary companies, so
     * a blank term that slipped through would look like a working search full of plausible
     * suggestions — the worst possible failure for a control that writes aliases.
     */
    public List<String> searchCompanyNames(String term, int limit) {
        String cleaned = term == null ? "" : term.trim();
        if (cleaned.isEmpty()) {
            throw new WebApplicationException("A search term is required", Response.Status.BAD_REQUEST);
        }
        return trustLinkClient.searchCompanies(cleaned, Math.max(1, limit));
    }

    // ------------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------------

    /**
     * Makes the client's enabled names exactly the ones given, as that person's decision.
     *
     * <p>A name they kept that the seeder had found stays {@link AliasSource#AUTO} — nobody
     * asserted anything by leaving it alone, and the seeder may go on confirming it. A name
     * they added is MANUAL. A name they removed becomes a <b>disabled MANUAL row</b>, never
     * a deleted one: a deleted row would be rediscovered by tonight's seeder and quietly
     * undo them.
     */
    @Transactional
    public List<TrustLinkAliasDTO> replaceManualAliases(String clientUuid, List<String> companyNames, String actor) {
        Client client = requireClient(clientUuid);
        requireActor(actor);

        // Keyed by the folded form the unique key compares on; the value is the spelling
        // the person typed, which is what gets stored and sent upstream.
        LinkedHashMap<String, String> wanted = new LinkedHashMap<>();
        for (String raw : companyNames == null ? List.<String>of() : companyNames) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > MAX_COMPANY_NAME_CHARS) {
                throw new WebApplicationException(
                        "A TrustLink company name is at most " + MAX_COMPANY_NAME_CHARS + " characters",
                        Response.Status.BAD_REQUEST);
            }
            // Case-folded, for the same reason seedAuto folds: two spellings differing only
            // in case are ONE row to the unique key, and sending both would fail the write.
            // First spelling wins, so what the person typed is what gets stored.
            wanted.putIfAbsent(foldForUniqueKey(name), name);
        }
        if (wanted.size() > MAX_ALIASES_PER_CLIENT) {
            throw new WebApplicationException(
                    "At most " + MAX_ALIASES_PER_CLIENT + " TrustLink company names per client",
                    Response.Status.BAD_REQUEST);
        }

        LocalDateTime now = LocalDateTime.now();
        List<TrustLinkCompanyAlias> existing = TrustLinkCompanyAlias.list("clientUuid", client.getUuid());
        Map<String, TrustLinkCompanyAlias> byName = new LinkedHashMap<>();
        for (TrustLinkCompanyAlias row : existing) {
            byName.put(foldForUniqueKey(row.getCompanyName()), row);
        }

        for (String name : wanted.values()) {
            TrustLinkCompanyAlias row = byName.get(foldForUniqueKey(name));
            if (row == null) {
                row = new TrustLinkCompanyAlias();
                row.setUuid(UUID.randomUUID().toString());
                row.setClientUuid(client.getUuid());
                row.setCompanyName(name);
                row.setSource(AliasSource.MANUAL);
                row.setCreatedAt(now);
                row.setCreatedBy(actor);
            }
            row.setEnabled(true);
            row.persist();
        }

        for (TrustLinkCompanyAlias row : existing) {
            if (wanted.containsKey(foldForUniqueKey(row.getCompanyName()))) {
                continue;
            }
            // MANUAL and disabled: a tombstone the seeder is required to leave alone.
            row.setSource(AliasSource.MANUAL);
            row.setEnabled(false);
            row.setCreatedBy(actor);
            row.persist();
        }

        log.infof("TrustLink aliases replaced: client=%s enabled=%d actor=%s",
                client.getUuid(), wanted.size(), actor);
        return aliasesFor(client.getUuid());
    }

    /**
     * Adds AUTO rows for names the client does not have yet, and returns how many were
     * written.
     *
     * <p>No transaction annotation: the sync calls this inside a short
     * {@code QuarkusTransaction.requiringNew()} of its own, having made every HTTP call
     * outside it. An existing row of any source is left exactly as it is — this method can
     * only ever add, which is what "never touches a MANUAL row" means in code.
     */
    public int seedAuto(String clientUuid, Collection<String> companyNames, LocalDateTime now) {
        Set<String> present = new HashSet<>();
        for (TrustLinkCompanyAlias row : TrustLinkCompanyAlias.<TrustLinkCompanyAlias>list("clientUuid", clientUuid)) {
            present.add(foldForUniqueKey(row.getCompanyName()));
        }
        int inserted = 0;
        for (String name : companyNames) {
            String cleaned = name == null ? "" : name.trim();
            if (cleaned.isEmpty() || cleaned.length() > MAX_COMPANY_NAME_CHARS
                    || present.contains(foldForUniqueKey(cleaned))) {
                continue;
            }
            if (present.size() >= MAX_ALIASES_PER_CLIENT) {
                break;
            }
            present.add(foldForUniqueKey(cleaned));
            TrustLinkCompanyAlias row = new TrustLinkCompanyAlias();
            row.setUuid(UUID.randomUUID().toString());
            row.setClientUuid(clientUuid);
            row.setCompanyName(cleaned);
            row.setSource(AliasSource.AUTO);
            row.setEnabled(true);
            row.setCreatedAt(now);
            // Null: nobody asserted an AUTO row, and naming a person for it would invite
            // somebody to hold them to it.
            row.setCreatedBy(null);
            row.persist();
            inserted++;
        }
        return inserted;
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /**
     * The form {@code uq_trustlink_alias_client_company} actually compares on.
     *
     * <p>This exists because two true things about company names disagree, and the
     * disagreement crashed the very first real sync run:
     *
     * <ul>
     *   <li><b>TrustLink is case-SENSITIVE.</b> {@code "novo nordisk"} returns nothing where
     *       {@code "Novo Nordisk"} returns 203 people, so a stored name has to keep
     *       TrustLink's exact spelling or it matches nothing upstream.</li>
     *   <li><b>The column is case-INSENSITIVE.</b> {@code company_name} is
     *       {@code utf8mb4_general_ci}, so MariaDB considers two spellings that differ only
     *       in case to be one row.</li>
     * </ul>
     *
     * <p>Intra calls a client {@code Styrelsen for IT og Læring}; TrustLink calls the
     * company {@code Styrelsen for It og Læring}. Java's {@code equals} says those are two
     * names, the unique key says they are one, and seeding both raised
     * {@code Duplicate entry} — which rolled back the seeding transaction and failed the
     * whole run, for every client, not just this one.
     *
     * <p>So de-duplication in memory has to use the database's notion of equality, not
     * Java's. {@code Locale.ROOT} deliberately, so a Turkish default locale cannot turn
     * {@code I} into {@code ı} and reintroduce the collision it is here to prevent.
     *
     * <p>This is an approximation of {@code utf8mb4_general_ci}, not a reimplementation of
     * it: that collation also folds some accents, which this does not. It closes the case
     * collision, which is the one that actually occurs; {@link TrustLinkSyncService} isolates
     * each client's seeding in its own transaction so that any residual collation surprise
     * costs one client's aliases rather than the night's entire run.
     */
    static String foldForUniqueKey(String companyName) {
        return companyName == null ? "" : companyName.trim().toLowerCase(Locale.ROOT);
    }

    static TrustLinkAliasDTO toDTO(TrustLinkCompanyAlias row) {
        return new TrustLinkAliasDTO(
                row.getUuid(),
                row.getClientUuid(),
                row.getCompanyName(),
                row.getSource(),
                row.isEnabled(),
                row.getCreatedAt(),
                row.getCreatedBy());
    }

    private Client requireClient(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            throw new WebApplicationException("A client uuid is required", Response.Status.BAD_REQUEST);
        }
        Client client = clientService.findByUuid(clientUuid.trim());
        if (client == null) {
            throw new WebApplicationException("Unknown client", Response.Status.NOT_FOUND);
        }
        return client;
    }

    private void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — an alias change records who made it",
                    Response.Status.BAD_REQUEST);
        }
    }
}
