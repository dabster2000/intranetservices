package dk.trustworks.intranet.recruitmentservice.application;

import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit tests for {@link CvFileExtractor}.
 *
 * <p>Deliberately not annotated with {@code @QuarkusTest}: the SUT has no injected dependencies,
 * so spinning up the full Quarkus runtime would only slow the test down (and risks sandbox
 * blocking on environments without DB access). This mirrors {@code InputDigestCalculatorTest}
 * in the {@code domain.ai} package.</p>
 */
class CvFileExtractorTest {

    private final CvFileExtractor extractor = new CvFileExtractor();

    @Test
    void extractsTextFromPdf() throws Exception {
        byte[] pdf = readFixture("recruitment/cv-fixtures/sample-consultant-cv.pdf");
        String text = extractor.extract(pdf, "sample-consultant-cv.pdf");
        assertNotNull(text);
        assertTrue(text.contains("Alice Example"), "expected fixture name in extracted text");
        assertTrue(text.contains("Acme Consulting"), "expected fictional company in extracted text");
        assertTrue(text.length() > 500, "non-trivial extraction expected (>500 chars)");
    }

    @Test
    void extractsTextFromDocx() throws Exception {
        byte[] docx = readFixture("recruitment/cv-fixtures/sample-consultant-cv.docx");
        String text = extractor.extract(docx, "sample-consultant-cv.docx");
        assertNotNull(text);
        assertTrue(text.contains("Alice Example"), "expected fixture name in extracted DOCX text");
    }

    @Test
    void rejectsUnknownExtension() {
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(new byte[]{1, 2, 3}, "evil.exe"));
    }

    @Test
    void rejectsTooLargeFile() {
        byte[] huge = new byte[15 * 1024 * 1024];  // 15 MB — over the 10 MB cap
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(huge, "huge.pdf"));
    }

    private static byte[] readFixture(String classpathPath) throws Exception {
        try (var in = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathPath),
                "fixture not on classpath: " + classpathPath)) {
            return in.readAllBytes();
        }
    }
}
