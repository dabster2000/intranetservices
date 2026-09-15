package dk.trustworks.intranet.aggregates.conference.services;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Base64;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConferenceUnsubscribeServiceTest {
    @Test
    void identityTrimsAndLowercasesWithoutMergingAliases() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("first.last+tag@example.com", ConferenceUnsubscribeService.normalizeEmail(" FIRST.Last+Tag@EXAMPLE.COM "));
            assertEquals("I@EXAMPLE.COM", ConferenceUnsubscribeService.validatedEmail(" I@EXAMPLE.COM "));
        } finally { Locale.setDefault(before); }
        assertNotEquals(ConferenceUnsubscribeService.normalizeEmail("a.b@example.com"), ConferenceUnsubscribeService.normalizeEmail("ab@example.com"));
        assertNotEquals(ConferenceUnsubscribeService.normalizeEmail("a+tag@example.com"), ConferenceUnsubscribeService.normalizeEmail("a@example.com"));
        for (String invalid : new String[]{"bad", "a..b@example.com", ".a@example.com", "a@-example.com", "a@ex..com", "a@example.com\r\nBcc: x@example.com", "<a@example.com>"}) {
            assertEquals(400, assertThrows(WebApplicationException.class, () -> ConferenceUnsubscribeService.normalizeEmail(invalid)).getResponse().getStatus());
        }
    }

    @Test
    void validatesCanonical256BitTokensBeforeDatabaseAccess() {
        DataSource dataSource = mock(DataSource.class);
        var service = new ConferenceUnsubscribeService(dataSource, "https://trustworks.dk/");
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        assertEquals(32, ConferenceUnsubscribeService.hashToken(token).length);
        assertEquals("https://trustworks.dk/unsubscribe#token=" + token, service.unsubscribeUrl(token));
        for (String invalid : new String[]{"", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "B", "A".repeat(42) + "=", "💣".repeat(100)}) {
            assertEquals(404, assertThrows(WebApplicationException.class, () -> service.status(invalid)).getResponse().getStatus());
        }
        verifyNoInteractions(dataSource);
    }

    @Test
    void publicUrlOnlyUsesConfiguredHttpsOrigin() {
        for (String invalid : new String[]{"http://trustworks.dk", "https://trustworks.dk/path", "https://trustworks.dk?redirect=other", "https://user@trustworks.dk", "https://trustworks.dk:443", "javascript:alert(1)"}) {
            assertThrows(IllegalArgumentException.class, () -> new ConferenceUnsubscribeService(mock(DataSource.class), invalid));
        }
        assertTrue(ConferenceUnsubscribeService.isUnsubscribePath("/knowledge/conferences/unsubscribe"));
        assertFalse(ConferenceUnsubscribeService.isUnsubscribePath("/knowledge/conferences/unsubscribe/admin"));
        assertEquals("m••••@example.com", ConferenceUnsubscribeService.maskEmail("mary@example.com"));
    }
}
