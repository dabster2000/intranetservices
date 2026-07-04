package dk.trustworks.intranet.aggregates.clientstatus.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.clientstatus.ClientStatusMath;
import dk.trustworks.intranet.aggregates.clientstatus.dto.*;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Composes an AI-generated Danish Slack brief of a client-account-manager's outstanding
 * invoicing gaps across the TTM window (non-provisional months only).
 *
 * <p>Orchestration only: it loads the AM, their active clients (minus self-billed sources),
 * reuses {@link ClientStatusService} for the grid + per-consultant reconciliation, builds a
 * compact JSON payload, and asks OpenAI (via {@code openai.invoice-status-model}) to write the
 * message. It never sets temperature and never logs the generated text at INFO.
 */
@JBossLog
@ApplicationScoped
public class AccountManagerBriefService {

    /** Report gaps at or beyond this magnitude; smaller deltas are noise. */
    static final double GAP_FLOOR_DKK = 25_000d;
    /** Only surface a consultant's shortfall when it exceeds this, to keep the payload clean. */
    static final double CONSULTANT_MISSING_FLOOR_DKK = 1_000d;
    private static final int MAX_NAME_LENGTH = 120;
    private static final Locale DA = Locale.of("da");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    ClientStatusService clientStatusService;

    @Inject
    OpenAIService openAIService;

    @ConfigProperty(name = "openai.invoice-status-model", defaultValue = "gpt-5.4")
    String invoiceStatusModel;

    /** Immutable framing options for the generated message. */
    public enum Framing {TO_AM, SELF}

    // --- Value objects for the pure payload builder (unit-testable without a DB) ---

    record ConsultantGap(String name, double registered, double invoiced, double missing) {}

    record ProjectGap(String project, double missing) {}

    record GapMonth(String monthLabel, double expected, double invoiced, double missing,
                    List<ConsultantGap> consultantsMissing, List<ProjectGap> projectGaps) {}

    record OverMonth(String monthLabel, double surplus) {}

    record ClientAnalysis(String name, List<GapMonth> gapMonths, List<OverMonth> overMonths) {}

    public AccountManagerBriefResponse generate(String accountManagerUuid, String end, String framingRaw) {
        Framing framing = parseFraming(framingRaw);
        YearMonth endMonth = parseEnd(end);

        AccountManager am = resolveAccountManager(accountManagerUuid);

        // Active clients for this AM.
        @SuppressWarnings("unchecked")
        List<Tuple> clientRows = em.createNativeQuery("""
                SELECT c.uuid, c.name
                FROM client c
                WHERE c.accountmanager = :am AND c.active = 1
                """, Tuple.class)
                .setParameter("am", accountManagerUuid)
                .getResultList();
        Map<String, String> clientNameByUuid = new LinkedHashMap<>();
        for (Tuple r : clientRows) {
            clientNameByUuid.put((String) r.get("uuid"), (String) r.get("name"));
        }

        // Self-billed exclusion (Vattenfall, Energinet, ...).
        Set<String> selfBilledUuids = new HashSet<>();
        List<String> excludedNames = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Tuple> selfBilled = em.createNativeQuery("""
                SELECT client_uuid, label FROM selfbilled_source WHERE enabled = 1
                """, Tuple.class).getResultList();
        for (Tuple r : selfBilled) {
            String uuid = (String) r.get("client_uuid");
            if (clientNameByUuid.containsKey(uuid)) {
                selfBilledUuids.add(uuid);
                String name = clientNameByUuid.get(uuid);
                excludedNames.add(sanitize(name != null ? name : (String) r.get("label")));
            }
        }
        clientNameByUuid.keySet().removeAll(selfBilledUuids);

        // Reuse the grid; keep only this AM's non-self-billed clients.
        ClientStatusResponse grid = clientStatusService.getClientStatus(endMonth);
        Set<String> provisional = ClientStatusMath.provisionalMonthKeys(grid.months(), java.time.LocalDate.now());

        List<ClientAnalysis> analyses = new ArrayList<>();
        int gapMonthCount = 0;
        for (ClientStatusRow row : grid.clients()) {
            if (!clientNameByUuid.containsKey(row.clientUuid())) continue;

            List<GapMonth> gapMonths = new ArrayList<>();
            List<OverMonth> overMonths = new ArrayList<>();
            for (ClientStatusCell cell : row.cells()) {
                if (provisional.contains(cell.monthKey())) continue;
                double delta = cell.delta();
                if (delta <= -GAP_FLOOR_DKK) {
                    gapMonths.add(buildGapMonth(row.clientUuid(), cell));
                    gapMonthCount++;
                } else if (delta >= GAP_FLOOR_DKK) {
                    overMonths.add(new OverMonth(monthLabel(cell.monthKey()), delta));
                }
            }
            if (!gapMonths.isEmpty() || !overMonths.isEmpty()) {
                analyses.add(new ClientAnalysis(sanitize(row.clientName()), gapMonths, overMonths));
            }
        }

        log.infof("POST /invoice-controlling/client-status/account-manager-brief: am=%s clientCount=%d gapMonthCount=%d model=%s framing=%s",
                accountManagerUuid, clientNameByUuid.size(), gapMonthCount, invoiceStatusModel, framing);

        String slackText = askModel(am.firstname(), framing, analyses, excludedNames);
        if (slackText == null || slackText.isBlank()) {
            throw new WebApplicationException(
                    "AI-generering fejlede (model " + invoiceStatusModel
                            + "). Tjek at OpenAI-projektet har adgang til modellen — se backend-loggen for detaljer.",
                    Response.Status.BAD_GATEWAY);
        }

        return new AccountManagerBriefResponse(
                slackText, accountManagerUuid, am.displayName(),
                clientNameByUuid.size(), gapMonthCount, excludedNames, invoiceStatusModel);
    }

    /** Build one gap-month payload with per-consultant + per-project shortfalls. */
    private GapMonth buildGapMonth(String clientUuid, ClientStatusCell cell) {
        int year = Integer.parseInt(cell.monthKey().substring(0, 4));
        int month = Integer.parseInt(cell.monthKey().substring(4, 6));
        ClientStatusDetailResponse detail = clientStatusService.getClientStatusDetail(clientUuid, year, month);

        List<ConsultantGap> consultants = new ArrayList<>();
        for (ClientStatusConsultantRecon rc : detail.consultantRecon()) {
            if (rc.consultantUuid() == null) continue; // skip the unmatched bucket
            if (rc.missingValue() <= CONSULTANT_MISSING_FLOOR_DKK) continue;
            consultants.add(new ConsultantGap(
                    sanitize(rc.consultantName()),
                    rc.registeredValue(), rc.invoicedValue(), rc.missingValue()));
        }

        List<ProjectGap> projectGaps = buildProjectGaps(detail);

        return new GapMonth(monthLabel(cell.monthKey()), cell.expected(), cell.invoiced(),
                -cell.delta(), consultants, projectGaps);
    }

    /** Per-project shortfall = Σ work.value − Σ invoice.signedGrossConsultant, only above the consultant noise floor. */
    private List<ProjectGap> buildProjectGaps(ClientStatusDetailResponse detail) {
        Map<String, Double> expectedByProject = new LinkedHashMap<>();
        Map<String, String> nameByProject = new HashMap<>();
        for (ClientStatusWorkLine w : detail.work()) {
            String key = w.projectUuid() != null ? w.projectUuid() : "";
            expectedByProject.merge(key, w.value(), Double::sum);
            nameByProject.putIfAbsent(key, w.projectName());
        }
        Map<String, Double> invoicedByProject = new HashMap<>();
        for (ClientStatusInvoiceLine inv : detail.invoices()) {
            String key = inv.projectUuid() != null ? inv.projectUuid() : "";
            invoicedByProject.merge(key, inv.signedGrossConsultant(), Double::sum);
            nameByProject.putIfAbsent(key, inv.projectName());
        }
        Set<String> projects = new LinkedHashSet<>(expectedByProject.keySet());
        projects.addAll(invoicedByProject.keySet());

        List<ProjectGap> gaps = new ArrayList<>();
        for (String key : projects) {
            double missing = expectedByProject.getOrDefault(key, 0d)
                    - invoicedByProject.getOrDefault(key, 0d);
            if (missing > CONSULTANT_MISSING_FLOOR_DKK) {
                String name = nameByProject.get(key);
                gaps.add(new ProjectGap(sanitize(name != null && !name.isBlank() ? name : "(uden projekt)"), missing));
            }
        }
        gaps.sort(Comparator.comparingDouble(ProjectGap::missing).reversed());
        return gaps;
    }

    private String askModel(String firstname, Framing framing,
                            List<ClientAnalysis> analyses, List<String> excludedNames) {
        ObjectNode payload = buildPayload(firstname, framing, analyses, excludedNames);
        String userMsg;
        try {
            userMsg = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("[AccountManagerBrief] Failed to serialize payload", e);
            throw new WebApplicationException("Failed to build the brief payload",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }

        String response = openAIService.askQuestionWithSchema(
                buildSystemPrompt(), userMsg, buildSchema(), "AccountManagerBrief",
                "{\"slackText\":\"\"}", invoiceStatusModel, 4096);

        if (response == null || response.isBlank() || response.equals("{}")) return null;
        try {
            JsonNode root = MAPPER.readTree(response);
            JsonNode slack = root.path("slackText");
            return slack.isMissingNode() ? null : slack.asText(null);
        } catch (Exception e) {
            log.error("[AccountManagerBrief] Failed to parse model response", e);
            return null;
        }
    }

    // --- Pure payload + prompt builders (package-private for unit tests) ---

    static ObjectNode buildPayload(String firstname, Framing framing,
                                   List<ClientAnalysis> analyses, List<String> excludedNames) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("accountManager", sanitize(firstname));
        root.put("framing", framing.name());

        ArrayNode clients = root.putArray("clients");
        for (ClientAnalysis a : analyses) {
            ObjectNode client = clients.addObject();
            client.put("name", a.name());
            ArrayNode gapMonths = client.putArray("gapMonths");
            for (GapMonth gm : a.gapMonths()) {
                ObjectNode m = gapMonths.addObject();
                m.put("month", gm.monthLabel());
                m.put("expected", Math.round(gm.expected()));
                m.put("invoiced", Math.round(gm.invoiced()));
                m.put("missing", Math.round(gm.missing()));
                ArrayNode consultants = m.putArray("consultantsMissing");
                for (ConsultantGap c : gm.consultantsMissing()) {
                    ObjectNode cn = consultants.addObject();
                    cn.put("name", c.name());
                    cn.put("registered", Math.round(c.registered()));
                    cn.put("invoiced", Math.round(c.invoiced()));
                    cn.put("missing", Math.round(c.missing()));
                }
                ArrayNode projects = m.putArray("projectGaps");
                for (ProjectGap p : gm.projectGaps()) {
                    ObjectNode pn = projects.addObject();
                    pn.put("project", p.project());
                    pn.put("missing", Math.round(p.missing()));
                }
            }
            ArrayNode overMonths = client.putArray("overMonths");
            for (OverMonth om : a.overMonths()) {
                ObjectNode o = overMonths.addObject();
                o.put("month", om.monthLabel());
                o.put("surplus", Math.round(om.surplus()));
            }
        }

        ArrayNode excluded = root.putArray("excludedSelfBilled");
        for (String name : excludedNames) excluded.add(name);
        return root;
    }

    static ObjectNode buildSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putObject("properties").putObject("slackText").put("type", "string");
        schema.putArray("required").add("slackText");
        return schema;
    }

    private static String buildSystemPrompt() {
        return """
                Du er en hjælpsom kollega i et dansk IT-konsulenthus. Du skriver en kort, venlig og
                kollegial Slack-besked til en kundeansvarlig (account manager) om udeståender vedrørende
                fakturering hos deres kunder. Beskeden skal kunne kopieres direkte ind i Slack.

                Format:
                - Start (framing=TO_AM) med en kort venlig intro: "Hej {accountManager}." efterfulgt af en
                  linje der beder om en status pr. punkt.
                - Start (framing=SELF) UDEN "Hej"; skriv i stedet en personlig tjekliste, fx
                  "Her er dine udeståender vedrørende fakturering – skriv en kort status på hvert punkt:".
                - Én sektion pr. kunde med klientnavnet i Slack-fed: *Klientnavn:*
                - Punkter med Slack-bullet: • ...

                Tre bullet-typer:
                - Manglende konsulentfakturering i en måned: "{Konsulent} er ikke faktureret i {måned}."
                - Projekt-beløb: "{Måned} mangler {beløb} kr på {Projekt}."
                - Genkommende mønstre komprimeres til ét punkt: "Underfaktureres hver måned siden {måned},
                  mest pga {konsulent/projekt}."

                Regler:
                - Brug KUN klient-, konsulent- og projektnavne der findes i payloaden. Opdigt aldrig navne,
                  beløb eller måneder.
                - Tal formateres da-DK: punktum som tusindtalsseparator, efterfulgt af " kr" (fx "185.119 kr").
                - Brug feltet overMonths KUN til at vurdere om et gap reelt er tidsforskudt fakturering:
                  hvis en måned mangler beløb og en nabomåned har et tilsvarende overskud, så formulér det
                  som "ser ud til at være faktureret forskudt" eller udelad punktet. Nævn ALDRIG
                  overfakturering som et selvstændigt kritikpunkt.
                - Hold beskeden kort og handlingsorienteret. Undgå gentagelser.
                - Hvis excludedSelfBilled er ikke-tom, afslut med en fodnote:
                  "Selvfakturerende kunder (…) er ikke medtaget." med navnene indsat.
                - Hvis der ingen gaps er (ingen kunder med gapMonths), returnér en kort positiv besked, fx
                  "Ingen udeståender over bagatelgrænsen 🎉".

                Returnér KUN gyldig JSON der matcher skemaet: { "slackText": "<hele beskeden>" }.
                """;
    }

    // --- Helpers ---

    private AccountManager resolveAccountManager(String uuid) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.firstname, u.lastname FROM `user` u WHERE u.uuid = :uuid
                """, Tuple.class)
                .setParameter("uuid", uuid)
                .getResultList();
        if (rows.isEmpty()) {
            throw new BadRequestException("Unknown account manager: " + uuid);
        }
        Tuple r = rows.get(0);
        return new AccountManager((String) r.get("firstname"), (String) r.get("lastname"));
    }

    private static Framing parseFraming(String raw) {
        if (raw == null || raw.isBlank()) return Framing.TO_AM;
        try {
            return Framing.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("framing must be TO_AM or SELF");
        }
    }

    private static YearMonth parseEnd(String raw) {
        if (raw == null || raw.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(raw.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        } catch (Exception e) {
            throw new BadRequestException("end must be in YYYYMM format");
        }
    }

    /** Danish long month label, e.g. "januar 2026". */
    static String monthLabel(String monthKey) {
        int year = Integer.parseInt(monthKey.substring(0, 4));
        int month = Integer.parseInt(monthKey.substring(4, 6));
        String name = java.time.Month.of(month).getDisplayName(TextStyle.FULL, DA);
        return name + " " + year;
    }

    /** Strip HTML tags + control chars and cap length, mirroring the prompt-safety precedent. */
    static String sanitize(String raw) {
        if (raw == null) return "";
        String stripped = raw.replaceAll("<[^>]*>", "").replaceAll("[\\p{Cntrl}]", " ").strip();
        return stripped.length() > MAX_NAME_LENGTH ? stripped.substring(0, MAX_NAME_LENGTH) : stripped;
    }

    private record AccountManager(String firstname, String lastname) {
        String displayName() {
            String f = firstname != null ? firstname : "";
            String l = lastname != null ? lastname : "";
            return (f + " " + l).strip();
        }
    }
}
