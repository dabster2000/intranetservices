package dk.trustworks.intranet.aggregates.crm.enrichment.ai;

import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentConfig;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Draws a logo for a client when nothing usable was found online — the fallback half of
 * the nightly logo job.
 *
 * <p>The brief asked for a full AI-designed logo rather than a monogram placeholder, so
 * the prompt describes a complete mark with the company name set in it. The one thing it
 * forbids is pretending: no photorealism, no mock-ups, no third-party marks. The account
 * page says the logo was generated, so a person can replace it through the upload dialog
 * the moment the real one turns up.
 *
 * <p>Landscape 3:2 because every place a client logo renders is a wide slot
 * ({@code PhotoService.updateLogo} bounds it to 1600×800), and a square would be padded.
 */
@JBossLog
@ApplicationScoped
public class LogoGenerator {

    static final String SIZE = "1536x1024";
    static final String QUALITY = "medium";

    @Inject
    OpenAIService openAIService;

    @Inject
    ClientEnrichmentConfig config;

    /** PNG bytes, or null when the model produced nothing. */
    public byte[] generate(String name, String industryDesc, ClientSegment segment) {
        return openAIService.generateImage(prompt(name, industryDesc, segment), config.logoImageModel(), SIZE, QUALITY);
    }

    static String prompt(String name, String industryDesc, ClientSegment segment) {
        String business = industryDesc != null && !industryDesc.isBlank()
                ? industryDesc.trim()
                : describe(segment);
        return "Design a professional corporate logo for the Danish organisation \"" + name + "\", "
                + "which works in: " + business + ". "
                + "A complete, polished brand mark: a simple geometric or abstract symbol paired with the "
                + "organisation name \"" + name + "\" set in a clean modern sans-serif typeface, spelled exactly "
                + "as given. Flat vector style, two or three harmonious colours, strong contrast, generous "
                + "margins, the whole mark centred on a plain solid white background. "
                + "No photographs, no 3D rendering, no mock-ups on objects, no additional text, no slogan, "
                + "no other company's logo, no watermark.";
    }

    static String describe(ClientSegment segment) {
        if (segment == null) return "business services";
        return switch (segment) {
            case PUBLIC -> "the Danish public sector";
            case HEALTH -> "healthcare and life science";
            case FINANCIAL -> "financial services";
            case ENERGY -> "energy and utilities";
            case EDUCATION -> "education";
            case OTHER -> "business services";
        };
    }
}
