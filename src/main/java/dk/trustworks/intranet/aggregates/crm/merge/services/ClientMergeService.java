package dk.trustworks.intranet.aggregates.crm.merge.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry.Collapse;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry.Ref;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry.Unique;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRules;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRules.AccountFrom;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO.AccountChoice;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO.AccountSide;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO.ClientRef;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO.EconomicsCustomerRef;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO.MonthControlDecision;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO.TableCount;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergeRequest;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergeResultDTO;
import dk.trustworks.intranet.aggregates.crm.merge.model.ClientMergeAudit;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Merges a duplicate client into the right one — docs/specs/crm-client-merge-2026-09-14.md.
 *
 * <p><b>The shape is the prod datafix's</b> (2026-09-14, seven pairs by hand): show a
 * person what would move ({@link #preview}), then in ONE transaction remove the loser's
 * rows that would collide with the winner's unique keys, repoint every column in
 * {@link ClientMergeRegistry#REPOINT}, tombstone the loser, and verify that nothing still
 * points at it before committing — a verification that fails rolls the whole merge back.
 *
 * <p><b>Never touches e-conomic</b> (spec §5). Intra does not own the customers there and
 * cannot merge two of them; the winner keeps its mapping per agreement, and the loser's
 * customer numbers the winner already had an agreement for are written on the audit row
 * and answered back as orphaned, for a person to deactivate by hand.
 *
 * <p>Every identifier interpolated into SQL below is a constant from
 * {@link ClientMergeRegistry}; every uuid is a bound parameter.
 */
@JBossLog
@ApplicationScoped
public class ClientMergeService {

    @Inject
    EntityManager em;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    ClientActivityLogService activityLog;

    @Inject
    ObjectMapper mapper;

    // ------------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------------

    /** What merging {@code loser} into {@code winner} would do. Reads only. */
    @Transactional
    public ClientMergePreviewDTO preview(String winnerUuid, String loserUuid) {
        Pair pair = load(winnerUuid, loserUuid);
        Picture picture = describe(pair, existingTables());
        return new ClientMergePreviewDTO(
                ref(pair.winner()),
                ref(pair.loser()),
                problemOf(pair),
                picture.moves(),
                picture.dropped(),
                picture.account(),
                picture.monthControls(),
                picture.orphaned(),
                picture.moved(),
                picture.skipped(),
                picture.moves().stream().mapToLong(TableCount::rows).sum(),
                picture.dropped().stream().mapToLong(TableCount::rows).sum());
    }

    // ------------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------------

    /**
     * Merges {@code loser} into {@code winner}. One transaction; the loser's row is
     * tombstoned, not deleted (spec D1).
     *
     * @throws WebApplicationException 404 when either row is missing, 409 when the pair
     *         may not be merged (same row, already merged, wrong direction — the sentence
     *         the preview carried as {@code problem}), 400 when both rows have an account
     *         and the request does not say whose to keep
     */
    @Transactional
    public ClientMergeResultDTO merge(String winnerUuid, String loserUuid, ClientMergeRequest request) {
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            // A merge is a person's decision about the firm's books; it is not attributed to a machine.
            throw new WebApplicationException("A merge must be made by a person — X-Requested-By is missing", 400);
        }
        lock(winnerUuid, loserUuid);
        Pair pair = load(winnerUuid, loserUuid);
        String problem = problemOf(pair);
        if (problem != null) {
            throw new WebApplicationException(problem, 409);
        }
        Set<String> tables = existingTables();
        Picture picture = describe(pair, tables);
        AccountFrom accountFrom = ClientMergeRules.requireAccountFrom(
                request == null ? null : request.accountFrom(), picture.bothHaveAccounts());

        Client winner = pair.winner();
        Client loser = pair.loser();
        String w = winner.getUuid();
        String l = loser.getUuid();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> dropped = new LinkedHashMap<>();
        Map<String, Long> moved = new LinkedHashMap<>();

        log.infof("Client merge starting: loser=%s (%s) -> winner=%s (%s), accountFrom=%s, by=%s",
                l, loser.getName(), w, winner.getName(), accountFrom, actor);

        // 1. Collapse: the loser's rows that would collide with a unique key on the winner.
        for (Unique u : ClientMergeRegistry.UNIQUE) {
            if (!tables.contains(u.table())) {
                continue;
            }
            long n = switch (u.strategy()) {
                case PERSON -> collapsePersons(w, l, tables);
                case ACCOUNT -> collapseAccount(w, l, picture.bothHaveAccounts(), accountFrom);
                case MONTH_CONTROL -> collapseMonthControls(w, l, picture.monthControls());
                case ECONOMICS_CUSTOMER -> collapseEconomicsCustomers(l, picture.orphaned());
                case DROP_ALL -> execute("DELETE FROM `" + u.table() + "` WHERE `" + u.column() + "` = :l", Map.of("l", l));
                case KEEP_WINNER, UNION -> dropCollisions(u, w, l);
            };
            if (n > 0) {
                dropped.merge(u.table(), n, Long::sum);
            }
        }

        // 2. The owner. The account manager lives on the client row, so it is the one
        //    piece of "the account" that does not move with client_account.
        String ownerBefore = winner.getAccountmanager();
        String loserOwner = loser.getAccountmanager();
        boolean takeLoserOwner = accountFrom == AccountFrom.LOSER
                || ((ownerBefore == null || ownerBefore.isBlank()) && loserOwner != null && !loserOwner.isBlank());
        if (takeLoserOwner && !Objects.equals(ownerBefore, loserOwner)) {
            if (loserOwner == null || loserOwner.isBlank()) {
                execute("UPDATE `client` SET `accountmanager` = NULL WHERE `uuid` = :w", Map.of("w", w));
            } else {
                execute("UPDATE `client` SET `accountmanager` = :am WHERE `uuid` = :w", Map.of("am", loserOwner, "w", w));
            }
            activityLog.logFieldChange(w, ClientActivityLog.TYPE_CLIENT, w, winner.getName(),
                    "accountmanager", ownerBefore, loserOwner);
        }

        // 3. Repoint every remaining reference.
        for (Ref ref : ClientMergeRegistry.REPOINT) {
            if (!tables.contains(ref.table())) {
                continue;
            }
            long n = repoint(ref, w, l);
            if (n > 0) {
                moved.put(ref.key(), n);
            }
        }

        // 4. The tombstone — and any older tombstone that pointed at the loser now points
        //    at the winner, so a chain of merges always resolves in one hop.
        execute("UPDATE `client` SET `merged_into_uuid` = :w WHERE `merged_into_uuid` = :l", Map.of("w", w, "l", l));
        execute("UPDATE `client` SET `merged_into_uuid` = :w, `merged_at` = :now WHERE `uuid` = :l",
                Map.of("w", w, "l", l, "now", Timestamp.valueOf(now)));

        // 5. If the loser's CVR was what marked the winner DUPLICATE, the question is closed:
        //    back to PENDING so the next nightly pass verifies it instead of a person.
        if (tables.contains("client_enrichment")) {
            execute("UPDATE `client_enrichment` SET `cvr_status` = 'PENDING', `cvr_error` = NULL "
                    + "WHERE `client_uuid` = :w AND `cvr_status` = 'DUPLICATE'", Map.of("w", w));
        }

        // 6. Verify before committing: nothing may still reference the loser. A miss here
        //    is a registry bug, and the honest answer is to roll the merge back.
        List<String> stillReferenced = new ArrayList<>();
        for (Ref ref : ClientMergeRegistry.REPOINT) {
            if (tables.contains(ref.table()) && count(ref, l) > 0) {
                stillReferenced.add(ref.key());
            }
        }
        if (!stillReferenced.isEmpty()) {
            throw new IllegalStateException("Client merge verification failed — still referencing the loser: " + stillReferenced);
        }

        // 7. The audit row, and a line in both activity feeds.
        ClientMergeAudit audit = new ClientMergeAudit();
        audit.setUuid(UUID.randomUUID().toString());
        audit.setWinnerUuid(w);
        audit.setLoserUuid(l);
        audit.setLoserName(loser.getName());
        audit.setLoserType(loser.getType() == null ? "" : loser.getType().name());
        audit.setLoserCvr(loser.getCvr());
        audit.setAccountFrom(accountFrom.name());
        audit.setMovedJson(json(moved));
        audit.setDroppedJson(json(dropped));
        audit.setOrphanedEconomicsJson(json(picture.orphaned()));
        audit.setActorUuid(actor);
        audit.setMergedAt(now);
        audit.persist();

        activityLog.logChange(w, ClientActivityLog.TYPE_CLIENT, l, loser.getName(),
                ClientActivityLog.ACTION_MERGED, "merged_from", null, loser.getName());
        activityLog.logChange(l, ClientActivityLog.TYPE_CLIENT, w, winner.getName(),
                ClientActivityLog.ACTION_MERGED, "merged_into", null, winner.getName());

        log.infof("Client merge done: loser=%s -> winner=%s, moved=%s, dropped=%s, orphanedEconomics=%d, audit=%s",
                l, w, moved, dropped, picture.orphaned().size(), audit.getUuid());

        return new ClientMergeResultDTO(
                audit.getUuid(), w, l, now, accountFrom.name(),
                toCounts(moved, true), toCounts(dropped, false),
                picture.orphaned(), picture.moved(), picture.skipped(),
                moved.values().stream().mapToLong(Long::longValue).sum(),
                dropped.values().stream().mapToLong(Long::longValue).sum());
    }

    // ------------------------------------------------------------------------
    // The picture: what a merge would do, read without writing
    // ------------------------------------------------------------------------

    private record Pair(Client winner, Client loser) {}

    private record Picture(
            List<TableCount> moves,
            List<TableCount> dropped,
            AccountChoice account,
            List<MonthControlDecision> monthControls,
            List<EconomicsCustomerRef> orphaned,
            List<EconomicsCustomerRef> moved,
            List<String> skipped,
            boolean bothHaveAccounts) {}

    private Pair load(String winnerUuid, String loserUuid) {
        Client winner = Client.findById(winnerUuid);
        if (winner == null) {
            throw new WebApplicationException("Client not found: " + winnerUuid, 404);
        }
        Client loser = Client.findById(loserUuid);
        if (loser == null) {
            throw new WebApplicationException("Client not found: " + loserUuid, 404);
        }
        return new Pair(winner, loser);
    }

    /** Why this pair may not be merged, or {@code null}. The 409 sentence and the preview's {@code problem}. */
    private String problemOf(Pair pair) {
        Client winner = pair.winner();
        Client loser = pair.loser();
        String problem = ClientMergeRules.sameRowProblem(winner.getUuid(), loser.getUuid());
        if (problem == null && loser.isMerged()) {
            problem = loser.getName() + " has already been merged into another client";
        }
        if (problem == null && winner.isMerged()) {
            problem = winner.getName() + " has itself been merged away — merge into the client it went to";
        }
        if (problem == null) {
            problem = ClientMergeRules.directionProblem(loser.getName(), winner.getType(), loser.getType());
        }
        return problem;
    }

    private Picture describe(Pair pair, Set<String> tables) {
        String w = pair.winner().getUuid();
        String l = pair.loser().getUuid();

        List<String> skipped = new ArrayList<>();
        Set<String> registryTables = new LinkedHashSet<>();
        ClientMergeRegistry.REPOINT.forEach(r -> registryTables.add(r.table()));
        ClientMergeRegistry.UNIQUE.forEach(u -> registryTables.add(u.table()));
        for (String table : registryTables) {
            if (!tables.contains(table)) {
                skipped.add(table);
                log.warnf("Client merge: table %s is not in this database and is skipped", table);
            }
        }

        List<TableCount> moves = new ArrayList<>();
        for (Ref ref : ClientMergeRegistry.REPOINT) {
            if (!tables.contains(ref.table())) {
                continue;
            }
            long n = count(ref, l);
            if (n > 0) {
                moves.add(new TableCount(ref.table(), ref.column(), n));
            }
        }

        boolean winnerHasAccount = tables.contains("client_account") && exists("client_account", "client_uuid", w);
        boolean loserHasAccount = tables.contains("client_account") && exists("client_account", "client_uuid", l);
        boolean both = winnerHasAccount && loserHasAccount;
        AccountChoice account = both ? new AccountChoice(accountSide(pair.winner()), accountSide(pair.loser())) : null;

        List<MonthControlDecision> monthControls = tables.contains("client_month_control")
                ? monthControls(w, l) : List.of();

        List<EconomicsCustomerRef> orphaned = new ArrayList<>();
        List<EconomicsCustomerRef> movedEconomics = new ArrayList<>();
        if (tables.contains("client_economics_customer")) {
            economics(w, l, orphaned, movedEconomics);
        }

        List<TableCount> dropped = new ArrayList<>();
        for (Unique u : ClientMergeRegistry.UNIQUE) {
            if (!tables.contains(u.table())) {
                continue;
            }
            long n = switch (u.strategy()) {
                case PERSON -> countPersonCollisions(w, l);
                case ACCOUNT -> both ? 1 : 0;
                case MONTH_CONTROL -> monthControls.size();
                case ECONOMICS_CUSTOMER -> orphaned.size();
                case DROP_ALL -> count(new Ref(u.table(), u.column()), l);
                case KEEP_WINNER, UNION -> countCollisions(u, w, l);
            };
            if (n > 0) {
                dropped.add(new TableCount(u.table(), null, n));
            }
        }

        return new Picture(moves, dropped, account, monthControls, orphaned, movedEconomics, skipped, both);
    }

    private ClientRef ref(Client client) {
        return new ClientRef(
                client.getUuid(),
                client.getName(),
                client.getType() == null ? null : client.getType().name(),
                client.getCvr(),
                client.getCreated(),
                client.getAccountmanager(),
                userName(client.getAccountmanager()),
                client.isMerged());
    }

    private AccountSide accountSide(Client client) {
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT `band`, `next_step`, `gtm_bubble_uuid` FROM `client_account` WHERE `client_uuid` = :c")
                .setParameter("c", client.getUuid())
                .getSingleResult();
        return new AccountSide(
                (String) row[0],
                client.getAccountmanager(),
                userName(client.getAccountmanager()),
                (String) row[1],
                (String) row[2]);
    }

    @SuppressWarnings("unchecked")
    private List<MonthControlDecision> monthControls(String w, String l) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT w.`month`, w.`approved_at`, w.`note`, l.`approved_at`, l.`note` "
                                + "FROM `client_month_control` w JOIN `client_month_control` l ON l.`month` = w.`month` "
                                + "WHERE w.`client_uuid` = :w AND l.`client_uuid` = :l ORDER BY w.`month`")
                .setParameter("w", w)
                .setParameter("l", l)
                .getResultList();
        List<MonthControlDecision> out = new ArrayList<>();
        for (Object[] row : rows) {
            boolean winnerHas = ClientMergeRules.monthControlHasContent(toDateTime(row[1]), (String) row[2]);
            boolean loserHas = ClientMergeRules.monthControlHasContent(toDateTime(row[3]), (String) row[4]);
            out.add(new MonthControlDecision(toDate(row[0]),
                    ClientMergeRules.monthControlKeep(winnerHas, loserHas).name(), winnerHas, loserHas));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void economics(String w, String l, List<EconomicsCustomerRef> orphaned, List<EconomicsCustomerRef> moved) {
        Map<String, Integer> winnerByCompany = new LinkedHashMap<>();
        List<Object[]> winnerRows = em.createNativeQuery(
                        "SELECT `company_uuid`, `customer_number` FROM `client_economics_customer` WHERE `client_uuid` = :w")
                .setParameter("w", w)
                .getResultList();
        for (Object[] row : winnerRows) {
            winnerByCompany.put((String) row[0], ((Number) row[1]).intValue());
        }
        List<Object[]> loserRows = em.createNativeQuery(
                        "SELECT `company_uuid`, `customer_number` FROM `client_economics_customer` WHERE `client_uuid` = :l ORDER BY `company_uuid`")
                .setParameter("l", l)
                .getResultList();
        for (Object[] row : loserRows) {
            String company = (String) row[0];
            int number = ((Number) row[1]).intValue();
            Integer winnerNumber = winnerByCompany.get(company);
            EconomicsCustomerRef ref = new EconomicsCustomerRef(company, companyName(company), number,
                    winnerNumber, winnerNumber != null && winnerNumber == number);
            if (winnerNumber != null) {
                orphaned.add(ref);
            } else {
                moved.add(ref);
            }
        }
    }

    // ------------------------------------------------------------------------
    // The collapses
    // ------------------------------------------------------------------------

    /**
     * {@code account_person}: a name on both sides is one person. For every loser person
     * whose {@code name_key} the winner already holds, the identities and claims the
     * survivor does not have move across, the plan-stakeholder links follow, and the loser
     * row goes — the same union {@code AccountPersonService} applies within one client.
     * Loser persons with no counterpart are repointed later like any other row.
     */
    @SuppressWarnings("unchecked")
    private long collapsePersons(String w, String l, Set<String> tables) {
        List<Object[]> pairs = em.createNativeQuery(
                        "SELECT d.`uuid`, s.`uuid` FROM `account_person` d JOIN `account_person` s ON d.`name_key` = s.`name_key` "
                                + "WHERE d.`client_uuid` = :l AND s.`client_uuid` = :w")
                .setParameter("l", l)
                .setParameter("w", w)
                .getResultList();
        for (Object[] pair : pairs) {
            String loserPerson = (String) pair[0];
            String survivor = (String) pair[1];
            // Hibernate refuses a bound name the statement does not use, so each statement
            // gets exactly its own parameters.
            Map<String, Object> lpW = Map.of("lp", loserPerson, "w", w);
            Map<String, Object> lpSp = Map.of("lp", loserPerson, "sp", survivor);
            Map<String, Object> lpSpW = Map.of("lp", loserPerson, "sp", survivor, "w", w);
            if (tables.contains("account_person_identity")) {
                execute("DELETE d FROM `account_person_identity` d JOIN `account_person_identity` s "
                        + "ON d.`kind` <=> s.`kind` AND d.`value` <=> s.`value` "
                        + "WHERE d.`person_uuid` = :lp AND s.`client_uuid` = :w", lpW);
                execute("UPDATE `account_person_identity` SET `person_uuid` = :sp, `client_uuid` = :w WHERE `person_uuid` = :lp", lpSpW);
            }
            if (tables.contains("account_relation_claim")) {
                execute("DELETE d FROM `account_relation_claim` d JOIN `account_relation_claim` s "
                        + "ON d.`user_uuid` <=> s.`user_uuid` "
                        + "WHERE d.`person_uuid` = :lp AND s.`person_uuid` = :sp", lpSp);
                execute("UPDATE `account_relation_claim` SET `person_uuid` = :sp, `client_uuid` = :w WHERE `person_uuid` = :lp", lpSpW);
            }
            if (tables.contains("client_plan_stakeholder")) {
                execute("UPDATE `client_plan_stakeholder` SET `person_uuid` = :sp WHERE `person_uuid` = :lp", lpSp);
            }
            execute("DELETE FROM `account_person` WHERE `uuid` = :lp", Map.of("lp", loserPerson));
        }
        return pairs.size();
    }

    /** {@code client_account}: when both have one, the side NOT chosen loses its row (spec D2). */
    private long collapseAccount(String w, String l, boolean both, AccountFrom accountFrom) {
        if (!both) {
            return 0;
        }
        String dropUuid = accountFrom == AccountFrom.LOSER ? w : l;
        return execute("DELETE FROM `client_account` WHERE `client_uuid` = :c", Map.of("c", dropUuid));
    }

    /** {@code client_month_control}: per month both control, the side the rule did not keep loses its row (spec D3). */
    private long collapseMonthControls(String w, String l, List<MonthControlDecision> decisions) {
        long n = 0;
        for (MonthControlDecision d : decisions) {
            String dropUuid = "LOSER".equals(d.keep()) ? w : l;
            n += execute("DELETE FROM `client_month_control` WHERE `client_uuid` = :c AND `month` = :m",
                    Map.of("c", dropUuid, "m", Date.valueOf(d.month())));
        }
        return n;
    }

    /** {@code client_economics_customer}: the loser's mapping for an agreement the winner already has is dropped, never merged (spec §5). */
    private long collapseEconomicsCustomers(String l, List<EconomicsCustomerRef> orphaned) {
        long n = 0;
        for (EconomicsCustomerRef ref : orphaned) {
            n += execute("DELETE FROM `client_economics_customer` WHERE `client_uuid` = :l AND `company_uuid` = :c",
                    Map.of("l", l, "c", ref.companyUuid()));
        }
        return n;
    }

    /** The generic collision: the loser's rows whose key the winner already holds. */
    private long dropCollisions(Unique u, String w, String l) {
        return execute("DELETE d FROM `" + u.table() + "` d JOIN `" + u.table() + "` s ON " + keyJoin(u)
                + " WHERE d.`" + u.column() + "` = :l AND s.`" + u.column() + "` = :w", Map.of("l", l, "w", w));
    }

    private long countCollisions(Unique u, String w, String l) {
        return scalar("SELECT COUNT(*) FROM `" + u.table() + "` d JOIN `" + u.table() + "` s ON " + keyJoin(u)
                + " WHERE d.`" + u.column() + "` = :l AND s.`" + u.column() + "` = :w", Map.of("l", l, "w", w));
    }

    private long countPersonCollisions(String w, String l) {
        return scalar("SELECT COUNT(*) FROM `account_person` d JOIN `account_person` s ON d.`name_key` = s.`name_key` "
                + "WHERE d.`client_uuid` = :l AND s.`client_uuid` = :w", Map.of("l", l, "w", w));
    }

    /** {@code ON d.a <=> s.a AND d.b <=> s.b}; a key of the client column alone joins on nothing else. */
    private static String keyJoin(Unique u) {
        if (u.keyColumns().isEmpty()) {
            return "1 = 1";
        }
        StringBuilder sb = new StringBuilder();
        for (String col : u.keyColumns()) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append("d.`").append(col).append("` <=> s.`").append(col).append('`');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // The repoint
    // ------------------------------------------------------------------------

    private long repoint(Ref ref, String w, String l) {
        String sql = "UPDATE `" + ref.table() + "` SET `" + ref.column() + "` = :w WHERE `" + ref.column() + "` = :l";
        if (!ClientMergeRegistry.BATCHED.contains(ref.table())) {
            return execute(sql, Map.of("w", w, "l", l));
        }
        long total = 0;
        while (true) {
            long n = execute(sql + " LIMIT " + ClientMergeRegistry.BATCH_SIZE, Map.of("w", w, "l", l));
            total += n;
            if (n < ClientMergeRegistry.BATCH_SIZE) {
                return total;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------------

    /** Row locks on both client rows for the length of the merge — two merges of one pair serialise. */
    private void lock(String winnerUuid, String loserUuid) {
        em.createNativeQuery("SELECT `uuid` FROM `client` WHERE `uuid` IN (:a, :b) FOR UPDATE")
                .setParameter("a", winnerUuid)
                .setParameter("b", loserUuid)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private Set<String> existingTables() {
        List<Object> rows = em.createNativeQuery(
                        "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")
                .getResultList();
        Set<String> tables = new HashSet<>();
        for (Object row : rows) {
            tables.add(String.valueOf(row));
        }
        return tables;
    }

    private long count(Ref ref, String uuid) {
        return scalar("SELECT COUNT(*) FROM `" + ref.table() + "` WHERE `" + ref.column() + "` = :u", Map.of("u", uuid));
    }

    private boolean exists(String table, String column, String uuid) {
        return scalar("SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` = :u", Map.of("u", uuid)) > 0;
    }

    private long scalar(String sql, Map<String, Object> params) {
        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return ((Number) q.getSingleResult()).longValue();
    }

    private long execute(String sql, Map<String, Object> params) {
        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q.executeUpdate();
    }

    private String companyName(String companyUuid) {
        if (companyUuid == null) {
            return null;
        }
        List<?> rows = em.createNativeQuery("SELECT `name` FROM `companies` WHERE `uuid` = :u")
                .setParameter("u", companyUuid)
                .getResultList();
        return rows.isEmpty() ? null : String.valueOf(rows.get(0));
    }

    private String userName(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            return null;
        }
        User user = User.findById(userUuid);
        if (user == null) {
            return null;
        }
        String name = ((user.getFirstname() == null ? "" : user.getFirstname()) + " "
                + (user.getLastname() == null ? "" : user.getLastname())).trim();
        return name.isEmpty() ? null : name;
    }

    private static List<TableCount> toCounts(Map<String, Long> byKey, boolean keyed) {
        List<TableCount> out = new ArrayList<>();
        byKey.forEach((key, rows) -> {
            if (keyed) {
                int dot = key.indexOf('.');
                out.add(new TableCount(key.substring(0, dot), key.substring(dot + 1), rows));
            } else {
                out.add(new TableCount(key, null, rows));
            }
        });
        return out;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the merge audit", e);
        }
    }

    private static LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    private static LocalDate toDate(Object value) {
        if (value instanceof Date d) {
            return d.toLocalDate();
        }
        if (value instanceof LocalDate ld) {
            return ld;
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
