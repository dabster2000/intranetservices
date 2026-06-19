package dk.trustworks.intranet.aggregates.conference.dto;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-side view of a conference participant. `allFields` is the uniform merge of typed
 * columns and the JSON `fields` bag (typed columns authoritative). This is the single place
 * the participant "shape" is assembled for the admin UI / export.
 */
public record ParticipantView(
        String uuid,
        String participantuuid,
        String conferenceuuid,
        String name,
        String company,
        String titel,
        String email,
        String andet,
        boolean samtykke,
        boolean marketingConsent,
        ConferencePhase conferencePhase,
        LocalDateTime registered,
        Map<String, Object> fields,
        Map<String, Object> allFields) {

    public static ParticipantView from(ConferenceParticipant p) {
        Map<String, Object> bag = p.getFields() != null
                ? new LinkedHashMap<>(p.getFields())
                : new LinkedHashMap<>();

        Map<String, Object> all = new LinkedHashMap<>(bag); // bag first; typed columns overwrite below
        putIfNotNull(all, "name", p.getName());
        putIfNotNull(all, "company", p.getCompany());
        putIfNotNull(all, "title", p.getTitel());
        putIfNotNull(all, "email", p.getEmail());
        putIfNotNull(all, "message", p.getAndet());
        all.put("consent", p.isSamtykke());
        all.put("marketing", p.isMarketingConsent());

        return new ParticipantView(
                p.getUuid(), p.getParticipantuuid(), p.getConferenceuuid(),
                p.getName(), p.getCompany(), p.getTitel(), p.getEmail(), p.getAndet(),
                p.isSamtykke(), p.isMarketingConsent(),
                p.getConferencePhase(), p.getRegistered(),
                bag, all);
    }

    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }
}
