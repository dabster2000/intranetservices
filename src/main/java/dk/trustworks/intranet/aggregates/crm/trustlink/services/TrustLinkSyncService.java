package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import dk.trustworks.intranet.aggregates.crm.trustlink.client.TrustLinkClient;
import dk.trustworks.intranet.aggregates.crm.trustlink.client.TrustLinkConfig;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkConnectionDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSearchResponse;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSyncStateDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSyncSummary;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkTrustworkerDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.TrustLinkConnection;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.TrustLinkConnectionTrustworker;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.TrustLinkSyncState;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.TrustLinkTrustworkerMap;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.MatchMethod;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirrors TrustLink's tier-5 connections into the account relationship graph (CRM spec
 * §3.5, §3.7) — the third edge source next to the calendar (MET) and signals (KNOWS).
 *
 * <h2>Never an HTTP call inside a transaction</h2>
 * A run makes hundreds of round trips: a typeahead per unmapped client on the first run,
 * the trustworker directory, and a paged search per batch of company names. A transaction
 * held across any of them pins a pooled connection for the length of the network, which is
 * the rule {@code AccountCalendarSyncService} follows for Graph and the reason it reads
 * outside a transaction and writes inside a short one. Everything here does the same, one
 * transaction per page of results.
 *
 * <h2>The response is not trusted</h2>
 * TrustLink ignores unknown request fields <b>silently</b>. A misspelled filter does not
 * error; it comes back looking like "no filter", and ingesting that would attach all 47,436
 * people in their database to arbitrary accounts and make every "who knows them" answer on
 * the site a lie that nobody could spot by reading the page. So every page is checked: each
 * {@code companyName} must be one this batch asked for and each {@code tier} must be 5. A
 * page that fails is a FAILURE, not data — the batch is abandoned, counted, and yesterday's
 * rows are left exactly as they were.
 *
 * <h2>Failure isolation, and the one failure that stops the run</h2>
 * A batch TrustLink refuses is counted and logged by CODE — never a message and never a
 * body, which can echo the request back and with it our client list — and the loop
 * continues. The exception is the trustworker directory: it is the only source of a
 * colleague's e-mail, and the e-mail rung resolves 38 of the 61 names, so continuing
 * without it would rewrite good attributions as "unmatched". The run aborts before
 * ingesting anything rather than degrade a graph that was correct this morning.
 *
 * <h2>Upsert, never delete</h2>
 * Rows only accumulate (decision 4). {@code first_seen_at} is set on insert,
 * {@code last_seen_at} on every sighting, and nothing is removed — a person who vanishes
 * from TrustLink goes stale rather than silently disappearing off an account page. Ids are
 * derived from the natural keys, so a re-run updates instead of duplicating and no lookup
 * query is needed to find out which.
 *
 * <h2>The run timestamp is taken once</h2>
 * One {@code now} at the top of a pass flows into every row. Rows written by
 * the same run share a timestamp, which is what makes "seen in the last run" a query anyone
 * can write, and what keeps a test of this class repeatable.
 */
@JBossLog
@ApplicationScoped
public class TrustLinkSyncService {

    /**
     * A first run over ~300 clients needs one typeahead call each. This is a runaway guard,
     * not a budget: a steady-state run makes ZERO, because every client already has an alias
     * row and the seeder skips it without asking anybody anything.
     */
    static final int MAX_TYPEAHEAD_CALLS_PER_RUN = 500;

    /** Paging guard. The whole tier-5 corpus is ~1,600 items; 200 pages is far past any real answer. */
    static final int MAX_PAGES_PER_BATCH = 200;

    @Inject
    TrustLinkClient trustLinkClient;

    @Inject
    TrustLinkConfig config;

    @Inject
    TrustLinkCompanyAliasService aliasService;

    /**
     * Set for the duration of a pass, so a second caller can be turned away instead of
     * racing the first one onto the same deterministic primary keys. Per-JVM only; see
     * {@link #syncNow()} for what that does and does not buy.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * The nightly entry point. A run already in flight is not an error here: the scheduler's
     * own {@code SKIP} covers two nightly runs overlapping, and this covers the case it
     * cannot see — somebody pressing {@code POST /trustlink/sync} at 02:41. The second
     * caller is told nothing happened and the first is left alone.
     */
    public TrustLinkSyncSummary syncAll() {
        if (!running.compareAndSet(false, true)) {
            log.warn("TrustLink sync skipped: a run is already in progress in this instance");
            return TrustLinkSyncSummary.skipped();
        }
        try {
            return runOnce();
        } finally {
            running.set(false);
        }
    }

    /**
     * The manual trigger's entry point, which differs from {@link #syncAll()} in one way
     * only: contention is an answer, not a silence.
     *
     * <p>An operator presses Sync because they have just fixed a mapping and want to see it
     * pull connections in. Handing them a summary of zeros while the nightly run is midway
     * through the same upserts would read as "your mapping found nothing" — the exact
     * wrong conclusion, and one they would act on by editing the mapping again. 409 says
     * what actually happened.
     *
     * <p><b>The guard is per-JVM.</b> {@code @Scheduled} fires on every running instance, so
     * two ECS tasks still run the nightly pass concurrently; this is the same condition
     * every scheduled job in this codebase lives with. What it does buy is the overlap that
     * actually happens in practice — a person triggering a run on the instance that is
     * already doing one — and without it the two races on {@link TrustLinkConnection}'s
     * deterministic primary key, where the loser abandons a whole batch for the night and
     * logs it only as {@code code=UNEXPECTED}.
     */
    public TrustLinkSyncSummary syncNow() {
        if (!running.compareAndSet(false, true)) {
            throw new WebApplicationException(
                    "A TrustLink sync is already running — try again when it has finished",
                    Response.Status.CONFLICT);
        }
        try {
            return runOnce();
        } finally {
            running.set(false);
        }
    }

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /**
     * What the last run did, for {@code GET /trustlink/sync-state}.
     *
     * <p>The bookkeeping row was write-only until this existed: the job wrote it nightly at
     * 02:40 and the only reader was a SQL client against a production database that is
     * read-only by default. "Did last night work?" is the question this feed's operator asks
     * every morning, and a stale relationship graph looks exactly like a quiet one, so it
     * could not be answered by looking at the account page either.
     *
     * <p>The feature flag is read live from the configuration rather than taken from the row,
     * because it is the thing that makes the row legible: zeros under {@code enabled = false}
     * are an environment that was never switched on, and zeros under {@code enabled = true}
     * are a job that is getting nowhere. Nothing in the row can tell those apart.
     *
     * <p>No transaction: one primary-key read, exactly like the alias reads next door.
     */
    public TrustLinkSyncStateDTO syncState() {
        TrustLinkSyncState state = TrustLinkSyncState.findById(TrustLinkSyncState.SINGLETON_ID);
        if (state == null) {
            // "No row" is a state, not a missing resource — nothing has run yet, which is what
            // a fresh environment looks like. See TrustLinkSyncStateDTO.neverRun for why this
            // is not a 404.
            return TrustLinkSyncStateDTO.neverRun(config.enabled());
        }
        return new TrustLinkSyncStateDTO(
                config.enabled(),
                state.getLastRunAt(),
                state.getLastSuccessAt(),
                state.getCompaniesQueried(),
                state.getConnectionsUpserted(),
                state.getEdgesUpserted(),
                state.getUnmatchedTrustworkers(),
                TrustLinkSyncState.decodeNames(state.getUnmatchedTrustworkerNames()),
                state.getFailureCode());
    }

    /**
     * One full pass: seed the aliases that are missing, resolve the trustworker names, then
     * query every enabled company name and write back what comes of it.
     */
    private TrustLinkSyncSummary runOnce() {
        if (!config.enabled()) {
            log.info("TrustLink sync is switched off (dk.trustworks.crm.trustlink.enabled=false)");
            return TrustLinkSyncSummary.disabled();
        }

        LocalDateTime now = LocalDateTime.now();
        List<ClientRef> clients = QuarkusTransaction.requiringNew().call(TrustLinkSyncService::loadClients);
        Set<String> alreadyMapped = QuarkusTransaction.requiringNew().call(aliasService::clientUuidsWithAnyAlias);

        SeedResult seeding = seedAliases(clients, alreadyMapped, now);

        Map<String, List<String>> companyIndex =
                QuarkusTransaction.requiringNew().call(aliasService::enabledCompanyIndex);
        if (companyIndex.isEmpty()) {
            log.info("TrustLink sync: no company aliases are enabled, nothing can be attributed");
            return finish(now, TrustLinkSyncSummary.ran(
                    clients.size(), seeding.seeded(), 0, 0, 0, 0, List.of(), 0), null);
        }

        TrustworkerIndex trustworkers;
        try {
            trustworkers = resolveTrustworkers();
        } catch (RuntimeException e) {
            String code = failureCodeOf(e);
            log.warnf("TrustLink sync aborted: the trustworker directory could not be read (code=%s)", code);
            return finish(now, TrustLinkSyncSummary.ran(
                    clients.size(), seeding.seeded(), 0, 0, 0, 0, List.of(), 1), code);
        }

        List<String> companies = List.copyOf(companyIndex.keySet());
        Counters counters = new Counters();
        String lastFailureCode = null;
        int batchSize = config.companyBatchSize();

        for (int start = 0; start < companies.size(); start += batchSize) {
            List<String> batch = companies.subList(start, Math.min(start + batchSize, companies.size()));
            try {
                ingestBatch(batch, companyIndex, trustworkers, now, counters);
            } catch (RuntimeException e) {
                counters.failures++;
                lastFailureCode = failureCodeOf(e);
                // The code and the size of the batch. Never the message, never the names:
                // an upstream error body can echo the request back into the log.
                log.warnf("TrustLink sync: batch of %d company names failed (code=%s)",
                        batch.size(), lastFailureCode);
            }
        }

        return finish(now, TrustLinkSyncSummary.ran(
                clients.size(),
                seeding.seeded(),
                companies.size(),
                counters.connections,
                counters.edges,
                trustworkers.unmatchedCount(),
                trustworkers.unmatchedNames(),
                counters.failures), lastFailureCode);
    }

    /** Writes the bookkeeping row, logs the one closing line, and hands the summary back. */
    private TrustLinkSyncSummary finish(LocalDateTime now, TrustLinkSyncSummary summary, String failureCode) {
        QuarkusTransaction.requiringNew().run(() -> writeState(now, summary, failureCode));
        // The status leads, because every counter behind it is zero under two quite
        // different circumstances and only the status says which one this was.
        log.infof("TrustLink sync done: status=%s clients=%d aliasesSeeded=%d companies=%d connections=%d edges=%d "
                        + "unmatchedTrustworkers=%d failures=%d",
                summary.status(), summary.clients(), summary.aliasesSeeded(), summary.companiesQueried(),
                summary.connectionsUpserted(), summary.edgesUpserted(),
                summary.unmatchedTrustworkers(), summary.failures());
        return summary;
    }

    // ------------------------------------------------------------------------
    // Step 1-2: aliases
    // ------------------------------------------------------------------------

    /**
     * Gives every unmapped client something to query with.
     *
     * <p>The client's own name is always seeded, so the 117 clients TrustLink spells
     * exactly as we do cost no typeahead call ever. On top of that the typeahead is asked
     * once, and only candidates {@link TrustLinkCompanyMatcher#selectMatches} accepts are
     * kept — the strict comparison, which folds legal form but not geography, because a
     * wrong AUTO alias silently moves strangers onto an account and nothing on the page
     * would say so.
     *
     * <p>A client with ANY existing alias row is skipped without a call. That is what makes
     * the nightly run cheap: after the first one this loop makes no HTTP requests at all. A
     * client whose typeahead call failed is skipped entirely rather than seeded with half an
     * answer, so tomorrow's run tries the whole thing again.
     */
    SeedResult seedAliases(List<ClientRef> clients, Set<String> alreadyMapped, LocalDateTime now) {
        int seeded = 0;
        int typeaheadCalls = 0;
        int seedFailures = 0;
        for (ClientRef client : clients) {
            if (alreadyMapped.contains(client.uuid()) || client.name() == null || client.name().isBlank()) {
                continue;
            }
            String name = client.name().trim();
            LinkedHashSet<String> names = new LinkedHashSet<>();

            if (typeaheadCalls < MAX_TYPEAHEAD_CALLS_PER_RUN) {
                typeaheadCalls++;
                try {
                    List<String> candidates = trustLinkClient.searchCompanies(
                            name, TrustLinkCompanyAliasService.TYPEAHEAD_LIMIT);
                    names.addAll(TrustLinkCompanyMatcher.selectMatches(name, candidates));
                } catch (RuntimeException e) {
                    log.warnf("TrustLink alias seeding skipped for client %s (code=%s)",
                            client.uuid(), failureCodeOf(e));
                    continue;
                }
            }

            // TrustLink's own spelling goes in FIRST, and our client name is only added when
            // TrustLink does not already know the company under a spelling that differs by
            // case alone. Both halves matter:
            //
            //   * Upstream search is case-sensitive, so only TrustLink's spelling returns
            //     anybody. Intra says "Styrelsen for IT og Læring", TrustLink says
            //     "Styrelsen for It og Læring"; querying ours finds nothing.
            //   * company_name is utf8mb4_general_ci, so the two are ONE row to the unique
            //     key. Seeding both raised Duplicate entry and rolled the seeding back.
            //
            // Seeding our own name is a guess that costs no HTTP call; a typeahead hit is a
            // name TrustLink has confirmed it holds. When they collide, the confirmed one wins.
            boolean knownUnderAnotherCasing = names.stream()
                    .anyMatch(candidate -> TrustLinkCompanyAliasService.foldForUniqueKey(candidate)
                            .equals(TrustLinkCompanyAliasService.foldForUniqueKey(name)));
            if (!knownUnderAnotherCasing) {
                names.add(name);
            }

            // One client's seeding failing must not cost the other 293 theirs. Before this
            // was isolated, a single Duplicate entry propagated out of the loop and failed
            // the entire run — the job would have ingested nothing, every night, silently.
            try {
                seeded += QuarkusTransaction.requiringNew()
                        .call(() -> aliasService.seedAuto(client.uuid(), names, now));
            } catch (RuntimeException e) {
                seedFailures++;
                log.warnf("TrustLink alias seeding failed for client %s (%s) — continuing",
                        client.uuid(), e.getClass().getSimpleName());
            }
        }
        if (typeaheadCalls > 0 || seedFailures > 0) {
            log.infof("TrustLink alias seeding: %d clients had no aliases, %d company names added, %d clients failed",
                    typeaheadCalls, seeded, seedFailures);
        }
        return new SeedResult(seeded, typeaheadCalls);
    }

    // ------------------------------------------------------------------------
    // Step 3: trustworker names -> users
    // ------------------------------------------------------------------------

    /**
     * Reads the trustworker directory (no transaction) and resolves every name against the
     * Intra users with the four-rung ladder, the manual override table beating all four.
     *
     * <p>The directory is worth a call of its own because it is the only place a
     * trustworker's e-mail exists, and the e-mail rung resolves 38 of the 61 names outright.
     * Names that turn up on a connection but not in the directory are resolved on demand,
     * with no e-mail to go on.
     */
    TrustworkerIndex resolveTrustworkers() {
        List<TrustLinkTrustworkerDTO> directory = trustLinkClient.trustworkers();
        List<TrustLinkTrustworkerMatcher.UserRef> users =
                QuarkusTransaction.requiringNew().call(TrustLinkSyncService::loadUsers);
        Map<String, String> overrides =
                QuarkusTransaction.requiringNew().call(TrustLinkSyncService::loadOverrides);

        Map<String, String> emailByName = new HashMap<>();
        for (TrustLinkTrustworkerDTO trustworker : directory) {
            if (trustworker != null && trustworker.name() != null && !trustworker.name().isBlank()) {
                emailByName.put(trustworker.name(), trustworker.email());
            }
        }

        TrustworkerIndex index = new TrustworkerIndex(users, overrides, emailByName);
        // Resolved up front so the closing log line can report how much of the directory
        // the ladder actually reaches. A falling number is the signal that a rung has
        // stopped working — invisible otherwise, because unmatched edges still render.
        for (String name : emailByName.keySet()) {
            index.match(name);
        }

        // The NAMES, not only the count. "unmatched_trustworkers = 7" is an alarm nobody can
        // act on: the single remedy is a trustlink_trustworker_map row keyed BY the name, so
        // without this line the number can be watched and never resolved.
        //
        // DO NOT REMOVE THIS AS A PII LEAK. These are TRUSTWORKS EMPLOYEES out of our own
        // staff directory — colleagues, whose names sit in `user` and on every timesheet.
        // They are categorically not the third-party people at client organisations that the
        // rest of this feature deliberately never logs, and that distinction is the whole
        // reason this one list is safe.
        List<String> unmatched = index.unmatchedNames();
        if (!unmatched.isEmpty()) {
            List<String> reported = unmatched.stream()
                    .limit(TrustLinkSyncSummary.MAX_REPORTED_UNMATCHED_NAMES)
                    .toList();
            // A name somebody deliberately mapped to nobody stays on this list: the ladder
            // did not place it, which is what the number counts, and the two must not
            // disagree. Its remedy is already written, so the line says "fix one", not
            // "these are all broken".
            log.infof("TrustLink trustworkers: %d of %d directory names resolved to no Intra user"
                            + " — a trustlink_trustworker_map row keyed by the name fixes one: %s",
                    unmatched.size(), index.directorySize(), String.join(", ", reported));
        }
        return index;
    }

    /**
     * The resolution of one trustworker name, memoised for the run.
     *
     * <p>Memoisation is not only about speed: the same name must resolve the same way on
     * every one of the hundreds of connections it appears on, or the graph would contradict
     * itself inside a single run.
     */
    static final class TrustworkerIndex {
        private final List<TrustLinkTrustworkerMatcher.UserRef> users;
        private final Map<String, String> overrides;
        private final Map<String, String> emailByName;
        private final Map<String, TrustLinkTrustworkerMatcher.Match> resolved = new HashMap<>();

        TrustworkerIndex(List<TrustLinkTrustworkerMatcher.UserRef> users,
                         Map<String, String> overrides,
                         Map<String, String> emailByName) {
            this.users = users;
            this.overrides = overrides;
            this.emailByName = emailByName;
        }

        TrustLinkTrustworkerMatcher.Match match(String trustworkerName) {
            return resolved.computeIfAbsent(trustworkerName, name ->
                    TrustLinkTrustworkerMatcher.match(name, emailByName.get(name), users, overrides));
        }

        int directorySize() {
            return emailByName.size();
        }

        /** Of the directory, how many the ladder placed. Names outside it are not counted. */
        int matchedCount() {
            return directorySize() - unmatchedCount();
        }

        /**
         * WHICH directory names the ladder could not place, sorted so two runs that found the
         * same problem produce the same line.
         *
         * <p>This, and not the count, is the actionable half: an unmatched name has exactly one
         * fix — a {@code trustlink_trustworker_map} row keyed by that name — and a number cannot
         * be keyed on. Sorting matters because these end up in a log line and in a stored row
         * that somebody compares against last night's.
         */
        List<String> unmatchedNames() {
            return emailByName.keySet().stream()
                    .filter(name -> !match(name).isMatched())
                    .sorted()
                    .toList();
        }

        /** Derived from the list, so the number and the names can never disagree. */
        int unmatchedCount() {
            return unmatchedNames().size();
        }
    }

    // ------------------------------------------------------------------------
    // Step 4-6: query, fan out, upsert
    // ------------------------------------------------------------------------

    /**
     * One batch of company names, paged. Each page is fetched with no transaction open and
     * written in one of its own, so a slow upstream costs latency and never a held
     * connection.
     */
    void ingestBatch(List<String> batch,
                     Map<String, List<String>> companyIndex,
                     TrustworkerIndex trustworkers,
                     LocalDateTime now,
                     Counters counters) {
        Set<String> asked = new HashSet<>(batch);
        int page = 1;
        boolean more = true;
        while (more && page <= MAX_PAGES_PER_BATCH) {
            TrustLinkSearchResponse response = trustLinkClient.search(batch, page, config.pageSize());
            requirePlausible(response, asked);

            List<PendingConnection> pending = new ArrayList<>();
            for (TrustLinkConnectionDTO item : response.items()) {
                // A TrustLink company can be claimed by more than one client; each of them
                // gets its own row, or the second client silently loses the connection.
                for (String clientUuid : companyIndex.getOrDefault(item.companyName(), List.of())) {
                    pending.add(toPending(clientUuid, item, trustworkers));
                }
            }
            if (!pending.isEmpty()) {
                QuarkusTransaction.requiringNew().run(() -> persist(pending, now, counters));
            }
            more = response.hasMorePages();
            page++;
        }
    }

    /**
     * The check that stands between a silently-ignored request field and 47,436 strangers in
     * the database. A page that answers about a company nobody asked about, or about a tier
     * we did not request, is not a partial answer to be salvaged — it means the filter did
     * not apply at all, so the whole page is rejected.
     */
    static void requirePlausible(TrustLinkSearchResponse response, Set<String> asked) {
        if (response == null) {
            throw new BatchRejected();
        }
        for (TrustLinkConnectionDTO item : response.items()) {
            if (item == null || item.personId() == null || item.personId().isBlank()) {
                throw new BatchRejected();
            }
            if (item.companyName() == null || !asked.contains(item.companyName())) {
                throw new BatchRejected();
            }
            if (item.tier() != TrustLinkClient.TIER_5) {
                throw new BatchRejected();
            }
        }
    }

    /** One returned person, for one client, with its edges already resolved to users. */
    PendingConnection toPending(String clientUuid, TrustLinkConnectionDTO item, TrustworkerIndex trustworkers) {
        String connectionUuid = TrustLinkConnection.deterministicUuid(clientUuid, item.personId());
        List<PendingEdge> edges = new ArrayList<>();
        for (TrustLinkConnectionDTO.ConnectedTrustworker connected : item.connectedTrustworkers()) {
            if (connected == null || connected.name() == null || connected.name().isBlank()) {
                continue;
            }
            String name = connected.name();
            TrustLinkTrustworkerMatcher.Match match = trustworkers.match(name);
            // An unmatched name is kept, not dropped: the colleague is real and so is the
            // relationship, and losing "Marie Dorthea · 242 tier-5 connections" because a
            // name did not resolve would discard exactly what this feature is for.
            edges.add(new PendingEdge(
                    TrustLinkConnection.deterministicUuid(connectionUuid, name),
                    connectionUuid,
                    name,
                    match.userUuid(),
                    // The method is recorded even on a miss: MANUAL with no uuid is a
                    // person saying "this name belongs to nobody here", which must not
                    // read the same as the ladder quietly running out.
                    match.method(),
                    connected.connectedOnDate()));
        }
        return new PendingConnection(connectionUuid, clientUuid, item, edges);
    }

    /** Upsert. The derived ids turn "insert" into "find and update"; nothing is ever deleted. */
    void persist(List<PendingConnection> pending, LocalDateTime now, Counters counters) {
        for (PendingConnection item : pending) {
            TrustLinkConnection connection = TrustLinkConnection.findById(item.uuid());
            if (connection == null) {
                connection = new TrustLinkConnection();
                connection.setUuid(item.uuid());
                connection.setClientUuid(item.clientUuid());
                connection.setPersonId(item.source().personId());
                connection.setFirstSeenAt(now);
            }
            connection.setFullName(item.source().fullName());
            connection.setPosition(item.source().position());
            connection.setTier(item.source().tier());
            connection.setCompanyName(item.source().companyName());
            connection.setCompanyId(item.source().companyId());
            connection.setLinkedinUrl(item.source().linkedInUrl());
            connection.setCustomer(item.source().isCustomer());
            connection.setCoffeeCount(item.source().coffeeCount());
            connection.setCommentCount(item.source().commentCount());
            connection.setLastSeenAt(now);
            connection.persist();
            counters.connections++;

            for (PendingEdge edge : item.edges()) {
                TrustLinkConnectionTrustworker row = TrustLinkConnectionTrustworker.findById(edge.uuid());
                if (row == null) {
                    row = new TrustLinkConnectionTrustworker();
                    row.setUuid(edge.uuid());
                    row.setConnectionUuid(edge.connectionUuid());
                    row.setTrustworkerName(edge.trustworkerName());
                    row.setFirstSeenAt(now);
                }
                // The resolution is rewritten every run on purpose: it is deterministic
                // given the same users and overrides, and the run has already aborted
                // rather than ingest without the directory that rung 1 needs.
                row.setUserUuid(edge.userUuid());
                row.setMatchMethod(edge.matchMethod());
                row.setConnectedOn(edge.connectedOn());
                row.setLastSeenAt(now);
                row.persist();
                counters.edges++;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Step 7: bookkeeping
    // ------------------------------------------------------------------------

    /**
     * The single {@code trustlink_sync_state} row. {@code lastSuccessAt} moves only when
     * every batch came back clean — the widening gap between it and {@code lastRunAt} is how
     * anyone notices a feed that fires nightly and gets nowhere.
     */
    void writeState(LocalDateTime now, TrustLinkSyncSummary summary, String failureCode) {
        TrustLinkSyncState state = TrustLinkSyncState.findById(TrustLinkSyncState.SINGLETON_ID);
        if (state == null) {
            state = new TrustLinkSyncState();
            state.setId(TrustLinkSyncState.SINGLETON_ID);
        }
        state.setLastRunAt(now);
        if (summary.failures() == 0) {
            state.setLastSuccessAt(now);
        }
        state.setCompaniesQueried(summary.companiesQueried());
        state.setConnectionsUpserted(summary.connectionsUpserted());
        state.setEdgesUpserted(summary.edgesUpserted());
        state.setUnmatchedTrustworkers(summary.unmatchedTrustworkers());
        // The names as well as the count: the count alone is an alarm with no remedy
        // attached, and this row is what GET /trustlink/sync-state serves in the morning.
        state.setUnmatchedTrustworkerNames(
                TrustLinkSyncState.encodeNames(summary.unmatchedTrustworkerNames()));
        state.setFailureCode(failureCode);
        state.persist();
    }

    // ------------------------------------------------------------------------
    // Loads
    // ------------------------------------------------------------------------

    static List<ClientRef> loadClients() {
        List<ClientRef> clients = new ArrayList<>();
        for (Client client : Client.<Client>listAll()) {
            clients.add(new ClientRef(client.getUuid(), client.getName()));
        }
        return clients;
    }

    static List<TrustLinkTrustworkerMatcher.UserRef> loadUsers() {
        List<TrustLinkTrustworkerMatcher.UserRef> users = new ArrayList<>();
        for (User user : User.<User>listAll()) {
            users.add(new TrustLinkTrustworkerMatcher.UserRef(user.uuid, user.getFullname(), user.getEmail()));
        }
        return users;
    }

    /**
     * The manual overrides, keyed by NORMALISED name so a difference in spacing or a Danish
     * letter cannot make an override silently inert. A null value is kept in the map on
     * purpose: it means "deliberately unmapped", which is a decision the matcher has to see.
     */
    static Map<String, String> loadOverrides() {
        Map<String, String> overrides = new HashMap<>();
        for (TrustLinkTrustworkerMap row : TrustLinkTrustworkerMap.<TrustLinkTrustworkerMap>listAll()) {
            overrides.put(TrustLinkNameNormalizer.normalize(row.getTrustworkerName()), row.getUserUuid());
        }
        return overrides;
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /**
     * A page this run refuses to believe. Carries the code as its message and nothing else,
     * so even a careless log line cannot leak a response body.
     */
    static final class BatchRejected extends RuntimeException {
        BatchRejected() {
            super(TrustLinkClient.Failure.INVALID_RESPONSE.name());
        }
    }

    /**
     * The code to log. Both failure types carry a {@code Failure} name and nothing else;
     * anything else is unexpected and is reported as such rather than by printing whatever
     * it happened to contain.
     */
    static String failureCodeOf(RuntimeException e) {
        if (e instanceof TrustLinkClient.TrustLinkFailure failure) {
            return failure.code().name();
        }
        if (e instanceof BatchRejected) {
            return e.getMessage();
        }
        return "UNEXPECTED";
    }

    static final class Counters {
        int connections;
        int edges;
        int failures;
    }

    record SeedResult(int seeded, int typeaheadCalls) { }

    record ClientRef(String uuid, String name) { }

    record PendingEdge(String uuid,
                       String connectionUuid,
                       String trustworkerName,
                       String userUuid,
                       MatchMethod matchMethod,
                       LocalDate connectedOn) { }

    record PendingConnection(String uuid,
                             String clientUuid,
                             TrustLinkConnectionDTO source,
                             List<PendingEdge> edges) { }
}
