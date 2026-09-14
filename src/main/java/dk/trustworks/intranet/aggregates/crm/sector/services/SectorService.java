package dk.trustworks.intranet.aggregates.crm.sector.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountActivityDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountRelationship;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountActivityService;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountRateService;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;
import dk.trustworks.intranet.aggregates.crm.gtm.services.GtmTeamService;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanStatus;
import dk.trustworks.intranet.aggregates.crm.plan.services.AccountPlanService;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorDTO;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorPlanRefDTO;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorSummaryDTO;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorPlan;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The sector roll-up (sectors spec §3.4, §5): every number on a sector card and on the
 * sector page, derived from the accounts in the segment. Nothing here is typed; the only
 * typed things about a sector — its lead and its plan — are read from their own services.
 *
 * <h2>Membership is free</h2>
 * Every client is in exactly one sector by {@code client.segment}, with a null segment
 * reading as OTHER everywhere. There is no link table, and there will not be one.
 *
 * <h2>Whole-table queries, never per row</h2>
 * The Sectors tab renders six cards over ~280 clients; the page must not issue a query
 * per client. {@link #load()} runs one query per source over the whole table — bands,
 * plans, last activity, open leads, weighted pipeline, waiting signals, FY revenue, rates —
 * and every card is assembled from those maps. The same approach as
 * {@code AccountResource.summaries()}.
 *
 * <h2>The thresholds</h2>
 * Spec §5, and the same constants the frontend carries in {@code accountTypes.ts}: a plan
 * is stale past 45 days and overdue past 90; an Active/Strategic account is quiet after 90
 * days without activity; a contract is expiring within 90 days.
 */
@JBossLog
@ApplicationScoped
public class SectorService {

    /** The order the executive dashboard's Industries tab uses; the cards keep it. */
    public static final List<ClientSegment> SECTOR_ORDER = List.of(
            ClientSegment.PUBLIC, ClientSegment.ENERGY, ClientSegment.HEALTH,
            ClientSegment.FINANCIAL, ClientSegment.EDUCATION, ClientSegment.OTHER);

    public static final int PLAN_STALE_AFTER_DAYS = 45;
    public static final int PLAN_OVERDUE_AFTER_DAYS = 90;
    public static final int QUIET_AFTER_DAYS = 90;
    public static final int CONTRACT_EXPIRING_WITHIN_DAYS = 90;
    public static final int RECENT_CHANGE_DAYS = 30;

    /** Internal Trustworks client — excluded from revenue, as the CXO services do. */
    static final String INTERNAL_CLIENT_UUID = "d58bb00b-4474-4250-84eb-d8f77548ddac";

    public static final String COVERAGE_OK = "OK";
    public static final String COVERAGE_STALE = "STALE";
    public static final String COVERAGE_OVERDUE = "OVERDUE";
    public static final String COVERAGE_MISSING = "MISSING";

    @Inject
    EntityManager em;

    @Inject
    ClientService clientService;

    @Inject
    AccountService accountService;

    @Inject
    AccountPlanService accountPlanService;

    @Inject
    AccountActivityService activityService;

    @Inject
    AccountRateService rateService;

    @Inject
    SectorLeadService sectorLeadService;

    @Inject
    GtmTeamService gtmTeamService;

    // ------------------------------------------------------------------------
    // Pure rules — package-private for the fast tier
    // ------------------------------------------------------------------------

    public static ClientSegment parseSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new WebApplicationException("A sector is required", Response.Status.BAD_REQUEST);
        }
        try {
            return ClientSegment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown sector: " + raw, Response.Status.NOT_FOUND);
        }
    }

    /** A client with no segment is OTHER — everywhere the segment is read. */
    public static ClientSegment segmentOf(Client client) {
        return client == null || client.getSegment() == null ? ClientSegment.OTHER : client.getSegment();
    }

    /** FY2026 = Jul 2026 – Jun 2027, named by its start year (docs/finalized/shared/fiscal-year.md). */
    public static int fiscalYearOf(LocalDate date) {
        return date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1;
    }

    /**
     * Which coverage bucket a Strategic/Active account's plan falls in: missing when no
     * plan was started, else by the days since it was last confirmed.
     */
    public static String coverageBucket(boolean started, LocalDate updatedAt, LocalDate today) {
        if (!started || updatedAt == null) {
            return COVERAGE_MISSING;
        }
        long days = ChronoUnit.DAYS.between(updatedAt, today);
        if (days > PLAN_OVERDUE_AFTER_DAYS) {
            return COVERAGE_OVERDUE;
        }
        if (days > PLAN_STALE_AFTER_DAYS) {
            return COVERAGE_STALE;
        }
        return COVERAGE_OK;
    }

    /** Quiet: an Active/Strategic account nobody has seen anything on for 90 days. */
    public static boolean isQuiet(LocalDate lastActivity, LocalDate today) {
        return lastActivity == null || ChronoUnit.DAYS.between(lastActivity, today) > QUIET_AFTER_DAYS;
    }

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /** One card per segment, always six, in {@link #SECTOR_ORDER}. */
    public List<SectorSummaryDTO> summaries(boolean maySeeCost) {
        Context context = load();
        List<SectorSummaryDTO> cards = new ArrayList<>();
        for (ClientSegment segment : SECTOR_ORDER) {
            cards.add(summary(segment, context, maySeeCost));
        }
        return cards;
    }

    public SectorDTO read(ClientSegment segment, boolean maySeeCost, boolean viewerIsManagement) {
        Context context = load();
        List<SectorDTO.SectorSignalDTO> signals = waitingSignals(segment, context);
        return new SectorDTO(
                summary(segment, context, maySeeCost),
                flags(segment, context, signals),
                recentChanges(segment),
                ownerWorkload(segment, context),
                signals,
                consultants(segment),
                viewerIsManagement);
    }

    // ------------------------------------------------------------------------
    // Everything, once
    // ------------------------------------------------------------------------

    private record ExpiringContract(String contractUuid, String clientUuid, String name, LocalDate ends) {
    }

    private record Context(
            LocalDate today,
            int fiscalYear,
            List<Client> clients,
            Map<String, AccountBand> bands,
            Map<String, AccountRelationshipDTO> relationships,
            Map<String, AccountPlanService.PlanSummary> plans,
            Map<String, AccountActivityDTO> lastActivity,
            Map<String, Integer> openLeads,
            Map<String, Double> weightedPipeline,
            Map<String, Integer> signalsWaiting,
            Map<String, double[]> fyRevenue,
            Map<String, AccountRateService.SegmentRate> rates,
            Double breakEven,
            Map<ClientSegment, PersonDTO> leads,
            Map<ClientSegment, List<SectorSummaryDTO.TeamRefDTO>> teams,
            Map<ClientSegment, SectorPlan> sectorPlans,
            Map<String, List<ExpiringContract>> expiring,
            Set<String> clientsWithExtensionLead) {

        List<Client> clientsIn(ClientSegment segment) {
            List<Client> rows = new ArrayList<>();
            for (Client client : clients) {
                if (segmentOf(client) == segment) {
                    rows.add(client);
                }
            }
            return rows;
        }

        AccountBand bandOf(Client client) {
            return bands.getOrDefault(client.getUuid(), AccountBand.BACKLOG);
        }

        /**
         * A company Intra knows and has never billed, with no open lead and no decided
         * band. Nothing is expected of one: it raises no alert, needs no owner, counts in
         * no plan coverage and is never quiet.
         */
        boolean isContact(Client client) {
            AccountRelationshipDTO relationship = relationships.get(client.getUuid());
            return relationship != null
                    && AccountRelationship.CONTACT.name().equals(relationship.relationship());
        }
    }

    private Context load() {
        LocalDate today = LocalDate.now();
        int fiscalYear = fiscalYearOf(today);
        Map<ClientSegment, SectorPlan> sectorPlans = new EnumMap<>(ClientSegment.class);
        for (SectorPlan plan : SectorPlan.<SectorPlan>listAll()) {
            sectorPlans.put(plan.getSegment(), plan);
        }
        // Clients AND prospects. A sector has to partition every company Intra knows,
        // and after 2026-09-14 most of the companies nobody has billed are typed PROSPECT
        // — leaving them out would have made a sector's contact count permanently zero.
        // Partners are still out: an intermediary billing entity is not an account.
        Map<String, AccountBand> bands = accountService.bandsForAll();
        return new Context(
                today,
                fiscalYear,
                clientService.listByTypes(List.of(ClientType.CLIENT, ClientType.PROSPECT)),
                bands,
                accountService.relationshipsForAll(bands),
                accountPlanService.summariesForAll(),
                activityService.lastActivityForAll(),
                countByClient("""
                        select clientuuid, count(*) from sales_lead
                         where status not in ('WON', 'LOST') and salesleaduuid is null
                         group by clientuuid
                        """),
                sumByClient("""
                        select clientuuid,
                               sum(period * 150 * (allocation / 100.0) * rate
                                   * (case status
                                          when 'DETECTED' then 0.10
                                          when 'QUALIFIED' then 0.25
                                          when 'PROPOSAL' then 0.50
                                          when 'SHORTLISTED' then 0.40
                                          when 'NEGOTIATION' then 0.75
                                          else 0 end))
                          from sales_lead
                         where status not in ('WON', 'LOST') and salesleaduuid is null
                         group by clientuuid
                        """),
                countByClient("""
                        select client_uuid, count(*) from account_signal
                         where status = 'NEW' group by client_uuid
                        """),
                fyRevenueByClient(fiscalYear),
                rateService.weightedRateBySegment(),
                rateService.firmBreakEven(),
                sectorLeadService.currentLeads(),
                gtmTeamService.teamsBySegment(),
                sectorPlans,
                expiringContracts(today),
                clientsWithExtensionLead());
    }

    // ------------------------------------------------------------------------
    // The card
    // ------------------------------------------------------------------------

    private SectorSummaryDTO summary(ClientSegment segment, Context context, boolean maySeeCost) {
        int strategic = 0;
        int active = 0;
        int backlog = 0;
        int contacts = 0;
        int unowned = 0;
        int quiet = 0;
        int openLeads = 0;
        double pipeline = 0;
        int signalsWaiting = 0;
        double revenue = 0;
        double revenuePrevious = 0;
        int ok = 0;
        int stale = 0;
        int overdue = 0;
        int missing = 0;

        for (Client client : context.clientsIn(segment)) {
            String uuid = client.getUuid();
            AccountBand band = context.bandOf(client);
            boolean isContact = context.isContact(client);
            if (isContact) {
                contacts++;
            } else {
                switch (band) {
                    case STRATEGIC -> strategic++;
                    case ACTIVE -> active++;
                    default -> backlog++;
                }
            }
            openLeads += context.openLeads().getOrDefault(uuid, 0);
            pipeline += context.weightedPipeline().getOrDefault(uuid, 0.0);
            signalsWaiting += context.signalsWaiting().getOrDefault(uuid, 0);
            double[] fy = context.fyRevenue().get(uuid);
            if (fy != null) {
                revenue += fy[0];
                revenuePrevious += fy[1];
            }
            if (band == AccountBand.BACKLOG) {
                continue;
            }
            if (isBlank(client.getAccountmanager())) {
                unowned++;
            }
            AccountActivityDTO last = context.lastActivity().get(uuid);
            if (isQuiet(last == null ? null : last.occurredAt(), context.today())) {
                quiet++;
            }
            AccountPlanService.PlanSummary plan = context.plans().get(uuid);
            switch (coverageBucket(plan != null && plan.started(), plan == null ? null : plan.updatedAt(), context.today())) {
                case COVERAGE_OK -> ok++;
                case COVERAGE_STALE -> stale++;
                case COVERAGE_OVERDUE -> overdue++;
                default -> missing++;
            }
        }

        AccountRateService.SegmentRate rate = context.rates().get(segment.name());
        Double weighted = rate == null ? null : rate.weighted();
        Double breakEven = maySeeCost ? context.breakEven() : null;
        Double target = breakEven == null ? null : AccountRateService.targetFor(breakEven);

        return new SectorSummaryDTO(
                segment.name(),
                segment.getDisplayName(),
                context.leads().get(segment),
                new SectorSummaryDTO.AccountsByBandDTO(strategic, active, backlog, contacts, unowned),
                quiet,
                context.fiscalYear(),
                round(revenue),
                round(revenuePrevious),
                weighted,
                breakEven,
                target,
                rate == null ? 0 : rate.consultants(),
                openLeads,
                round(pipeline),
                signalsWaiting,
                new SectorSummaryDTO.PlanCoverageDTO(ok, stale, overdue, missing),
                planRef(context.sectorPlans().get(segment)),
                context.teams().getOrDefault(segment, List.of()));
    }

    static SectorPlanRefDTO planRef(SectorPlan plan) {
        if (plan == null) {
            return new SectorPlanRefDTO(false, null, null, null, 1);
        }
        return new SectorPlanRefDTO(
                plan.getStatus() == PlanStatus.ACTIVE,
                plan.getHealthRag() == null ? null : plan.getHealthRag().name(),
                plan.getUpdatedAt() == null ? null : plan.getUpdatedAt().toLocalDate(),
                plan.getNextReview(),
                plan.getVersion());
    }

    // ------------------------------------------------------------------------
    // The page
    // ------------------------------------------------------------------------

    private List<SectorDTO.SectorFlagDTO> flags(ClientSegment segment, Context context,
                                                List<SectorDTO.SectorSignalDTO> signals) {
        List<SectorDTO.SectorFlagDTO> flags = new ArrayList<>();
        for (Client client : context.clientsIn(segment)) {
            String uuid = client.getUuid();
            AccountBand band = context.bandOf(client);
            String bandName = band.name();

            if (band != AccountBand.BACKLOG) {
                if (isBlank(client.getAccountmanager())) {
                    flags.add(new SectorDTO.SectorFlagDTO("UNOWNED", uuid, client.getName(), bandName,
                            "An " + bandName.toLowerCase(Locale.ROOT) + " account with nobody accountable", null, null));
                }
                AccountPlanService.PlanSummary plan = context.plans().get(uuid);
                String bucket = coverageBucket(plan != null && plan.started(),
                        plan == null ? null : plan.updatedAt(), context.today());
                if (COVERAGE_MISSING.equals(bucket)) {
                    flags.add(new SectorDTO.SectorFlagDTO("PLAN_MISSING", uuid, client.getName(), bandName,
                            "No plan has been started", null, null));
                } else if (COVERAGE_OVERDUE.equals(bucket)) {
                    long days = ChronoUnit.DAYS.between(plan.updatedAt(), context.today());
                    flags.add(new SectorDTO.SectorFlagDTO("PLAN_OVERDUE", uuid, client.getName(), bandName,
                            days + " days since anyone confirmed the plan", plan.updatedAt(), null));
                }
                AccountActivityDTO last = context.lastActivity().get(uuid);
                if (isQuiet(last == null ? null : last.occurredAt(), context.today())) {
                    flags.add(new SectorDTO.SectorFlagDTO("QUIET", uuid, client.getName(), bandName,
                            last == null
                                    ? "Nothing has ever been seen on this account"
                                    : ChronoUnit.DAYS.between(last.occurredAt(), context.today()) + " days without activity",
                            last == null ? null : last.occurredAt(), null));
                }
            }

            if (!context.clientsWithExtensionLead().contains(uuid)) {
                for (ExpiringContract contract : context.expiring().getOrDefault(uuid, List.of())) {
                    flags.add(new SectorDTO.SectorFlagDTO("CONTRACT_EXPIRING_NO_EXTENSION", uuid, client.getName(), bandName,
                            "\"" + contract.name() + "\" ends " + contract.ends() + " and no extension lead exists",
                            contract.ends(), contract.contractUuid()));
                }
            }
        }
        for (SectorDTO.SectorSignalDTO signal : signals) {
            flags.add(new SectorDTO.SectorFlagDTO("SIGNAL_WAITING", signal.clientUuid(), signal.clientName(),
                    context.bands().getOrDefault(signal.clientUuid(), AccountBand.BACKLOG).name(),
                    signal.author() == null
                            ? "A colleague heard something and nobody has decided"
                            : signal.author().name().split(" ")[0] + " heard something and nobody has decided",
                    signal.createdAt(), signal.uuid()));
        }
        flags.sort(Comparator
                .comparing((SectorDTO.SectorFlagDTO flag) -> FLAG_ORDER.getOrDefault(flag.kind(), 99))
                .thenComparing(SectorDTO.SectorFlagDTO::clientName, String.CASE_INSENSITIVE_ORDER));
        return flags;
    }

    private static final Map<String, Integer> FLAG_ORDER = Map.of(
            "SIGNAL_WAITING", 0,
            "CONTRACT_EXPIRING_NO_EXTENSION", 1,
            "UNOWNED", 2,
            "PLAN_MISSING", 3,
            "PLAN_OVERDUE", 4,
            "QUIET", 5);

    private List<SectorDTO.OwnerLoadDTO> ownerWorkload(ClientSegment segment, Context context) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Client client : context.clientsIn(segment)) {
            AccountBand band = context.bandOf(client);
            if (band == AccountBand.BACKLOG || isBlank(client.getAccountmanager())) {
                continue;
            }
            int[] load = counts.computeIfAbsent(client.getAccountmanager().trim(), key -> new int[2]);
            if (band == AccountBand.STRATEGIC) {
                load[0]++;
            } else {
                load[1]++;
            }
        }
        List<SectorDTO.OwnerLoadDTO> rows = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            PersonDTO owner = person(entry.getKey());
            if (owner != null) {
                rows.add(new SectorDTO.OwnerLoadDTO(owner, entry.getValue()[0], entry.getValue()[1]));
            }
        }
        rows.sort(Comparator
                .comparingInt((SectorDTO.OwnerLoadDTO row) -> row.strategic() + row.active()).reversed()
                .thenComparing(row -> row.owner().name(), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** The NEW signals across the sector, newest first. */
    private List<SectorDTO.SectorSignalDTO> waitingSignals(ClientSegment segment, Context context) {
        Query query = em.createNativeQuery("""
                select s.uuid, s.client_uuid, c.name, c.accountmanager, s.signal_text, s.person_name,
                       s.person_role, s.signal_type, s.author_uuid, s.created_at
                  from account_signal s
                  join client c on c.uuid = s.client_uuid
                 where s.status = 'NEW'
                   and coalesce(c.segment, 'OTHER') = :segment
                 order by s.created_at desc
                """);
        query.setParameter("segment", segment.name());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<SectorDTO.SectorSignalDTO> signals = new ArrayList<>();
        for (Object[] row : rows) {
            signals.add(new SectorDTO.SectorSignalDTO(
                    str(row[0]),
                    str(row[1]),
                    str(row[2]),
                    !isBlank(str(row[3])),
                    str(row[4]),
                    str(row[5]),
                    str(row[6]),
                    str(row[7]),
                    person(str(row[8])),
                    date(row[9])));
        }
        return signals;
    }

    /** What moved across the sector in the last 30 days: band moves, new leads, signals, reviews. */
    private List<SectorDTO.SectorChangeDTO> recentChanges(ClientSegment segment) {
        LocalDateTime since = LocalDateTime.now().minusDays(RECENT_CHANGE_DAYS);
        List<SectorDTO.SectorChangeDTO> changes = new ArrayList<>();

        for (Object[] row : segmentRows("""
                select h.uuid, h.client_uuid, c.name, h.from_band, h.to_band, h.changed_at
                  from client_band_history h
                  join client c on c.uuid = h.client_uuid
                 where coalesce(c.segment, 'OTHER') = :segment and h.changed_at >= :since
                """, segment, since)) {
            String from = str(row[3]);
            changes.add(new SectorDTO.SectorChangeDTO("band-" + str(row[0]), "BAND",
                    (from == null ? "Triaged as " : capitalise(from) + " → ") + capitalise(str(row[4])),
                    date(row[5]), str(row[1]), str(row[2])));
        }
        for (Object[] row : segmentRows("""
                select l.uuid, l.clientuuid, c.name, l.description, l.created
                  from sales_lead l
                  join client c on c.uuid = l.clientuuid
                 where coalesce(c.segment, 'OTHER') = :segment and l.created >= :since
                """, segment, since)) {
            changes.add(new SectorDTO.SectorChangeDTO("lead-" + str(row[0]), "LEAD",
                    "Lead created: " + truncate(str(row[3]), 80), date(row[4]), str(row[1]), str(row[2])));
        }
        for (Object[] row : segmentRows("""
                select s.uuid, s.client_uuid, c.name, s.signal_type, s.person_name, s.created_at
                  from account_signal s
                  join client c on c.uuid = s.client_uuid
                 where coalesce(c.segment, 'OTHER') = :segment and s.created_at >= :since
                """, segment, since)) {
            String person = str(row[4]);
            changes.add(new SectorDTO.SectorChangeDTO("signal-" + str(row[0]), "SIGNAL",
                    person == null ? "Somebody heard something" : "Somebody heard about " + person,
                    date(row[5]), str(row[1]), str(row[2])));
        }
        for (Object[] row : segmentRows("""
                select r.uuid, r.client_uuid, c.name, r.outcome, r.review_date
                  from client_plan_review r
                  join client c on c.uuid = r.client_uuid
                 where coalesce(c.segment, 'OTHER') = :segment and r.review_date >= :since
                """, segment, since.toLocalDate())) {
            changes.add(new SectorDTO.SectorChangeDTO("review-" + str(row[0]), "NOTE",
                    "Plan reviewed: " + truncate(str(row[3]), 80), date(row[4]), str(row[1]), str(row[2])));
        }

        changes.sort(Comparator.comparing(SectorDTO.SectorChangeDTO::occurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return changes;
    }

    private List<PersonDTO> consultants(ClientSegment segment) {
        List<PersonDTO> people = new ArrayList<>();
        for (String uuid : rateService.consultantUuidsForSegment(segment.name())) {
            PersonDTO person = person(uuid);
            if (person != null) {
                people.add(person);
            }
        }
        people.sort(Comparator.comparing(PersonDTO::name, String.CASE_INSENSITIVE_ORDER));
        return people;
    }

    // ------------------------------------------------------------------------
    // Whole-table queries
    // ------------------------------------------------------------------------

    private Map<String, Integer> countByClient(String sql) {
        Map<String, Integer> counts = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        for (Object[] row : rows) {
            if (row[0] != null && row[1] != null) {
                counts.put(row[0].toString(), ((Number) row[1]).intValue());
            }
        }
        return counts;
    }

    private Map<String, Double> sumByClient(String sql) {
        Map<String, Double> sums = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        for (Object[] row : rows) {
            if (row[0] != null && row[1] != null) {
                sums.put(row[0].toString(), ((Number) row[1]).doubleValue());
            }
        }
        return sums;
    }

    /** {@code client → [this FY, last FY]} net revenue, internal client excluded. */
    private Map<String, double[]> fyRevenueByClient(int fiscalYear) {
        Query query = em.createNativeQuery("""
                select client_id, fiscal_year, sum(net_revenue_dkk)
                  from fact_client_revenue_mat
                 where fiscal_year in (:current, :previous)
                   and client_id is not null
                   and client_id <> :internal
                 group by client_id, fiscal_year
                """);
        query.setParameter("current", fiscalYear);
        query.setParameter("previous", fiscalYear - 1);
        query.setParameter("internal", INTERNAL_CLIENT_UUID);
        Map<String, double[]> revenue = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null || row[2] == null) {
                continue;
            }
            double[] years = revenue.computeIfAbsent(row[0].toString(), key -> new double[2]);
            int year = ((Number) row[1]).intValue();
            years[year == fiscalYear ? 0 : 1] += ((Number) row[2]).doubleValue();
        }
        return revenue;
    }

    /** Running contracts whose last consultant ends within the window, by client. */
    private Map<String, List<ExpiringContract>> expiringContracts(LocalDate today) {
        Query query = em.createNativeQuery("""
                select c.uuid, c.clientuuid, c.name, max(cc.activeto)
                  from contracts c
                  join contract_consultants cc on cc.contractuuid = c.uuid
                 where c.status in ('SIGNED', 'TIME', 'BUDGET')
                 group by c.uuid, c.clientuuid, c.name
                having max(cc.activeto) between :today and :horizon
                """);
        query.setParameter("today", today);
        query.setParameter("horizon", today.plusDays(CONTRACT_EXPIRING_WITHIN_DAYS));
        Map<String, List<ExpiringContract>> byClient = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            if (row[1] == null) {
                continue;
            }
            byClient.computeIfAbsent(row[1].toString(), key -> new ArrayList<>())
                    .add(new ExpiringContract(str(row[0]), str(row[1]), str(row[2]), date(row[3])));
        }
        return byClient;
    }

    private Set<String> clientsWithExtensionLead() {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                select distinct clientuuid from sales_lead
                 where extension = 1 and status not in ('WON', 'LOST')
                """).getResultList();
        Set<String> clients = new HashSet<>();
        for (Object row : rows) {
            if (row != null) {
                clients.add(row.toString());
            }
        }
        return clients;
    }

    private List<Object[]> segmentRows(String sql, ClientSegment segment, Object since) {
        Query query = em.createNativeQuery(sql);
        query.setParameter("segment", segment.name());
        query.setParameter("since", since);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static PersonDTO person(String userUuid) {
        if (isBlank(userUuid)) {
            return null;
        }
        return PersonDTO.from(User.findById(userUuid.trim()));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    private static String capitalise(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.charAt(0) + raw.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
