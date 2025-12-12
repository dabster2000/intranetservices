package dk.trustworks.intranet.utils.converter;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for Word-to-PDF conversion.
 *
 * <p>Example configuration in application.properties:
 * <pre>
 * word-converter.backend=local
 * word-converter.libreoffice.path=/usr/bin/libreoffice
 * word-converter.libreoffice.timeout-seconds=60
 * word-converter.temp-dir=/tmp/word-converter
 * word-converter.fallback-enabled=true
 * </pre>
 */
@ConfigMapping(prefix = "word-converter")
public interface WordConverterConfig {

    /**
     * Backend to use for conversion: "local" or "nextsign".
     * Default: local
     */
    @WithDefault("local")
    String backend();

    /**
     * LibreOffice configuration for local conversion.
     */
    LibreOffice libreoffice();

    /**
     * Temporary directory for conversion files.
     * Default: /tmp/word-converter
     */
    @WithDefault("/tmp/word-converter")
    String tempDir();

    /**
     * Whether to fall back to NextSign if local conversion fails.
     * Default: true
     */
    @WithDefault("true")
    boolean fallbackEnabled();

    /**
     * Maximum concurrent conversions (semaphore permits).
     * LibreOffice is memory-intensive (~200MB per process).
     * Default: 3
     */
    @WithDefault("3")
    int maxConcurrent();

    /**
     * LibreOffice-specific configuration.
     */
    interface LibreOffice {

        /**
         * Path to LibreOffice executable.
         * Default: /usr/bin/libreoffice
         */
        @WithDefault("/usr/bin/libreoffice")
        String path();

        /**
         * Timeout in seconds for LibreOffice conversion.
         * Default: 60
         */
        @WithDefault("60")
        int timeoutSeconds();
    }
}
