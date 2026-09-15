package dk.trustworks.intranet.aggregates.conference.services;

import jakarta.ws.rs.NotFoundException;

import java.util.List;

/** The public presentation IDs are shared with the website's festival catalog. */
public final class ConferenceDownloadCatalog {
    public static final String CONFERENCE_UUID = "9ade6f8d-7925-416a-80fd-3548ca462a29";
    public static final List<String> PRESENTATION_IDS = List.of(
            "keynote-human-code", "test-new-black", "fart-kontrol",
            "intention-handling", "vis-byg-tilpas");

    private static final String REQUEST_PATH_PREFIX =
            "knowledge/conferences/" + CONFERENCE_UUID + "/downloads/";

    private ConferenceDownloadCatalog() {}

    public static void requireConference(String conferenceUuid) {
        if (!CONFERENCE_UUID.equals(conferenceUuid)) throw new NotFoundException();
    }

    public static void requirePresentation(String conferenceUuid, String presentationId) {
        requireConference(conferenceUuid);
        if (presentationId == null || !PRESENTATION_IDS.contains(presentationId)) throw new NotFoundException();
    }

    /** Also covers rejected IDs so invalid anonymous requests do not acquire an actor. */
    public static boolean isAnonymousRequestPath(String path) {
        if (path == null) return false;
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (!normalized.startsWith(REQUEST_PATH_PREFIX)) return false;
        String id = normalized.substring(REQUEST_PATH_PREFIX.length());
        if (id.endsWith("/")) id = id.substring(0, id.length() - 1);
        return !id.isEmpty() && !id.contains("/");
    }
}
