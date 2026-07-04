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

    /**
     * One non-provisional month with activity. {@code gap} marks months at/beyond the 25k floor;
     * the full series (including FULL/OVER months) is sent so the model can trace month-shifted
     * invoicing per consultant and recurring patterns. {@code consultantDeviations} holds every
     * consultant whose |registered − invoiced| exceeds the noise floor (negative missing =
     * invoiced more than registered that month, typically the other side of a timing shift).
     */
    record MonthAnalysis(String monthLabel, double expected, double invoiced, double delta, boolean gap,
                         List<ConsultantGap> consultantDeviations, List<ProjectGap> projectGaps,
                         double unmatchedInvoiced) {}

    record ClientAnalysis(String name, List<MonthAnalysis> months, int gapMonthCount, double totalMissing) {}

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
        long detailFetchStart = System.currentTimeMillis();
        for (ClientStatusRow row : grid.clients()) {
            if (!clientNameByUuid.containsKey(row.clientUuid())) continue;

            // A client enters the brief only when at least one month breaches the gap floor —
            // but then its FULL month series goes along, so the model can trace timing shifts
            // and per-consultant patterns across the whole window.
            boolean hasGap = row.cells().stream()
                    .filter(c -> !provisional.contains(c.monthKey()))
                    .anyMatch(c -> c.delta() <= -GAP_FLOOR_DKK);
            if (!hasGap) continue;

            List<ClientStatusCell> activeCells = row.cells().stream()
                    .filter(c -> !provisional.contains(c.monthKey()))
                    .filter(c -> c.expected() != 0d || c.invoiced() != 0d)
                    .toList();

            // Per-consultant detail is expensive (3+ queries per month), so it is fetched only
            // where the analysis needs it: gap months, their direct neighbours (timing shifts),
            // and over-months at/beyond the floor. Other active months ride along as totals.
            Set<Integer> detailIdx = new HashSet<>();
            for (int i = 0; i < activeCells.size(); i++) {
                double delta = activeCells.get(i).delta();
                if (delta <= -GAP_FLOOR_DKK) {
                    detailIdx.add(i - 1);
                    detailIdx.add(i);
                    detailIdx.add(i + 1);
                } else if (delta >= GAP_FLOOR_DKK) {
                    detailIdx.add(i);
                }
            }

            List<MonthAnalysis> months = new ArrayList<>();
            int clientGaps = 0;
            double totalMissing = 0d;
            for (int i = 0; i < activeCells.size(); i++) {
                ClientStatusCell cell = activeCells.get(i);
                boolean gap = cell.delta() <= -GAP_FLOOR_DKK;
                if (detailIdx.contains(i)) {
                    months.add(buildMonthAnalysis(row.clientUuid(), cell, gap));
                } else {
                    months.add(new MonthAnalysis(monthLabel(cell.monthKey()), cell.expected(),
                            cell.invoiced(), cell.delta(), gap, List.of(), List.of(), 0d));
                }
                if (gap) {
                    clientGaps++;
                    totalMissing += -cell.delta();
                }
            }
            gapMonthCount += clientGaps;
            analyses.add(new ClientAnalysis(sanitize(row.clientName()), months, clientGaps, totalMissing));
        }

        log.infof("POST /invoice-controlling/client-status/account-manager-brief: am=%s clientCount=%d gapMonthCount=%d model=%s framing=%s detailBuildMs=%d",
                accountManagerUuid, clientNameByUuid.size(), gapMonthCount, invoiceStatusModel, framing,
                System.currentTimeMillis() - detailFetchStart);

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

    /**
     * Build one month with per-consultant deviations (both signs — a consultant invoiced MORE
     * than registered is the other half of a timing shift) and, for gap months, per-project
     * shortfalls. The unmatched-bucket value rides along so the model can flag invoice lines
     * that could not be tied to any consultant.
     */
    private MonthAnalysis buildMonthAnalysis(String clientUuid, ClientStatusCell cell, boolean gap) {
        int year = Integer.parseInt(cell.monthKey().substring(0, 4));
        int month = Integer.parseInt(cell.monthKey().substring(4, 6));
        ClientStatusDetailResponse detail = clientStatusService.getClientStatusDetail(clientUuid, year, month);

        List<ConsultantGap> deviations = new ArrayList<>();
        double unmatched = 0d;
        for (ClientStatusConsultantRecon rc : detail.consultantRecon()) {
            if (rc.consultantUuid() == null) {
                unmatched = rc.invoicedValue();
                continue;
            }
            if (Math.abs(rc.missingValue()) <= CONSULTANT_MISSING_FLOOR_DKK) continue;
            deviations.add(new ConsultantGap(
                    sanitize(rc.consultantName()),
                    rc.registeredValue(), rc.invoicedValue(), rc.missingValue()));
        }

        List<ProjectGap> projectGaps = gap ? buildProjectGaps(detail) : List.of();

        return new MonthAnalysis(monthLabel(cell.monthKey()), cell.expected(), cell.invoiced(),
                cell.delta(), gap, deviations, projectGaps,
                Math.abs(unmatched) > CONSULTANT_MISSING_FLOOR_DKK ? unmatched : 0d);
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
        int variationSeed = new Random().nextInt(1_000);
        ObjectNode payload = buildPayload(firstname, framing, analyses, excludedNames, variationSeed);
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
                "{\"slackText\":\"\"}", invoiceStatusModel, 8192);

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
                                   List<ClientAnalysis> analyses, List<String> excludedNames,
                                   int variationSeed) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("accountManager", sanitize(firstname));
        root.put("framing", framing.name());
        root.put("variationSeed", variationSeed);

        int gapMonths = analyses.stream().mapToInt(ClientAnalysis::gapMonthCount).sum();
        double totalMissing = analyses.stream().mapToDouble(ClientAnalysis::totalMissing).sum();
        ObjectNode stats = root.putObject("stats");
        stats.put("clientsWithGaps", analyses.size());
        stats.put("gapMonths", gapMonths);
        stats.put("totalMissingDkk", Math.round(totalMissing));

        ArrayNode clients = root.putArray("clients");
        for (ClientAnalysis a : analyses) {
            ObjectNode client = clients.addObject();
            client.put("name", a.name());
            ArrayNode months = client.putArray("months");
            for (MonthAnalysis ma : a.months()) {
                ObjectNode m = months.addObject();
                m.put("month", ma.monthLabel());
                m.put("expected", Math.round(ma.expected()));
                m.put("invoiced", Math.round(ma.invoiced()));
                m.put("delta", Math.round(ma.delta()));
                m.put("gap", ma.gap());
                if (!ma.consultantDeviations().isEmpty()) {
                    ArrayNode consultants = m.putArray("consultants");
                    for (ConsultantGap c : ma.consultantDeviations()) {
                        ObjectNode cn = consultants.addObject();
                        cn.put("name", c.name());
                        cn.put("registered", Math.round(c.registered()));
                        cn.put("invoiced", Math.round(c.invoiced()));
                        cn.put("missing", Math.round(c.missing()));
                    }
                }
                if (!ma.projectGaps().isEmpty()) {
                    ArrayNode projects = m.putArray("projectGaps");
                    for (ProjectGap p : ma.projectGaps()) {
                        ObjectNode pn = projects.addObject();
                        pn.put("project", p.project());
                        pn.put("missing", Math.round(p.missing()));
                    }
                }
                if (ma.unmatchedInvoiced() != 0d) {
                    m.put("unmatchedInvoiced", Math.round(ma.unmatchedInvoiced()));
                }
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
                Du er en hjælpsom, venlig kollega i et dansk IT-konsulenthus. Du skriver en kort,
                kollegial Slack-besked til en kundeansvarlig (account manager) om udeståender vedrørende
                fakturering hos deres kunder. Beskeden skal kunne kopieres direkte ind i Slack.

                Data: payloaden indeholder for hver kunde HELE månedsserien (kun måneder med aktivitet;
                provisoriske måneder er udeladt). Pr. måned: expected (registreret arbejdsværdi),
                invoiced, delta og gap=true når måneden mangler over bagatelgrænsen. "consultants" viser
                konsulenter hvis registrerede arbejde og fakturering afviger i den måned: missing > 0 =
                mangler fakturering; missing < 0 = faktureret mere end registreret (typisk den anden halvdel
                af en tidsforskydning). "unmatchedInvoiced" er fakturalinjer der ikke kunne knyttes til en
                konsulent. "projectGaps" viser manglende beløb pr. projekt i gap-måneder.

                Intro (varieres HVER gang — brug variationSeed til at vælge en ny formulering, kopiér ikke
                eksemplerne ordret):
                - framing=TO_AM: start med "Hej {accountManager}." efterfulgt af 1-2 venlige, afvæbnende
                  linjer med lidt kontekst, kalibreret efter stats: få gaps (fx under 6) → i stil med
                  "Du har lige nogle få udestående fakturaer – kan du skrive en kort status på punkterne?";
                  mange gaps (fx 12+) → i stil med "Listen med fakturaer der ikke er sendt ud er ved at være
                  lidt lang – skal vi ikke lige få den kortet ned sammen?". Tonen er ALDRIG bebrejdende
                  eller løftet pegefinger — vi hjælper hinanden med at få styr på det.
                - framing=SELF: ingen "Hej"; en personlig, positiv tjekliste-intro i du-form, også varieret,
                  fx "Her er dine udeståender på fakturering – en hurtig status pr. punkt gør det nemt at
                  lukke dem."

                Sektioner: én pr. kunde med klientnavnet i Slack-fed: *Klientnavn:* og punkter med • ...

                Analysen bag hvert punkt — vær SÅ specifik som muligt:
                1. Navngiv den præcise konsulent: "{Konsulent} er ikke faktureret i {måned} ({beløb} kr)"
                   eller "{Konsulent} er kun faktureret {x} kr af {y} kr i {måned}."
                2. Tjek tidsforskydning FØR du melder et gap: følg konsulenten hen over månedsserien — hvis
                   et manglende beløb i én måned modsvares af et tilsvarende overskud (missing < 0) for
                   SAMME konsulent i en nabomåned, så skriv fx "{Konsulent} ser ud til at være faktureret i
                   {anden måned} i stedet – kan du bekræfte?" eller udelad punktet hvis det klart udligner.
                   Gælder også på månedsniveau: gap + tilsvarende positiv delta i nabomåned = sandsynligvis
                   forskudt fakturering, ikke et reelt hul.
                3. Find mønstre og komprimér dem til ét punkt: en konsulent der gentagne gange er under-
                   eller ufaktureret ("{Konsulent} er ikke faktureret i 3 af de seneste 5 måneder"), eller
                   en kunde der underfaktureres måned efter måned ("Underfaktureres hver måned siden
                   {måned}, mest pga {konsulent/projekt}").
                4. Projekt-beløb hvor det er mere sigende end konsulenter: "{Måned} mangler {beløb} kr på
                   {Projekt}."
                5. Nævn gerne ANDRE problemer du kan se i data, formuleret som venlige spørgsmål: fx en
                   konsulent faktureret uden registreret arbejde, et markant unmatchedInvoiced-beløb, eller
                   en måned der ser fuld ud i totalen men hvor to konsulenter udligner hinanden.

                Regler:
                - Brug KUN klient-, konsulent- og projektnavne der findes i payloaden. Opdigt aldrig navne,
                  beløb eller måneder.
                - Tal formateres da-DK: punktum som tusindtalsseparator, efterfulgt af " kr" (fx "185.119 kr").
                - Nævn ALDRIG en måneds samlede overfakturering (delta > 0) som et selvstændigt
                  kritikpunkt — månedsoverskud bruges kun til tidsforskydningsanalysen. Anomalier på
                  konsulentniveau fra punkt 5 (fx faktureret uden registreret arbejde) må derimod gerne
                  nævnes som venlige spørgsmål.
                - Måneder uden "consultants"-liste er medtaget som totaler alene (detaljer var ikke
                  nødvendige der) — konkludér ikke ud fra fraværet af konsulentdetaljer i de måneder.
                - Slut hvert punkt handlingsorienteret hvor det er naturligt ("kan du tjekke…?", "skal der
                  sendes en faktura?").
                - Hold beskeden kort; komprimér frem for at gentage. Spring måneder uden reelle problemer over.
                - Hvis excludedSelfBilled er ikke-tom, afslut med en fodnote:
                  "Selvfakturerende kunder (…) er ikke medtaget." med navnene indsat.
                - Hvis der ingen gaps er (tom clients-liste), returnér en kort positiv besked, fx
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
