package dk.trustworks.intranet.aggregates.conference.services;

import dk.trustworks.intranet.aggregates.conference.dto.UnsubscribeFooter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Shared preparation for every SMTP path. Raw capability URLs exist only in the returned HTML. */
@ApplicationScoped
public class ConferenceMailDispatch {
    @Inject ConferenceUnsubscribeService policy;
    @ConfigProperty(name = "conference.mail.enabled", defaultValue = "false") boolean enabled;

    public boolean isEnabled() { return enabled; }

    public void requireEnabled() {
        if (!enabled) throw new jakarta.ws.rs.ServiceUnavailableException("CONFERENCE_MAIL_NOT_ENABLED");
    }

    public String prepare(String conferenceUuid, String recipient, String sourceBody, UnsubscribeFooter footer) {
        requireEnabled();
        if (conferenceUuid == null || conferenceUuid.isBlank()) throw new IllegalStateException("CONFERENCE_CONTEXT_REQUIRED");
        if (policy.isSuppressedFresh(conferenceUuid, recipient)) return null;
        // issueToken commits a NEW transaction before returning; never retain a bulk snapshot.
        UnsubscribeFooter settings = UnsubscribeFooter.orDefault(footer);
        String listName = settings.resolvedListName(policy.listName(conferenceUuid));
        String token = policy.issueToken(conferenceUuid, recipient, listName, settings.pageCopy());
        return ConferenceMailRenderer.render(sourceBody, settings, listName, policy.unsubscribeUrl(token));
    }

    /** Call directly before SMTP after attachments/headers have been prepared. */
    public boolean allowedAtDispatch(String conferenceUuid, String recipient) {
        requireEnabled();
        return !policy.isSuppressedFresh(conferenceUuid, recipient);
    }
}
