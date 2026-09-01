package dk.trustworks.intranet.agreementservice.services;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DB-free tests for the registry's document-link validation: the URL is
 * rendered as an anchor in the HR UI, so only http(s) is ever stored —
 * a javascript:/data: value from a compromised HR session must be
 * rejected at the API, not sanitized at render time.
 */
class AgreementServiceCoreTest {

    @Test
    void validateDocumentUrl_acceptsWebUrls() {
        assertEquals("https://docs.example/doc.pdf",
                AgreementService.validateDocumentUrl(" https://docs.example/doc.pdf "));
        assertEquals("http://intra.trustworks.dk/x",
                AgreementService.validateDocumentUrl("http://intra.trustworks.dk/x"));
    }

    @Test
    void validateDocumentUrl_blankIsNull() {
        assertNull(AgreementService.validateDocumentUrl(null));
        assertNull(AgreementService.validateDocumentUrl("  "));
    }

    @Test
    void validateDocumentUrl_rejectsNonWebSchemes() {
        assertThrows(WebApplicationException.class,
                () -> AgreementService.validateDocumentUrl("javascript:alert(1)"));
        assertThrows(WebApplicationException.class,
                () -> AgreementService.validateDocumentUrl("data:text/html,<script>1</script>"));
        assertThrows(WebApplicationException.class,
                () -> AgreementService.validateDocumentUrl("ftp://files.example/doc.pdf"));
    }

    @Test
    void validateDocumentUrl_rejectsOverlongValues() {
        assertThrows(WebApplicationException.class,
                () -> AgreementService.validateDocumentUrl("https://x/" + "a".repeat(1000)));
    }
}
