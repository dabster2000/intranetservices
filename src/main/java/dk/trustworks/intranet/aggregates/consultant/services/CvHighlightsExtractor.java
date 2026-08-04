package dk.trustworks.intranet.aggregates.consultant.services;

import com.fasterxml.jackson.databind.JsonNode;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, language-neutral facts derived from a consultant's CV JSON
 * ({@code cv_tool_employee_cv.cv_data_json}).
 *
 * <p>These fields are deliberately <b>not persisted</b>: they are recomputed on
 * every read (~1&nbsp;ms per consultant, CPU only) so they are always fresh and —
 * critically — available even when the AI half of the profile is {@code PENDING},
 * {@code UNAVAILABLE}, or switched off entirely. That is what guarantees the
 * dashboard card never renders a consultant with nothing but a name.
 *
 * <p>The class is <b>pure</b>: no database access, no network, no CDI collaborators.
 * It is a bean only so callers can inject it; {@code new CvHighlightsExtractor()}
 * is a perfectly valid way to use it (and is how the unit tests do).
 *
 * <p><b>Prohibited by design:</b> any "N years of experience" figure derived from CV
 * dates. Validated against 40 consultants who state their experience explicitly, the
 * best available derivation has a median absolute error of 1.9 years and only 55&nbsp;%
 * land within ±2 years. Neither this class nor any caller may compute one.
 */
@ApplicationScoped
public class CvHighlightsExtractor {

    /** The frontend truncates visually; this is only a hard upper bound on the payload. */
    static final int MAX_ROLE_TITLE_CHARS = 120;
    static final int MAX_CLIENT_NAME_CHARS = 40;
    static final int MAX_TOP_CLIENTS = 3;
    static final int MAX_INDUSTRIES = 2;
    /** Years before this are data-entry noise (placeholder dates), not project history. */
    static final int MIN_PROJECT_YEAR = 1970;

    private static final String TRUSTWORKS = "trustworks";
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern LEADING_YEAR = Pattern.compile("^(\\d{4})(?:-(\\d{2}))?");

    /**
     * Deterministic CV facts for one consultant. All list fields are non-null and
     * immutable; {@code roleTitle} and {@code firstProjectYear} are nullable.
     */
    public record CvHighlights(
            String roleTitle,
            List<String> industries,
            List<String> topClients,
            int projectCount,
            int clientCount,
            Integer firstProjectYear
    ) {
        public CvHighlights {
            industries = industries == null ? List.of() : List.copyOf(industries);
            topClients = topClients == null ? List.of() : List.copyOf(topClients);
        }

        /** Nothing known at all — used when the consultant has no CV row. */
        public static CvHighlights empty() {
            return new CvHighlights(null, List.of(), List.of(), 0, 0, null);
        }

        /** A CV row exists but carries no usable project array (the 202-byte CV shape). */
        public static CvHighlights titleOnly(String roleTitle) {
            return new CvHighlights(roleTitle, List.of(), List.of(), 0, 0, null);
        }
    }

    /** One parsed {@code projects[]} entry — only the fields this class consumes. */
    private record CvProject(String clientUuid, String clientName, boolean trustworksClient, LocalDate startDate) {
    }

    /**
     * Derives the deterministic half of a consultant profile.
     *
     * @param employeeTitle          {@code cv_tool_employee_cv.employee_title} (may be null/blank)
     * @param cvRoot                 the parsed {@code cv_data_json}; a {@code MissingNode} (or null)
     *                               is tolerated and yields a title-only result — never an exception
     * @param segmentsByClientUuid   client uuid → segment, for the industry derivation; may be empty
     * @return never null
     */
    public CvHighlights extract(String employeeTitle, JsonNode cvRoot,
                                Map<String, ClientSegment> segmentsByClientUuid) {
        String roleTitle = normalizeRoleTitle(employeeTitle);

        JsonNode projectsNode = cvRoot == null ? null : cvRoot.path("projects");
        if (projectsNode == null || !projectsNode.isArray()) {
            return CvHighlights.titleOnly(roleTitle);
        }

        List<CvProject> projects = new ArrayList<>(projectsNode.size());
        for (JsonNode node : projectsNode) {
            projects.add(toProject(node));
        }

        Map<String, String> preferredNames = preferredDisplayNames(projects);
        List<ClientTally> clients = tallyTrustworksClients(projects);

        return new CvHighlights(
                roleTitle,
                deriveIndustries(projects, segmentsByClientUuid),
                topClients(clients, preferredNames),
                projects.size(),
                clients.size(),
                firstProjectYear(projects));
    }

    // ---- roleTitle -------------------------------------------------------------

    /** Trim, collapse internal whitespace, blank ⇒ null, hard-cap at 120 chars (no ellipsis). */
    static String normalizeRoleTitle(String employeeTitle) {
        String cleaned = collapse(employeeTitle);
        if (cleaned == null) {
            return null;
        }
        return cleaned.length() > MAX_ROLE_TITLE_CHARS
                ? cleaned.substring(0, MAX_ROLE_TITLE_CHARS)
                : cleaned;
    }

    // ---- clients ---------------------------------------------------------------

    /**
     * Trim, collapse whitespace, drop blanks and drop Trustworks itself (a consultant's
     * own employer is not a client reference).
     */
    static String normalizeClientName(String rawName) {
        String cleaned = collapse(rawName);
        if (cleaned == null || cleaned.equalsIgnoreCase(TRUSTWORKS)) {
            return null;
        }
        return cleaned;
    }

    /**
     * Case-insensitive key → the variant to display. When the same client appears both
     * shouting ("PFA PENSION") and cased ("PFA Pension") anywhere in the CV, the cased
     * variant wins.
     */
    private static Map<String, String> preferredDisplayNames(List<CvProject> projects) {
        Map<String, String> preferred = new LinkedHashMap<>();
        for (CvProject project : projects) {
            String name = project.clientName();
            if (name == null) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            String current = preferred.get(key);
            if (current == null || (isShouting(current) && !isShouting(name))) {
                preferred.put(key, name);
            }
        }
        return preferred;
    }

    private static boolean isShouting(String value) {
        return value.equals(value.toUpperCase(Locale.ROOT))
                && value.chars().anyMatch(Character::isLetter);
    }

    /** Aggregated occurrences of one client across the Trustworks-delivered projects. */
    private record ClientTally(String key, String firstSeenName, int count, LocalDate latestStart) {
    }

    /**
     * Only {@code client_is_trustworks == true} projects count. 485 of 1119 project rows name a
     * former employer's client (Microsoft, Lunar Bank, IT Minds); putting those on a Trustworks
     * sales card would be a different — and unsupported — claim.
     */
    private static List<ClientTally> tallyTrustworksClients(List<CvProject> projects) {
        Map<String, ClientTally> byKey = new LinkedHashMap<>();
        for (CvProject project : projects) {
            if (!project.trustworksClient() || project.clientName() == null) {
                continue;
            }
            String key = project.clientName().toLowerCase(Locale.ROOT);
            ClientTally current = byKey.get(key);
            if (current == null) {
                byKey.put(key, new ClientTally(key, project.clientName(), 1, project.startDate()));
            } else {
                byKey.put(key, new ClientTally(key, current.firstSeenName(), current.count() + 1,
                        latest(current.latestStart(), project.startDate())));
            }
        }
        return List.copyOf(byKey.values());
    }

    private static List<String> topClients(List<ClientTally> clients, Map<String, String> preferredNames) {
        return clients.stream()
                .sorted(Comparator
                        .comparingInt(ClientTally::count).reversed()
                        .thenComparing(ClientTally::latestStart,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ClientTally::key))
                .limit(MAX_TOP_CLIENTS)
                .map(tally -> cap(preferredNames.getOrDefault(tally.key(), tally.firstSeenName()),
                        MAX_CLIENT_NAME_CHARS))
                .toList();
    }

    // ---- industries ------------------------------------------------------------

    /**
     * Tallies the client register's own {@link ClientSegment} across the CV's projects.
     * {@code OTHER} carries no sales signal and is dropped. Ties break on enum ordinal so
     * the output is stable across runs. Emits {@link ClientSegment#getDisplayName()} — already
     * English, so no translation round-trip is needed.
     */
    private static List<String> deriveIndustries(List<CvProject> projects,
                                                 Map<String, ClientSegment> segmentsByClientUuid) {
        if (segmentsByClientUuid == null || segmentsByClientUuid.isEmpty()) {
            return List.of();
        }
        Map<ClientSegment, Integer> tally = new EnumMap<>(ClientSegment.class);
        for (CvProject project : projects) {
            if (project.clientUuid() == null) {
                continue;
            }
            ClientSegment segment = segmentsByClientUuid.get(project.clientUuid());
            if (segment == null || segment == ClientSegment.OTHER) {
                continue;
            }
            tally.merge(segment, 1, Integer::sum);
        }
        return tally.entrySet().stream()
                .sorted(Map.Entry.<ClientSegment, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_INDUSTRIES)
                .map(entry -> entry.getKey().getDisplayName())
                .toList();
    }

    // ---- dates -----------------------------------------------------------------

    private static Integer firstProjectYear(List<CvProject> projects) {
        Integer earliest = null;
        for (CvProject project : projects) {
            LocalDate start = project.startDate();
            if (start == null) {
                continue;
            }
            int year = start.getYear();
            if (year < MIN_PROJECT_YEAR || year > Year.now().getValue()) {
                continue;
            }
            if (earliest == null || year < earliest) {
                earliest = year;
            }
        }
        return earliest;
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.isAfter(a) ? b : a;
    }

    /**
     * Accepts {@code YYYY-MM-DD} (the CV tool's own format) and degrades to {@code YYYY-MM}
     * and {@code YYYY}. Anything else — including Danish {@code DD-MM-YYYY} — is unparseable
     * and simply contributes nothing.
     */
    static LocalDate parseStartDate(String raw) {
        String value = collapse(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
        } catch (RuntimeException ignored) {
            // Fall through to the year/month degradation below.
        }
        Matcher matcher = LEADING_YEAR.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            if (month < 1 || month > 12) {
                month = 1;
            }
            return LocalDate.of(year, month, 1);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ---- small helpers ---------------------------------------------------------

    private static CvProject toProject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new CvProject(null, null, false, null);
        }
        String clientUuid = collapse(text(node, "client_uuid"));
        return new CvProject(
                clientUuid,
                normalizeClientName(text(node, "client_name")),
                node.path("client_is_trustworks").asBoolean(false),
                parseStartDate(text(node, "start_date")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /** Strip control chars, collapse whitespace runs, trim; blank ⇒ null. */
    private static String collapse(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = WHITESPACE_RUN
                .matcher(CONTROL_CHARS.matcher(value).replaceAll(" "))
                .replaceAll(" ")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String cap(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars).trim() : value;
    }
}
