package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps a public contact form (English param names) onto a ConferenceParticipant:
 * known keys become typed columns; everything else lands in the JSON {@code fields} bag.
 * Pure (no DB / no CDI) so it is unit-testable in isolation.
 */
public final class ContactFormMapper {

    static final Set<String> CORE =
            Set.of("name", "email", "company", "title", "message", "consent", "marketing");

    private ContactFormMapper() { }

    public static ConferenceParticipant fromForm(MultivaluedMap<String, String> form) {
        ConferenceParticipant p = new ConferenceParticipant();
        p.setName(first(form, "name"));
        p.setEmail(first(form, "email"));
        p.setCompany(first(form, "company"));
        p.setTitel(first(form, "title"));
        p.setAndet(first(form, "message"));
        p.setSamtykke(isChecked(first(form, "consent")));
        p.setMarketingConsent(isChecked(first(form, "marketing")));

        Map<String, Object> bag = new LinkedHashMap<>();
        for (String key : form.keySet()) {
            if (CORE.contains(key)) continue;
            List<String> values = form.get(key);
            bag.put(key, values != null && values.size() == 1 ? values.get(0) : values);
        }
        p.setFields(bag);
        return p;
    }

    static String first(MultivaluedMap<String, String> form, String key) {
        return form.getFirst(key);
    }

    static boolean isChecked(String value) {
        return value != null
                && Set.of("on", "ja", "true", "1", "yes").contains(value.trim().toLowerCase());
    }
}
