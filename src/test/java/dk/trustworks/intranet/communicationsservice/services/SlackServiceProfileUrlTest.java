package dk.trustworks.intranet.communicationsservice.services;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the "View Documents" deep link in the signed-document DM.
 *
 * <p>The link pointed at {@code /profile-view} — the {@code @Route} of the retired Vaadin v1
 * app — which has no route and no rewrite in the React app, so it 404'd for every recipient in
 * production. The correct path is {@code /profile}; v1's own migration registry records the
 * mapping as {@code createFallbackPage("profile", …, "/profile", "profile-view", …)}.
 *
 * <p>DB-free: the URL builder is pure string work, so no Quarkus boot is needed. The method is
 * private, hence the reflection — the alternative (asserting on the whole Block Kit payload)
 * would need a Slack client.
 */
class SlackServiceProfileUrlTest {

    private static String profileUrlFor(String base) throws Exception {
        SlackService service = new SlackService();
        Field f = SlackService.class.getDeclaredField("applicationBaseUrl");
        f.setAccessible(true);
        f.set(service, base);

        Method m = SlackService.class.getDeclaredMethod("constructProfileUrl");
        m.setAccessible(true);
        return (String) m.invoke(service);
    }

    /** The regression itself: the retired Vaadin path must never come back. */
    @Test
    void profileUrl_usesTheReactRoute_notTheRetiredVaadinRoute() throws Exception {
        String url = profileUrlFor("https://intra.trustworks.dk");

        assertEquals("https://intra.trustworks.dk/profile", url);
        assertFalse(url.contains("profile-view"),
                "/profile-view is the retired Vaadin @Route and 404s in the React app");
    }

    /** The base URL is environment-supplied, so staging must not silently inherit a prod path shape. */
    @Test
    void profileUrl_isBuiltFromTheConfiguredBase() throws Exception {
        String staging = "https://tw-1bec5887af0d4066a2ff90910770d250.ecs.eu-west-1.on.aws";

        assertEquals(staging + "/profile", profileUrlFor(staging));
    }

    /** A trailing slash in the environment variable must not produce a double slash. */
    @Test
    void trailingSlashOnBase_doesNotDoubleUp() throws Exception {
        assertEquals("https://intra.trustworks.dk/profile",
                profileUrlFor("https://intra.trustworks.dk/"));
    }

    /**
     * Unconfigured base returns null, which the caller uses to drop the link element rather than
     * send a broken one — so null here is the contract, not a failure.
     */
    @Test
    void unconfiguredBase_yieldsNoLink() throws Exception {
        assertNull(profileUrlFor(null), "null base must suppress the link");
        assertNull(profileUrlFor(""), "empty base must suppress the link");
        assertNull(profileUrlFor("   "), "blank base must suppress the link");
    }
}
