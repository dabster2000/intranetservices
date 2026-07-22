package dk.trustworks.intranet.recruitmentservice.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 DoD: "same LinkedIn slug with URL variants → match". The dedupe check
 * compares normalized /in/ slugs — every real-world paste variant of the
 * same profile must extract to the same slug.
 */
class LinkedInUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.linkedin.com/in/jane-doe-1b2c3d",
            "http://linkedin.com/in/jane-doe-1b2c3d",
            "www.linkedin.com/in/jane-doe-1b2c3d/",
            "linkedin.com/in/jane-doe-1b2c3d?utm_source=share&trk=profile",
            "https://dk.linkedin.com/in/jane-doe-1b2c3d",
            "https://www.linkedin.com/in/Jane-Doe-1B2C3D/",
            "jane-doe-1b2c3d"
    })
    void urlVariants_extractTheSameSlug(String variant) {
        assertEquals("jane-doe-1b2c3d", LinkedInUrls.extractSlug(variant));
    }

    @Test
    void percentEncodedSlugs_areDecoded() {
        // Danish names percent-encode in copied URLs: søren → s%C3%B8ren
        assertEquals("søren-å-hansen",
                LinkedInUrls.extractSlug("https://www.linkedin.com/in/s%C3%B8ren-%C3%A5-hansen/"));
    }

    @Test
    void nonProfileLinkedInUrls_extractNothing() {
        assertNull(LinkedInUrls.extractSlug("https://www.linkedin.com/company/trustworks"));
        assertNull(LinkedInUrls.extractSlug("https://www.linkedin.com/feed/update/urn:li:activity:123"));
    }

    @Test
    void blankAndGarbage_extractNothing() {
        assertNull(LinkedInUrls.extractSlug(null));
        assertNull(LinkedInUrls.extractSlug("   "));
        assertNull(LinkedInUrls.extractSlug("not a url at all!!"));
    }

    @Test
    void sameProfile_matchesAcrossVariants() {
        assertTrue(LinkedInUrls.sameProfile(
                "https://www.linkedin.com/in/jane-doe-1b2c3d/",
                "linkedin.com/in/JANE-DOE-1b2c3d?trk=x"));
        assertFalse(LinkedInUrls.sameProfile(
                "https://www.linkedin.com/in/jane-doe-1b2c3d",
                "https://www.linkedin.com/in/someone-else"));
        assertFalse(LinkedInUrls.sameProfile(null, null));
    }
}
