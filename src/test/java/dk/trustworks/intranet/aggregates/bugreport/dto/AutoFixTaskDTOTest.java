package dk.trustworks.intranet.aggregates.bugreport.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AutoFixTaskDTO computed getters.
 * These verify that the JSON parsing from result and usage_info columns
 * produces the correct values for the frontend.
 */
class AutoFixTaskDTOTest {

    // --- costUsd ---

    @Test
    void getCostUsd_withCostUsdField_returnsCost() {
        var dto = new AutoFixTaskDTO();
        dto.setUsageInfo("""
            {"model": "claude-sonnet-4-20250514", "cost_usd": 0.42, "num_turns": 5}
            """);
        assertEquals(0, new BigDecimal("0.42").compareTo(dto.getCostUsd()));
    }

    @Test
    void getCostUsd_withLegacyCostField_returnsCost() {
        var dto = new AutoFixTaskDTO();
        dto.setUsageInfo("""
            {"cost": 0.35}
            """);
        assertEquals(0, new BigDecimal("0.35").compareTo(dto.getCostUsd()));
    }

    @Test
    void getCostUsd_withLegacyTotalCostField_returnsCost() {
        var dto = new AutoFixTaskDTO();
        dto.setUsageInfo("""
            {"total_cost": 1.20}
            """);
        assertEquals(0, new BigDecimal("1.20").compareTo(dto.getCostUsd()));
    }

    @Test
    void getCostUsd_nullUsageInfo_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setUsageInfo(null);
        assertNull(dto.getCostUsd());
    }

    @Test
    void getCostUsd_malformedJson_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setUsageInfo("not json");
        assertNull(dto.getCostUsd());
    }

    // --- verdict ---

    @Test
    void getVerdict_withDiffScanner_returnsDecision() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {
              "claude_output": "...",
              "diff_scanner": {
                "decision": "approve_candidate",
                "risk_score": 0.1,
                "files_changed": 2
              }
            }
            """);
        assertEquals("approve_candidate", dto.getVerdict());
    }

    @Test
    void getVerdict_noDiffScanner_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"claude_output": "...", "root_cause": "..."}
            """);
        assertNull(dto.getVerdict());
    }

    @Test
    void getVerdict_nullResult_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setResult(null);
        assertNull(dto.getVerdict());
    }

    // --- resultSummary (maps to diagnosis field in result JSON) ---

    @Test
    void getResultSummary_withDiagnosis_returnsSummary() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"diagnosis": "Fixed missing null check in UserService.getUser()"}
            """);
        assertEquals("Fixed missing null check in UserService.getUser()", dto.getResultSummary());
    }

    @Test
    void getResultSummary_noDiagnosis_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"root_cause": "..."}
            """);
        assertNull(dto.getResultSummary());
    }

    // --- diagnosis (maps to root_cause field in result JSON) ---

    @Test
    void getDiagnosis_withRootCause_returnsValue() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"root_cause": "NullPointerException in UserService line 42"}
            """);
        assertEquals("NullPointerException in UserService line 42", dto.getDiagnosis());
    }

    // --- filesChanged ---

    @Test
    void getFilesChanged_withDiffScanner_returnsCount() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {
              "diff_scanner": {
                "decision": "approve_candidate",
                "files_changed": 3,
                "lines_added": 15,
                "lines_removed": 5
              }
            }
            """);
        assertEquals(3, dto.getFilesChanged());
    }

    @Test
    void getFilesChanged_noDiffScanner_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"root_cause": "..."}
            """);
        assertNull(dto.getFilesChanged());
    }

    // --- filesExamined ---

    @Test
    void getFilesExamined_withList_returnsFiles() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"files_examined": ["src/App.tsx", "src/utils.ts"]}
            """);
        assertEquals(List.of("src/App.tsx", "src/utils.ts"), dto.getFilesExamined());
    }

    @Test
    void getFilesExamined_noField_returnsEmptyList() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"root_cause": "..."}
            """);
        assertEquals(List.of(), dto.getFilesExamined());
    }

    // --- flaggedPatterns (nested in diff_scanner) ---

    @Test
    void getFlaggedPatterns_nestedInDiffScanner_returnsPatterns() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {
              "diff_scanner": {
                "decision": "manual_review_required",
                "flagged_patterns": ["auth_change", "scope_modification"]
              }
            }
            """);
        assertEquals(List.of("auth_change", "scope_modification"), dto.getFlaggedPatterns());
    }

    @Test
    void getFlaggedPatterns_topLevel_returnsPatterns() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"flagged_patterns": ["auth_change"]}
            """);
        assertEquals(List.of("auth_change"), dto.getFlaggedPatterns());
    }

    @Test
    void getFlaggedPatterns_noPatterns_returnsEmptyList() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"root_cause": "..."}
            """);
        assertEquals(List.of(), dto.getFlaggedPatterns());
    }

    // --- confidence ---

    @Test
    void getConfidence_returnsValue() {
        var dto = new AutoFixTaskDTO();
        dto.setResult("""
            {"confidence": "high"}
            """);
        assertEquals("high", dto.getConfidence());
    }

    // --- branchName and prNumber (direct fields, not computed) ---

    @Test
    void branchNameAndPrNumber_directFields() {
        var dto = new AutoFixTaskDTO();
        dto.setBranchName("autofix/bug-abc12345");
        dto.setPrNumber(42);
        assertEquals("autofix/bug-abc12345", dto.getBranchName());
        assertEquals(42, dto.getPrNumber());
    }

    // --- Multi-repo PR computed getters ---

    @Test
    void getPrUrls_withMultiRepoJson_returnsMap() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl("""
            {
              "trustworks-intranet-v3": {"pr_url": "https://github.com/trustworksdk/trustworks-intranet-v3/pull/123", "pr_number": 123},
              "intranetservices": {"pr_url": "https://github.com/dabster2000/intranetservices/pull/456", "pr_number": 456}
            }
            """);
        Map<String, Object> urls = dto.getPrUrls();
        assertNotNull(urls);
        assertEquals(2, urls.size());
        assertTrue(urls.containsKey("trustworks-intranet-v3"));
        assertTrue(urls.containsKey("intranetservices"));
    }

    @Test
    void getPrUrls_withPlainUrl_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl("https://github.com/trustworksdk/trustworks-intranet-v3/pull/123");
        assertNull(dto.getPrUrls());
    }

    @Test
    void getPrUrls_withNull_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl(null);
        assertNull(dto.getPrUrls());
    }

    @Test
    void getPrNumbers_withMultiRepoJson_returnsMap() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl("""
            {
              "trustworks-intranet-v3": {"pr_url": "https://github.com/trustworksdk/trustworks-intranet-v3/pull/123", "pr_number": 123},
              "intranetservices": {"pr_url": "https://github.com/dabster2000/intranetservices/pull/456", "pr_number": 456}
            }
            """);
        Map<String, Integer> numbers = dto.getPrNumbers();
        assertNotNull(numbers);
        assertEquals(123, numbers.get("trustworks-intranet-v3"));
        assertEquals(456, numbers.get("intranetservices"));
    }

    @Test
    void getPrNumbers_withPlainUrl_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl("https://github.com/trustworksdk/trustworks-intranet-v3/pull/123");
        assertNull(dto.getPrNumbers());
    }

    @Test
    void isMultiRepo_withJsonPrUrl_returnsTrue() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl("""
            {"trustworks-intranet-v3": {"pr_url": "...", "pr_number": 1}}
            """);
        assertTrue(dto.isMultiRepo());
    }

    @Test
    void isMultiRepo_withPlainUrl_returnsFalse() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl("https://github.com/foo/bar/pull/1");
        assertFalse(dto.isMultiRepo());
    }

    @Test
    void isMultiRepo_withNull_returnsFalse() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl(null);
        assertFalse(dto.isMultiRepo());
    }

    @Test
    void getPrUrls_withMalformedJson_returnsNull() {
        var dto = new AutoFixTaskDTO();
        dto.setPrUrl("{not valid json");
        assertNull(dto.getPrUrls());
    }
}
