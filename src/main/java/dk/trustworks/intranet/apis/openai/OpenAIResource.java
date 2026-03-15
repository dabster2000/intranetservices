package dk.trustworks.intranet.apis.openai;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/openai")
@RolesAllowed({"system:write"})
public class OpenAIResource {

    @Inject
    OpenAIService openAIService;

    @POST
    @Path("/ask")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public String askQuestion(String question) {
        return openAIService.askQuestion(question);
    }

    // =========================================================================
    // Project description AI suggestions
    // Ported from Spring Boot ChatController + PromptProviders
    // =========================================================================

    public record ProjectSuggestionRequest(String promptType, String text) {}

    private static final String SITUATION_PROMPT = """
            Det er vigtigt, at indholdet bliver skarpt, korte sætninger og ikke for langt. \
            Der må ikke blive skrevet jeg i teksten. Kundens navn må kun nævnes, hvis dette er godkendt af kunden, \
            så nævnes der et virksomhedsnavn, så gør her opmærksom på, at dette skal være godkendt. \
            Her kan du også referere til, at industrien blot nævnes.

            Gode eksempler på engelsk og dansk i forhold til opbygning og antal tegn under hver felt.

            Eksempel 1:
            As part of an ambitious global growth strategy, Novo Nordisk launched a large-scale programme aimed at \
            increasing production capacity across several continents. With its size and complexity, the programme \
            introduced a high influx of new employees, particularly technical specialists and engineers. The existing \
            onboarding lacked consistency and depth, particularly in the technical domain. This resulted in slow \
            ramp-up times, insufficient knowledge transfer, and high turnover rates — ultimately threatening \
            project timelines and performance.

            Eksempel 2:
            Efter et stort opkøb stod kunden overfor en kompleks opgave med at integrere de to \
            organisationers IT-miljøer - det største IT Program i kundens historie. Programmet involverede \
            mere end 200 personer. Konteksten var præget af stor usikkerhed og komplekse samarbejdsrelationer, \
            da opgaven indebar et krav om samarbejde med en konkurrerende organisation. Programmet havde et \
            markant behov for en struktureret tilgang til organisatorisk forandringsledelse (OCM) for at sikre \
            fremdrift, engagement og forankring på tværs af organisationen.

            Baseret på ovenstående eksempler og sprogbrug skal du komme med ét konkret forslag til, hvordan følgende \
            projektbeskrivelse kunne forbedres, hvor der tages udgangspunkt i ovenstående gode eksempler og krav til \
            beskrivelsen. Forslag skal ikke være eksempler, men pege på specifikke elementer i den angivne tekst, \
            hvor forfatteren kunne gøre noget for at forbedre teksten. Forslaget du kommer med, skal skrives i form \
            af en kort sætning. Hvis beskrivelsen nogenlunde lever op til format og krav, så skriv at teksten er god. \
            Her er teksten:
            """;

    private static final String SOLUTION_PROMPT = """
            Det er vigtigt, at indholdet bliver skarpt, korte sætninger og ikke for langt. \
            Der må ikke blive skrevet jeg i teksten. Kundens navn må kun nævnes, hvis dette er godkendt af kunden, \
            så nævnes der et virksomhedsnavn, så gør her opmærksom på, at dette skal være godkendt. \
            Her kan du også referere til, at industrien blot nævnes.

            Gode eksempler på engelsk og dansk i forhold til opbygning og antal tegn under hver felt.

            Eksempel 1:
            At Trustworks, we proposed the need for a new technical onboarding programme to address gaps in \
            how engineers and specialists were onboarded and integrated into FFEx. We recognized that the lack \
            of structured technical onboarding was contributing to slow ramp-up times and high turnover. \
            We led the design and implementation of a targeted onboarding solution, tailored specifically to \
            the needs of IT & Automation roles within FFEx, which quickly became a strategic change initiative \
            for FFEx IT & Automation.

            Eksempel 2:
            Trustworks anlagde en struktureret tilgang til OCM. Vi udviklede og implementerede en OCM-strategi og \
            etablerede et dedikeret OCM-team som en del af programorganisationen. Som en del af indsatsen udarbejdede \
            vi en kommunikationsstrategi og oprettede kanaler for både intern og ekstern kommunikation, der bidrog \
            til at øge transparensen og styrke det tværgående samarbejde i programmet. For at sikre en bedre forståelse \
            af forretningens behov og sikre forankring, udviklede vi et format til change impact assessments samt et \
            ambassadørnetværk. Dette var med til at sikre bred opbakning i organisationen.

            Baseret på ovenstående eksempler og sprogbrug skal du komme med ét konkret forslag til, hvordan følgende \
            projektbeskrivelse kunne forbedres, hvor der tages udgangspunkt i ovenstående gode eksempler og krav til \
            beskrivelsen. Forslag skal ikke være eksempler, men pege på specifikke elementer i den angivne tekst, \
            hvor forfatteren kunne gøre noget for at forbedre teksten. Forslaget du kommer med, skal skrives i form \
            af en kort sætning. Hvis beskrivelsen nogenlunde lever op til format og krav, så skriv at teksten er god. \
            Her er teksten:
            """;

    private static final String VALUE_PROMPT = """
            Det er vigtigt, at indholdet bliver skarpt, korte sætninger og ikke for langt. \
            Der må ikke blive skrevet jeg i teksten. Kundens navn må kun nævnes, hvis dette er godkendt af kunden, \
            så nævnes der et virksomhedsnavn, så gør her opmærksom på, at dette skal være godkendt. \
            Her kan du også referere til, at industrien blot nævnes.

            Gode eksempler på engelsk og dansk i forhold til opbygning og antal tegn under hver felt.

            Eksempel 1:
            The technical onboarding programme became a key enabler of organizational readiness across FFEx. \
            It ensured that new technical employees were equipped to contribute from day one, improving \
            time-to-performance and reducing employee turnover. By standardizing onboarding across a complex, \
            global setup, the programme created consistency, scalability, and long-term value. It also strengthened \
            cross-functional collaboration and helped foster a shared culture in a rapidly growing organization.

            Eksempel 2:
            Trustworks bidrog til at skabe struktur i en kompleks og til tider kaotisk kontekst. Vi skabte øget \
            transparens i projektet gennem strategisk kommunikation og interessentinvolvering, hvilket styrkede \
            engagementet og reducerede modstanden i programmet. Desuden bidrog vi til et styrket samarbejde \
            mellem tekniske teams, procesansvarlige og forretningsenheder og sikrede, at brugerens perspektiv blev \
            tænkt ind i det tekniske arbejde.

            Baseret på ovenstående eksempler og sprogbrug skal du komme med ét konkret forslag til, hvordan følgende \
            projektbeskrivelse kunne forbedres, hvor der tages udgangspunkt i ovenstående gode eksempler og krav til \
            beskrivelsen. Forslag skal ikke være eksempler, men pege på specifikke elementer i den angivne tekst, \
            hvor forfatteren kunne gøre noget for at forbedre teksten. Forslaget du kommer med, skal skrives i form \
            af en kort sætning. Hvis beskrivelsen nogenlunde lever op til format og krav, så skriv at teksten er god. \
            Her er teksten:
            """;

    private static final Map<String, String> PROMPT_TEMPLATES = Map.of(
        "PROJECT_SITUATION", SITUATION_PROMPT,
        "PROJECT_SOLUTION", SOLUTION_PROMPT,
        "PROJECT_VALUE", VALUE_PROMPT
    );

    @POST
    @Path("/project-suggestion")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String getProjectSuggestion(ProjectSuggestionRequest request) {
        String template = PROMPT_TEMPLATES.get(request.promptType());
        if (template == null) {
            throw new IllegalArgumentException("Unknown promptType: " + request.promptType());
        }
        String text = (request.text() == null || request.text().isBlank()) ? "[Tom]" : request.text();
        String fullPrompt = template + "\n" + text;
        return openAIService.askQuestion(fullPrompt);
    }
}