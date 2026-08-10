package dk.trustworks.intranet.recruitmentservice.airtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves Airtable's free-text "Reference i Trustworks (navn)" to an
 * employee (P21 import; owner request 2026-08-10). Three tiers, strictly
 * in order:
 * <ol>
 *   <li><b>Exact</b> — whitespace-normalized, case-insensitive full-name
 *       equality (the directory itself contains double spaces).</li>
 *   <li><b>Token</b> — first name token + last name token, requiring
 *       exactly ONE directory hit ("Elvi Nissen" → "Elvi Rohde Nissen";
 *       middle names and maiden names stop mattering).</li>
 *   <li><b>AI extraction</b> — for prose and multi-person texts ("Morten
 *       Lund og Charlotte Ellesøe", "Thomas Løber (og Jeppe Cramon), som
 *       jeg allerede har …"): one strict-schema OpenAI call extracts the
 *       person NAMES only; each extracted name then runs through tiers
 *       1–2. The model never sees the employee directory and never picks
 *       uuids — a hallucination cannot create a link, only a name that
 *       fails to match. {@code store=false} (the P16 privacy posture);
 *       any AI failure degrades to no-link.</li>
 * </ol>
 * No link ⇒ the raw text is preserved as the external referrer (real
 * Airtable data: references are often NOT employees). Tiers 1–2 are pure
 * and fast-tier tested; the AI tier runs only during a real import (never
 * dry-runs), outside the record's transaction (the no-OpenAI-in-tx rule).
 */
@JBossLog
@ApplicationScoped
public class AirtableReferrerMatcher {

    /** How the link was made — stamped on CANDIDATE_CREATED for auditability. */
    public record Resolution(String userUuid, String matchMethod) {

        static final Resolution NONE = new Resolution(null, null);
    }

    /** Minimal directory row — decoupled from the User entity for testability. */
    public record DirectoryUser(String uuid, String firstname, String lastname) {
    }

    @Inject
    OpenAIService openAIService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.ai.extraction-model", defaultValue = "gpt-5.6-terra")
    String extractionModel;

    @ConfigProperty(name = "dk.trustworks.recruitment.ai.extraction-reasoning-effort", defaultValue = "low")
    java.util.Optional<String> extractionReasoningEffort;

    /**
     * Resolve a reference text against the directory. {@code useAi} is
     * false on paths that must stay cheap and side-effect free.
     */
    public Resolution resolve(String referenceText, List<DirectoryUser> directory, boolean useAi) {
        if (referenceText == null || referenceText.isBlank()) {
            return Resolution.NONE;
        }
        String direct = deterministicMatch(referenceText, directory);
        if (direct != null) {
            return new Resolution(direct, "name");
        }
        if (!useAi) {
            return Resolution.NONE;
        }
        for (String name : extractNames(referenceText)) {
            String match = deterministicMatch(name, directory);
            if (match != null) {
                return new Resolution(match, "ai_extraction");
            }
        }
        return Resolution.NONE;
    }

    // ------------------------------------------------------------------
    // Tiers 1–2 (pure)
    // ------------------------------------------------------------------

    /** Exact normalized full-name match, then unique first+last token match. */
    static String deterministicMatch(String name, List<DirectoryUser> directory) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) {
            return null;
        }
        List<String> exact = new ArrayList<>();
        for (DirectoryUser user : directory) {
            if (normalize(user.firstname() + " " + user.lastname()).equals(normalized)) {
                exact.add(user.uuid());
            }
        }
        if (exact.size() == 1) {
            return exact.get(0);
        }
        if (exact.size() > 1) {
            return null; // ambiguous — never guess
        }

        String[] tokens = normalized.split(" ");
        if (tokens.length < 2) {
            return null; // a single token ("Lars A") is too weak a signal
        }
        String first = tokens[0];
        String last = tokens[tokens.length - 1];
        List<String> tokenHits = new ArrayList<>();
        for (DirectoryUser user : directory) {
            String[] firstTokens = normalize(user.firstname()).split(" ");
            String[] lastTokens = normalize(user.lastname()).split(" ");
            if (firstTokens.length == 0 || lastTokens.length == 0) {
                continue;
            }
            if (firstTokens[0].equals(first) && lastTokens[lastTokens.length - 1].equals(last)) {
                tokenHits.add(user.uuid());
            }
        }
        return tokenHits.size() == 1 ? tokenHits.get(0) : null;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Tier 3 — AI name extraction (names only, never directory access)
    // ------------------------------------------------------------------

    private List<String> extractNames(String referenceText) {
        try {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            ObjectNode props = schema.putObject("properties");
            ObjectNode names = props.putObject("names");
            names.put("type", "array");
            names.putObject("items").put("type", "string");
            schema.putArray("required").add("names");

            String system = "Du udtrækker personnavne fra en dansk fritekst, hvor en jobkandidat "
                    + "beskriver sin reference/kontakt hos konsulenthuset Trustworks. Returnér KUN "
                    + "fulde personnavne som nævnes som Trustworks-medarbejdere eller kontakter — "
                    + "aldrig kandidatens eget navn, aldrig virksomhedsnavne, aldrig titler. "
                    + "Ingen navne → tomt array.";
            String json = openAIService.askQuestionWithSchema(
                    system, referenceText, schema, "referrer_names",
                    "{\"names\":[]}", extractionModel, 2048,
                    false, // store=false — PII prose never retained (P16 posture)
                    extractionReasoningEffort.filter(e -> !e.isBlank()).orElse(null));
            JsonNode parsed = objectMapper.readTree(json);
            List<String> result = new ArrayList<>();
            if (parsed.has("names") && parsed.get("names").isArray()) {
                parsed.get("names").forEach(node -> {
                    String value = node.asText("").trim();
                    if (!value.isEmpty()) {
                        result.add(value);
                    }
                });
            }
            return result;
        } catch (Exception e) {
            log.warnf("Airtable referrer AI extraction failed (%s) — keeping external referrer",
                    e.getClass().getSimpleName());
            return List.of();
        }
    }
}
