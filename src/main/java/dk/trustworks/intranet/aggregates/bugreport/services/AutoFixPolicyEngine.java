package dk.trustworks.intranet.aggregates.bugreport.services;

import dk.trustworks.intranet.aggregates.bugreport.dto.PolicyDecision;
import dk.trustworks.intranet.aggregates.bugreport.entities.BugReport;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic policy engine for the auto-fix pipeline.
 * Classifies bug reports against security rules in priority order.
 * All decisions are rule-based -- no LLM calls.
 *
 * <p>Rule priority (highest first):
 * <ol>
 *   <li>C: Prompt injection / policy override</li>
 *   <li>E: Security vulnerability signal</li>
 *   <li>A: Access expansion request</li>
 *   <li>B: Data exposure request</li>
 *   <li>F: Entitlement consistency (user_roles vs page_url)</li>
 *   <li>D: Feature request detection (with previously_worked signal)</li>
 *   <li>Restricted domain check (admin/settings pages)</li>
 *   <li>G: Regression signal and risk scoring</li>
 * </ol>
 *
 * @see <a href="docs/specs/secure_ai_bug_fix_spec.md">Security Policy Reference</a>
 */
@JBossLog
@ApplicationScoped
public class AutoFixPolicyEngine {

    // --- Rule C: Prompt injection patterns ---
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
        Pattern.compile("(?i)ignore\\s+(all\\s+)?your\\s+rules"),
        Pattern.compile("(?i)developer\\s+mode"),
        Pattern.compile("(?i)act\\s+as\\s+(the\\s+)?(developer|admin|system)"),
        Pattern.compile("(?i)reveal\\s+(the\\s+)?(system\\s+)?prompt"),
        Pattern.compile("(?i)show\\s+(me\\s+)?(your|the)\\s+instructions"),
        Pattern.compile("(?i)override\\s+policy"),
        Pattern.compile("(?i)bypass\\s+restrictions"),
        Pattern.compile("(?i)skip\\s+validation"),
        Pattern.compile("(?i)you\\s+are\\s+now"),
        Pattern.compile("(?i)pretend\\s+you\\s+are"),
        Pattern.compile("(?i)your\\s+new\\s+role\\s+is"),
        Pattern.compile("(?i)disregard\\s+(all\\s+)?safety"),
        Pattern.compile("(?i)remove\\s+(the\\s+)?@?RolesAllowed"),
        Pattern.compile("(?i)remove\\s+(the\\s+)?(scope|role|permission)\\s+check"),
        Pattern.compile("(?i)disable\\s+UserScopeResponseFilter")
    );

    // --- Rule A: Access expansion indicators ---
    private static final List<Pattern> ACCESS_PATTERNS = List.of(
        Pattern.compile("(?i)remove\\s+(the\\s+)?restriction"),
        Pattern.compile("(?i)remove\\s+(the\\s+)?(scope|role|permission)"),
        Pattern.compile("(?i)grant\\s+(me\\s+)?access"),
        Pattern.compile("(?i)I\\s+should\\s+(be\\s+able\\s+to\\s+)?see\\s+all"),
        Pattern.compile("(?i)admin\\s+dashboard"),
        Pattern.compile("(?i)admin\\s+panel"),
        Pattern.compile("(?i)give\\s+me\\s+(admin|full)\\s+access")
    );

    // --- Rule B: Data exposure indicators ---
    private static final List<Pattern> DATA_PATTERNS = List.of(
        Pattern.compile("(?i)raw\\s+database\\s+record"),
        Pattern.compile("(?i)show\\s+me\\s+the\\s+raw"),
        Pattern.compile("(?i)export\\s+all"),
        Pattern.compile("(?i)download\\s+all"),
        Pattern.compile("(?i)(show|display|reveal)\\s+(the\\s+)?(salary|salaries|CPR|bank\\s*info|pension)")
    );

    // --- Rule E: Security vulnerability indicators ---
    private static final List<Pattern> SECURITY_PATTERNS = List.of(
        Pattern.compile("(?i)see\\s+another\\s+(employee|user|person)'?s"),
        Pattern.compile("(?i)access\\s+(the\\s+)?endpoint\\s+without"),
        Pattern.compile("(?i)unauthorized\\s+(access|data|visibility)"),
        Pattern.compile("(?i)broken\\s+authorization"),
        Pattern.compile("(?i)UserScopeResponseFilter\\s+(is\\s+)?not\\s+(working|stripping)")
    );

    // --- Restricted domain URL prefixes ---
    private static final Set<String> RESTRICTED_URL_PREFIXES = Set.of(
        "/admin", "/settings"
    );

    /**
     * Evaluate a bug report against all policy rules.
     * Rules are checked in priority order (highest threat first).
     */
    public PolicyDecision evaluate(BugReport report) {
        String allText = collectSearchableText(report);

        // Rule C: Prompt injection (highest priority)
        List<String> injectionMatches = findMatches(allText, INJECTION_PATTERNS);
        if (!injectionMatches.isEmpty()) {
            log.warnf("Policy engine: prompt injection detected in bug report %s: %s",
                report.getUuid(), injectionMatches);
            return PolicyDecision.reject(
                "prompt_injection_or_policy_override", 0.95,
                List.of("Report contains prompt injection or policy override language"),
                List.of("prompt_injection_detected")
            );
        }

        // Rule E: Security vulnerability signal
        List<String> securityMatches = findMatches(allText, SECURITY_PATTERNS);
        if (!securityMatches.isEmpty()) {
            return PolicyDecision.reject(
                "security_vulnerability_report", 0.85,
                List.of("Report suggests a security or authorization issue"),
                List.of("security_vulnerability_signal")
            );
        }

        // Rule A: Access expansion
        List<String> accessMatches = findMatches(allText, ACCESS_PATTERNS);
        if (!accessMatches.isEmpty()) {
            return PolicyDecision.reject(
                "access_request", 0.80,
                List.of("Report requests broader access or scope changes"),
                List.of("access_expansion_request")
            );
        }

        // Rule B: Data exposure
        List<String> dataMatches = findMatches(allText, DATA_PATTERNS);
        if (!dataMatches.isEmpty()) {
            return PolicyDecision.reject(
                "data_request", 0.75,
                List.of("Report requests raw data access or bulk export"),
                List.of("data_exposure_request")
            );
        }

        // Rule F: Entitlement consistency check
        PolicyDecision entitlementCheck = checkEntitlementConsistency(report);
        if (entitlementCheck != null) {
            return entitlementCheck;
        }

        // Rule D: Feature request detection
        PolicyDecision featureCheck = checkFeatureRequest(report);
        if (featureCheck != null) {
            return featureCheck;
        }

        // Restricted domain check (admin/settings pages get elevated risk)
        if (isRestrictedDomain(report.getPageUrl())) {
            return PolicyDecision.reject(
                "needs_human_triage", 0.60,
                List.of("Bug is in a restricted domain (admin/settings). Auto-fix requires additional review."),
                List.of("restricted_domain")
            );
        }

        // Rule G: Regression signal (positive)
        double riskScore = 0.15;
        List<String> reasons = new ArrayList<>();
        reasons.add("Report passed all policy checks");

        if (report.getPreviouslyWorked() != null && report.getPreviouslyWorked()) {
            riskScore = 0.10;
            reasons.add("Regression signal: employee confirms this previously worked");
        }

        // Check AI triage assessment for risk elevation
        if (report.getAiRawResponse() != null) {
            if (report.getAiRawResponse().contains("POSSIBLY_EXPECTED")) {
                riskScore = Math.max(riskScore, 0.45);
                reasons.add("AI triage assessed as POSSIBLY_EXPECTED — elevated risk");
            } else if (report.getAiRawResponse().contains("UNCERTAIN")) {
                riskScore = Math.max(riskScore, 0.35);
                reasons.add("AI triage assessed as UNCERTAIN — moderate risk");
            }
        }

        PolicyDecision decision = PolicyDecision.approve(String.join("; ", reasons));
        decision.setRiskScore(riskScore);
        return decision;
    }

    // --- Helper methods ---

    String collectSearchableText(BugReport report) {
        var sb = new StringBuilder();
        appendIfPresent(sb, report.getTitle());
        appendIfPresent(sb, report.getDescription());
        appendIfPresent(sb, report.getStepsToReproduce());
        appendIfPresent(sb, report.getExpectedBehavior());
        appendIfPresent(sb, report.getActualBehavior());
        appendIfPresent(sb, report.getConsoleErrors());
        appendIfPresent(sb, report.getLogExcerpt());
        appendIfPresent(sb, report.getAiRawResponse());
        return normalizeText(sb.toString());
    }

    /**
     * Normalize text before pattern matching to catch encoded injection attempts.
     * <ul>
     *   <li>URL-decode %xx sequences</li>
     *   <li>Unicode NFKD normalization (homoglyph detection)</li>
     *   <li>Base64 segment detection and decoding</li>
     * </ul>
     */
    String normalizeText(String text) {
        if (text == null) return "";
        String normalized = text;

        // URL-decode %xx sequences
        try {
            normalized = URLDecoder.decode(normalized, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // If decoding fails (e.g., malformed sequences), continue with original
        }

        // Normalize Unicode confusables (Cyrillic a -> Latin a, etc.)
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD);

        // Base64 detection: look for base64-encoded segments and decode them
        Matcher b64Matcher = Pattern.compile("[A-Za-z0-9+/]{20,}={0,2}").matcher(normalized);
        var decoded = new StringBuilder(normalized);
        while (b64Matcher.find()) {
            try {
                String segment = b64Matcher.group();
                String decodedSegment = new String(
                    Base64.getDecoder().decode(segment), StandardCharsets.UTF_8);
                if (decodedSegment.matches(".*[a-zA-Z]{3,}.*")) {
                    decoded.append(" ").append(decodedSegment);
                }
            } catch (Exception ignored) {
                // Not valid base64 -- skip
            }
        }

        return decoded.toString();
    }

    private void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(value).append(" ");
        }
    }

    private List<String> findMatches(String text, List<Pattern> patterns) {
        List<String> matches = new ArrayList<>();
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                matches.add(p.pattern());
            }
        }
        return matches;
    }

    /**
     * Rule F: Check if the employee's scopes are consistent with the page they're reporting on.
     */
    private PolicyDecision checkEntitlementConsistency(BugReport report) {
        String pageUrl = report.getPageUrl();
        String userRoles = report.getUserRoles();

        if (pageUrl == null || userRoles == null) {
            return null;
        }

        Set<String> scopes = parseScopes(userRoles);

        // Admin pages require admin:* scope
        if (pageUrl.startsWith("/admin") && !hasMatchingScope(scopes, "admin:")) {
            return PolicyDecision.reject(
                "access_request", 0.85,
                List.of("Employee reports bug on admin page but lacks admin scope"),
                List.of("entitlement_mismatch")
            );
        }

        // Salary pages require salary:read scope
        if (pageUrl.contains("/salar") && !hasMatchingScope(scopes, "salary:")) {
            return PolicyDecision.reject(
                "access_request", 0.85,
                List.of("Employee reports bug on salary page but lacks salary scope"),
                List.of("entitlement_mismatch")
            );
        }

        // CXO pages require cxo:read scope
        if (pageUrl.contains("/cxo") && !hasMatchingScope(scopes, "cxo:")) {
            return PolicyDecision.reject(
                "access_request", 0.85,
                List.of("Employee reports bug on CXO page but lacks CXO scope"),
                List.of("entitlement_mismatch")
            );
        }

        return null;
    }

    /**
     * Parse user_roles as a JSON array of scope strings.
     * Falls back to treating the raw string as comma-separated if parsing fails.
     */
    Set<String> parseScopes(String userRoles) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var arr = mapper.readValue(userRoles, String[].class);
            return Set.of(arr);
        } catch (Exception e) {
            return Set.of(userRoles.replaceAll("[\\[\\]\"]", "").split(","));
        }
    }

    private boolean hasMatchingScope(Set<String> scopes, String prefix) {
        return scopes.stream().anyMatch(s ->
            s.trim().startsWith(prefix) || s.trim().equals("admin:*"));
    }

    /**
     * Rule D: Detect feature requests disguised as bugs.
     * If previously_worked is false/null AND expected behavior uses feature-request
     * language, it is likely a feature request.
     */
    private PolicyDecision checkFeatureRequest(BugReport report) {
        String expected = report.getExpectedBehavior();
        if (expected == null) return null;

        boolean previouslyWorked = report.getPreviouslyWorked() != null && report.getPreviouslyWorked();
        if (previouslyWorked) return null;

        String lower = expected.toLowerCase();
        boolean hasFeatureLanguage =
            lower.contains("should allow") ||
            lower.contains("should support") ||
            lower.contains("should enable") ||
            lower.contains("should be able to") ||
            lower.contains("would be nice") ||
            lower.contains("add a feature") ||
            lower.contains("add the ability");

        if (hasFeatureLanguage) {
            return PolicyDecision.reject(
                "feature_request", 0.70,
                List.of("Expected behavior uses feature-request language and previously_worked is not confirmed"),
                List.of("possible_feature_request")
            );
        }

        return null;
    }

    private boolean isRestrictedDomain(String pageUrl) {
        if (pageUrl == null) return false;
        return RESTRICTED_URL_PREFIXES.stream().anyMatch(pageUrl::startsWith);
    }
}
