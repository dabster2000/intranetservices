package dk.trustworks.intranet.recruitmentservice.slack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P14 — LIKE-wildcard escaping in the Slack candidate search: user input
 * must match literally; {@code %} can never become a match-everything
 * probe against the visibility filter's scan budget.
 */
class SlackCandidateSearchEscapeTest {

    @Test
    void escapesEveryLikeMetacharacter() {
        assertEquals("100\\%", SlackCandidateSearch.escapeLike("100%"));
        assertEquals("a\\_b", SlackCandidateSearch.escapeLike("a_b"));
        assertEquals("back\\\\slash", SlackCandidateSearch.escapeLike("back\\slash"));
        assertEquals("plain", SlackCandidateSearch.escapeLike("plain"));
    }
}
