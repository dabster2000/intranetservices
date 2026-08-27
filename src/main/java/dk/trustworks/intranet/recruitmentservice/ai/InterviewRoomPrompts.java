package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt and schema builders for the Interview Room's AI features (room
 * spec 2026-08-26 §9). The binding rule rides in every system prompt:
 * <em>AI may reorganise, expand and cross-check a human's words. It may
 * never produce evidence, a score, or a recommendation.</em> Model output
 * is untrusted either way — {@code InterviewRoomAiService} validates
 * every field against the vocabulary / subject set and silently drops
 * anything outside it.
 */
final class InterviewRoomPrompts {

    static final String PROMPT_VERSION = "room-v1";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private InterviewRoomPrompts() {
    }

    // ------------------------------------------------------------------
    // Fact extraction (§5.4)
    // ------------------------------------------------------------------

    static String extractionSystem() {
        return """
                You extract HIRING FACTS from an interviewer's live notes, written in a mix of
                Danish and English shorthand. You only report what the notes literally say —
                you never infer, never assess the candidate, and never invent a value.
                Allowed fact fields (use these keys exactly): %s.
                For each fact found, quote the exact note fragment it rests on as `evidence`.
                Dates: resolve relative dates against today's date given in the input, ISO
                format (yyyy-MM-dd) where the note pins a date, otherwise keep the note's own
                wording. If nothing in the notes states a fact, return an empty list. Never
                report family, health, partner, pregnancy, politics, religion or age — those
                are deliberately not fields and must not be shoehorned into one.
                Answer with exactly one JSON document matching the schema.""".formatted(
                String.join(", ", RecruitmentFactVocabulary.keys()));
    }

    static String extractionUser(LocalDate today, List<String> lines) {
        return "Today: " + today + "\nNotes:\n" + lines.stream()
                .map(line -> "- " + line)
                .collect(Collectors.joining("\n"));
    }

    static ObjectNode extractionSchema() {
        ObjectNode suggestion = F.objectNode();
        suggestion.put("type", "object");
        suggestion.put("additionalProperties", false);
        ObjectNode props = suggestion.putObject("properties");
        props.putObject("field").put("type", "string");
        props.putObject("value").put("type", "string");
        props.putObject("evidence").put("type", "string");
        suggestion.putArray("required").add("field").add("value").add("evidence");

        ObjectNode root = F.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode rootProps = root.putObject("properties");
        ObjectNode suggestions = rootProps.putObject("suggestions");
        suggestions.put("type", "array");
        suggestions.set("items", suggestion);
        root.putArray("required").add("suggestions");
        return root;
    }

    // ------------------------------------------------------------------
    // Tidy + alignment (§9)
    // ------------------------------------------------------------------

    static String tidySystem(boolean alignment) {
        String base = """
                You tidy an interviewer's shorthand notes into short, plain prose, per
                scorecard subject. Rules, all binding:
                - Use ONLY the interviewer's own observations. Never add an assessment,
                  a score, a recommendation, or any fact not present in the lines.
                - Keep lines marked verbatim EXACTLY as written, in quotation marks.
                - Only produce prose for subjects that appear in the input. A subject with
                  no lines gets nothing — you must not fill a gap.
                - Keep the language the notes are written in (Danish stays Danish).
                Answer with exactly one JSON document matching the schema.""";
        if (!alignment) {
            return base;
        }
        return base + """

                Additionally list alignment observations: cases where a subject's lines
                describe something the rubric files under a DIFFERENT subject (for example
                technical depth tagged as CULTURE reads as FAGLIGHED). Observations only —
                never a proposed score, never advice on what to score.""";
    }

    static String tidyUser(List<String> taggedLines) {
        return String.join("\n", taggedLines);
    }

    static ObjectNode tidySchema() {
        ObjectNode subject = F.objectNode();
        subject.put("type", "object");
        subject.put("additionalProperties", false);
        ObjectNode subjectProps = subject.putObject("properties");
        subjectProps.putObject("subjectCode").put("type", "string");
        subjectProps.putObject("prose").put("type", "string");
        subject.putArray("required").add("subjectCode").add("prose");

        ObjectNode root = F.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode rootProps = root.putObject("properties");
        ObjectNode subjects = rootProps.putObject("subjects");
        subjects.put("type", "array");
        subjects.set("items", subject);
        ObjectNode notes = rootProps.putObject("alignmentNotes");
        notes.put("type", "array");
        notes.putObject("items").put("type", "string");
        root.putArray("required").add("subjects").add("alignmentNotes");
        return root;
    }

    // ------------------------------------------------------------------
    // Prep pack (§9)
    // ------------------------------------------------------------------

    static String prepSystem() {
        return """
                You specialise interview probes to one candidate. You are given the standard
                probes per scorecard subject and the candidate's material (CV extract, form
                answers). For each subject, rewrite two or three probes so they anchor in
                THIS candidate's concrete experience. Produce QUESTIONS ONLY — every entry
                must end with a question mark. Never a conclusion, never an assessment,
                never advice on what to score. Danish is fine where the material is Danish.
                Answer with exactly one JSON document matching the schema.""";
    }

    static ObjectNode prepSchema() {
        ObjectNode subject = F.objectNode();
        subject.put("type", "object");
        subject.put("additionalProperties", false);
        ObjectNode subjectProps = subject.putObject("properties");
        subjectProps.putObject("subjectCode").put("type", "string");
        ObjectNode questions = subjectProps.putObject("questions");
        questions.put("type", "array");
        questions.putObject("items").put("type", "string");
        subject.putArray("required").add("subjectCode").add("questions");

        ObjectNode root = F.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode rootProps = root.putObject("properties");
        ObjectNode probes = rootProps.putObject("probes");
        probes.put("type", "array");
        probes.set("items", subject);
        root.putArray("required").add("probes");
        return root;
    }
}
