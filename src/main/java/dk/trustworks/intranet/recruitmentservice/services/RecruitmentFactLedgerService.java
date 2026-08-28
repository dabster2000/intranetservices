package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.FactsLedgerResponse;
import dk.trustworks.intranet.recruitmentservice.dto.FactsLedgerResponse.FactEntry;
import dk.trustworks.intranet.recruitmentservice.dto.FactsLedgerResponse.FactHistoryEntry;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.FactField;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.FactGroup;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactStates.FactNote;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactStates.Persisted;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactStates.State;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-candidate fact ledger, derived from the event stream on every
 * read (Interview Room design spec 2026-08-26 §4.3): state per vocabulary
 * field, the newest value where the viewer may read it, and the
 * append-only drift history. One candidate's stream is dozens of rows, so
 * this derives directly — the V535 projection exists for the board's
 * eighty cards, not for this surface.
 * <p>
 * Viewer scoping (spec §7.1): compensation values are withheld —
 * {@code redacted=true}, no value, no history — unless the caller passes
 * the comp tier the resource resolved. Competition facts follow the
 * candidate boundary, which the resource has already enforced. The state
 * itself (that something IS known) is never a secret within the
 * candidate's readers; the VALUE is what the comp gate protects — the
 * same split the timeline applies to salary notes.
 * <p>
 * Redaction (change request 2026-08-28): a fact-bearing note withdrawn by a
 * {@code FACT_REDACTED} event stops counting — it is excluded from the fold,
 * so the state falls back to whatever was stated before it (or UNKNOWN), and
 * it never supplies the newest value. It still appears in the history, marked
 * and without its value: a drift history that silently dropped a statement
 * would misrepresent its own gaps, and "recorded, then taken back" is exactly
 * what a later reader needs to see.
 * <p>
 * Completeness is derived, never hand-maintained (spec §4.3): a fact is
 * <em>required</em> when its key matches a placeholder in the candidate's
 * dossier shape, or when the vocabulary marks it default-required —
 * adding a placeholder to a template updates the checklist by itself.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentFactLedgerService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    @Inject
    ObjectMapper objectMapper;

    /**
     * Build the ledger for one candidate.
     *
     * @param candidate the resolved, visibility-checked candidate
     * @param compTier  whether the viewer reads compensation values
     *                  ({@code RecruitmentVisibility.isCompTierFor}, resolved
     *                  by the resource)
     */
    public FactsLedgerResponse ledger(RecruitmentCandidate candidate, boolean compTier) {
        List<RecruitmentEvent> noteEvents = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2 order by seq",
                candidate.getUuid(), RecruitmentEventType.NOTE_ADDED);
        Set<String> redactedEventIds = redactedEventIds(candidate.getUuid());

        Map<String, List<ParsedNote>> byField = new LinkedHashMap<>();
        for (RecruitmentEvent event : noteEvents) {
            ParsedNote parsed = parseNote(event, redactedEventIds);
            if (parsed != null) {
                byField.computeIfAbsent(parsed.field(), f -> new ArrayList<>()).add(parsed);
            }
        }

        Set<String> requiredKeys = requiredKeys(candidate.getUuid());
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        List<FactEntry> entries = new ArrayList<>();
        int requiredGathered = 0;
        for (FactField field : RecruitmentFactVocabulary.all()) {
            List<ParsedNote> notes = byField.getOrDefault(field.key(), List.of());
            Persisted persisted = RecruitmentFactStates.fold(notes.stream()
                    .filter(n -> !n.redacted())
                    .map(ParsedNote::note).toList()).get(field.key());
            State state = RecruitmentFactStates.effective(field, persisted, nowUtc);
            boolean required = requiredKeys.contains(field.key());
            boolean redacted = field.group() == FactGroup.COMPENSATION && !compTier;

            String value = null;
            String statedAt = null;
            List<FactHistoryEntry> history = List.of();
            if (!redacted) {
                ParsedNote newestValue = notes.stream()
                        .filter(n -> !n.note().asked() && !n.redacted())
                        .reduce((a, b) -> b).orElse(null);
                if (newestValue != null && (state == State.STATED
                        || state == State.CONFIRMED || state == State.STALE)) {
                    value = newestValue.text();
                    statedAt = newestValue.note().occurredAt().toString();
                }
                history = notes.stream()
                        .map(n -> new FactHistoryEntry(
                                n.eventId(),
                                n.note().asked() || n.redacted() ? null : n.text(),
                                n.note().occurredAt().toString(),
                                n.interviewUuid(),
                                n.note().asked() ? "ASKED" : null,
                                n.note().confirmed(),
                                n.redacted()))
                        .toList();
            }
            if (required && (state == State.STATED || state == State.CONFIRMED
                    || state == State.STALE)) {
                // A stale value still counts as gathered for the ring — the
                // ledger row itself shows the STALE state loudly.
                requiredGathered++;
            }
            entries.add(new FactEntry(field.key(), field.group().name(), field.label(),
                    field.askRole().name(), state.name(), required, value, redacted,
                    statedAt, history));
        }
        return new FactsLedgerResponse(entries, requiredKeys.size(), requiredGathered, compTier);
    }

    /**
     * The required key set: vocabulary defaults plus every field one of the
     * candidate's dossier placeholder keys resolves to (spec §4.3 —
     * "required if the position's contract template needs it").
     */
    public Set<String> requiredKeys(String candidateUuid) {
        Set<String> required = new LinkedHashSet<>();
        for (FactField field : RecruitmentFactVocabulary.all()) {
            if (field.defaultRequired()) {
                required.add(field.key());
            }
        }
        List<CandidateDossier> dossiers = CandidateDossier.list("candidateUuid", candidateUuid);
        for (CandidateDossier dossier : dossiers) {
            for (String placeholderKey : placeholderKeys(dossier.getPlaceholderValuesJson())) {
                for (FactField field : RecruitmentFactVocabulary.all()) {
                    if (RecruitmentFactVocabulary.requiredByPlaceholder(field, placeholderKey)) {
                        required.add(field.key());
                    }
                }
            }
        }
        return required;
    }

    private Set<String> placeholderKeys(String placeholderValuesJson) {
        if (placeholderValuesJson == null || placeholderValuesJson.isBlank()) {
            return Set.of();
        }
        try {
            return objectMapper.readValue(placeholderValuesJson, JSON_OBJECT).keySet();
        } catch (Exception e) {
            log.warn("Unparseable dossier placeholder shape — ignoring for fact requiredness");
            return Set.of();
        }
    }

    /**
     * Every {@code NOTE_ADDED} this candidate's stream has had withdrawn.
     * One extra indexed query per ledger read, and almost always empty —
     * cheaper than carrying a redaction flag on the note events, which would
     * mean mutating an append-only row.
     */
    private Set<String> redactedEventIds(String candidateUuid) {
        List<RecruitmentEvent> redactions = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2",
                candidateUuid, RecruitmentEventType.FACT_REDACTED);
        if (redactions.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (RecruitmentEvent redaction : redactions) {
            if (parse(redaction.getPayload()).get("redacted_event_id") instanceof String id) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** One fact-bearing NOTE_ADDED with the pieces the ledger renders. */
    private record ParsedNote(String eventId, String field, FactNote note, String text,
                              String interviewUuid, boolean redacted) {
    }

    private ParsedNote parseNote(RecruitmentEvent event, Set<String> redactedEventIds) {
        Map<String, Object> payload = parse(event.getPayload());
        String field = payload.get("field") instanceof String s ? s : null;
        if (!RecruitmentFactVocabulary.isKnown(field)) {
            return null;
        }
        Map<String, Object> pii = parse(event.getPii());
        String text = pii.get("text") instanceof String s ? s : null;
        return new ParsedNote(event.getEventId(), field,
                new FactNote(event.getSeq(), field, event.getOccurredAt(),
                        "ASKED".equals(payload.get("outcome")),
                        Boolean.TRUE.equals(payload.get("confirmed"))),
                text,
                payload.get("interview_uuid") instanceof String i ? i : null,
                redactedEventIds.contains(event.getEventId()));
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
