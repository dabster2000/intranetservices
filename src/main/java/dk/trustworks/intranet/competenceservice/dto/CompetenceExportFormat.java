package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.competenceservice.content.CompetenceContent;

import java.util.List;

/**
 * The JSON import/export wire format.
 *
 * <p>{@code formatVersion 1} is the shape of the original content export: no
 * {@code formatVersion} key at all, no targeting, and quiz answers as
 * {@code [["text", true], ...]} tuples. It is accepted on import and up-converted.
 * Export always emits {@code formatVersion 2}.
 *
 * <p>Unknown properties are tolerated <em>here</em> (unlike the stored payload) because a
 * v1 file legitimately carries keys v2 dropped — {@code customTopics} being the obvious
 * one. The content inside each topic is still validated strictly.
 */
public final class CompetenceExportFormat {

    public static final String KIND = "kompetencemodul-full-export";
    public static final int CURRENT_FORMAT_VERSION = 2;

    private CompetenceExportFormat() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(String kind,
                           Integer formatVersion,
                           String exportedAt,
                           List<Topic> topics) {

        /** v1 files carry no {@code formatVersion}; absent means 1. */
        public int effectiveFormatVersion() {
            return formatVersion == null ? 1 : formatVersion;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Topic(String compId,
                        String kref,
                        String name,
                        String desc,
                        List<String> targetPracticeUuids,
                        List<String> targetTeams,
                        List<String> targetUseruuids,
                        Integer cadenceDaysOverride,
                        Course course,
                        Quiz quiz) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Course(String version, List<CompetenceContent.Screen> screens) {
    }

    /**
     * The v2 quiz shape, used for <em>export</em>.
     *
     * <p>Import does not bind to this type. Both format versions call the key
     * {@code questions} while meaning different things — v1 holds {@code {q, a:[[text,
     * bool]]}} and v2 holds {@code {id, text, options:[...]}} — so a single record cannot
     * carry both without one silently winning. {@code CompetenceImportAdapter} therefore
     * walks the parsed tree and converts explicitly.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quiz(String version, List<CompetenceContent.Question> questions) {
    }
}
