package dk.trustworks.intranet.recruitmentservice.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Trustworks interview framework as interviewer-facing coaching, keyed by
 * {@link ScorecardAttribute#code()}.
 * <p>
 * Single source of truth for every surface: the web scorecard renders it as
 * hover help, the Slack modal as input hints, and the position editor as a
 * preview of what interviewers will be asked. Edit the wording here and all
 * three move together — including on positions created before the edit, since
 * guidance is resolved by code at render time (see {@link ScorecardGuidance}).
 * <p>
 * The catalog also carries {@link #CULTURE_FIT_LEGACY_CODE}, the predecessor of
 * {@code CULTURE}: positions created before the six-subject framework keep
 * their snapshotted template, and their in-flight interviews still deserve
 * help text.
 *
 * <h3>Language: the coaching is Danish, the codes and labels are not</h3>
 * The interview is held in Danish, so the coaching an interviewer reads mid
 * sitting — {@link #USAGE_NOTE}, the short hints, what-you-are-scoring, the
 * probes and the anchors — is Danish, authored here rather than translated by a
 * model (the pattern of {@code RecruitmentDanishLabels} and
 * {@code PublicApplyQuestions}: translation belongs in code, where it is total
 * and testable).
 * <p>
 * The subject {@code code} and {@code label} deliberately stay as they are. The
 * label is <em>snapshotted per position</em> into
 * {@code recruitment_positions.scorecard_template}, and the web scorecard dialog
 * and the Slack modal render that stored copy while the interview room renders
 * this one. Translating the label here alone would make the same subject read
 * Danish on one surface and English on another with nothing failing anywhere;
 * translating both is a data migration over every existing opening. Names stay.
 */
public final class ScorecardGuidanceCatalog {

    private ScorecardGuidanceCatalog() {
    }

    /** Pre-2026-08 code for the culture subject, still live on older positions. */
    public static final String CULTURE_FIT_LEGACY_CODE = "CULTURE_FIT";

    /**
     * How to run a six-subject sitting. Shown above the scorecard and in the
     * position editor: six is the ceiling for one interview, so the honest
     * pattern is depth on a few subjects and evidence-backed scoring on the
     * rest — marching through all six as a checklist is what produces
     * backfilled 3s.
     */
    public static final String USAGE_NOTE =
            "Seks emner er loftet for én samtale. Gå i dybden med tre eller fire, og score "
                    + "resten på det, der alligevel dukkede op undervejs — med én linje konkret "
                    + "evidens pr. score i dine noter. At arbejde sig gennem alle seks som en "
                    + "tjekliste er præcis det, der producerer efterrationaliserede 3-taller. Den "
                    + "samlede indstilling er en selvstændig, helhedsorienteret vurdering: afviger "
                    + "den fra emnescorerne, er det et signal værd at sige højt — ikke en fejl, "
                    + "der skal rettes.";

    private static final ScorecardGuidance WHY_CONSULTING = new ScorecardGuidance(
            "WHY_CONSULTING",
            "Why consulting",
            "Motivation for selve konsulentrollen — med et realistisk billede af dens "
                    + "ubehagelige sider.",
            "Motivation for selve konsulentrollen — ikke bare for Trustworks eller fagområdet. "
                    + "Et realistisk billede, der også rummer de ubehagelige sider (belægning, at "
                    + "blive målt på kundeværdi, at skulle stå på mål for en upopulær anbefaling). "
                    + "For erfarne profiler: belæg for, at de reelt har fungeret som konsulenter — "
                    + "storkoncernprofiler med “én Danske Bank-linje på CV'et” har ofte for meget "
                    + "af deres egen måde at arbejde på med i bagagen.",
            List.of(
                    "Hvad gør en konsulent, som en fastansat i samme stol ikke gør?",
                    "Fortæl om en gang, hvor du stod på mål for en anbefaling, kunden ikke brød sig om.",
                    "(Seniorer) Hvad skal du aflære for at lykkes her?"),
            List.of(
                    "Vil mere væk fra sit nuværende job end ind i konsulentfaget; behandler det som "
                            + "en venteposition; har intet realistisk billede af rollen.",
                    "Tiltrukket af variationen og prestigen, men har ikke forholdt sig til "
                            + "bagsiderne; storkoncernvanerne er ureflekterede.",
                    "Realistisk billede inkl. de hårde sider; motivationen er forankret i "
                            + "kundeværdi og variation; seniorer har påviseligt arbejdet som konsulenter.",
                    "Taler om konsulentfaget som et håndværk og har konkrete eksempler på at have "
                            + "valgt den sværere rådgivervej; giver intervieweren energi om rollen."));

    private static final String CULTURE_WHAT =
            "DNA'et gjort observerbart, begge halvdele. Good People: ærlig, empatisk, udadvendt; "
                    + "kan rumme flere perspektiver “på en rummelig måde”; balancerer sjov, arbejde "
                    + "og liv. Talent & Passion / Continuous Improvement: en hurtig lærende på en "
                    + "stejl kurve, der deler det, vedkommende lærer — den adfærd, praksisser, "
                    + "faglige fredage og vidensdage kører på. Testen er “en, du gerne vil arbejde "
                    + "sammen med, fagligt og personligt” — udtrykkeligt ikke “en, der ligner os”.";

    private static final List<String> CULTURE_PROBES = List.of(
            "Fortæl om en kollega, du var uenig med, og som du alligevel var med til at gøre "
                    + "succesfuld.",
            "Hvad får dine kolleger ud af at arbejde sammen med dig, som ikke står på dit CV?",
            "Hvad har du lært dig selv de seneste seks måneder — og hvem gav du det videre til?",
            "Hvad ville du holde en faglig fredag om?");

    private static final List<String> CULTURE_ANCHORS = List.of(
            "Taler nedsættende om tidligere kolleger; empatien er fraværende; ét fastlåst "
                    + "perspektiv; læringen stoppede ved seneste certificering; holder sin viden for sig selv.",
            "Rar, men lukket eller indøvet; har svært ved reelt at rumme et andet perspektiv; "
                    + "lærer, når det bliver krævet, og deler kun, hvis der bliver spurgt.",
            "Ærlig, varm og konkret; tager selv sine fejl op uopfordret; har ægte eksempler på en "
                    + "stejl kurve; underviser eller formidler af vane; du ville sætte vedkommende "
                    + "på din bedste kunde i morgen.",
            "Ville synligt løfte niveauet i rummet; en læringsmaskine med en delerefleks — møder "
                    + "op med en idé til, hvad vedkommende vil bidrage med i en praksis den første måned.");

    private static final String CULTURE_SHORT_HINT =
            "En, du gerne vil arbejde sammen med — ærlig, empatisk, hurtigt lærende "
                    + "og deler det, vedkommende lærer.";

    private static final ScorecardGuidance CULTURE = new ScorecardGuidance(
            "CULTURE",
            "Culture — Good People, learning & sharing",
            CULTURE_SHORT_HINT, CULTURE_WHAT, CULTURE_PROBES, CULTURE_ANCHORS);

    /** Same subject under its pre-2026-08 code, for positions snapshotted before the rename. */
    private static final ScorecardGuidance CULTURE_FIT = new ScorecardGuidance(
            CULTURE_FIT_LEGACY_CODE,
            "Culture fit",
            CULTURE_SHORT_HINT, CULTURE_WHAT, CULTURE_PROBES, CULTURE_ANCHORS);

    private static final ScorecardGuidance SELF_LEADERSHIP = new ScorecardGuidance(
            "SELF_LEADERSHIP",
            "Self-leadership & structure",
            "Kan vedkommende styre sin egen uge hos en kunde, uden at nogen holder i tøjlerne?",
            "Kan vedkommende styre sin egen uge hos en kunde, uden at nogen holder i tøjlerne? "
                    + "Adfærd fra karrieremodellen: planlægger og strukturerer eget arbejde, "
                    + "prioriterer, laver forventningsafstemning på forkant, melder skred tidligt, "
                    + "og holder et bæredygtigt tempo med grænser.",
            List.of(
                    "Beskriv en uge med tre leverancer og ingen til at styre dig — hvordan greb du den an?",
                    "Hvornår meldte du sidst tidligt ud, at noget ville skride?",
                    "Hvordan beslutter du, hvad du ikke skal lave?"),
            List.of(
                    "Har brug for anvisninger på opgaveniveau; deadlines kommer bag på vedkommende; "
                            + "ingen prioritering — eller endeløse timer som eneste værktøj.",
                    "Struktureret, når rammen bliver serveret; skred kommer først frem sent; "
                            + "tøvende med at genforhandle forventninger.",
                    "Planlægger eget arbejde, afstemmer forventninger på forkant, rejser risici "
                            + "tidligt og holder et bæredygtigt tempo.",
                    "Skaber struktur, som andre tager til sig; forventningsafstemning er en "
                            + "refleks; dokumenteret leverance under uklarhed uden styring."));

    private static final ScorecardGuidance UNCERTAINTY = new ScorecardGuidance(
            "UNCERTAINTY",
            "Handling uncertainty",
            "Skaber vedkommende selv klarhed, når opgaven er uklar — eller ventes der på den?",
            "Tryghed og effektivitet, når opgaven er uklar, data mangler, eller en aftalt deadline "
                    + "møder et fast scope (leverancekultur: timeboks, “lever effektivt, brænd "
                    + "færrest timer”). Skaber vedkommende klarhed — eller ventes der på den?",
            List.of(
                    "Fortæl om et projekt, hvor opgavebeskrivelsen var forkert eller slet ikke fandtes.",
                    "En aftalt deadline er i fare, og scope kan ikke flyttes — hvad gjorde du helt konkret?",
                    "Hvornår har det været sjovt ikke at vide?"),
            List.of(
                    "Har brug for sikkerhed for at handle; går i stå eller eskalerer alt; ubehaget "
                            + "aflæses som stress.",
                    "Klarer den med støtte; venter på klarhed frem for selv at skabe den.",
                    "Skaber klarhed for sig selv og andre; handler på 70 % information; timeboxer "
                            + "frem for at forgylde løsningen.",
                    "Opsøger det uklare; har en historik for at forvandle uklare situationer til "
                            + "planer, andre fulgte; får energi af det."));

    private static final ScorecardGuidance FAGLIGHED = new ScorecardGuidance(
            "FAGLIGHED",
            "Faglighed & formidling",
            "Kan vedkommende løse opgaven på det niveau, rollen ansættes til — og forklare det enkelt?",
            "Kan vedkommende løse opgaven på det niveau, rollen ansættes til — og forklare det "
                    + "enkelt? Vurderes op mod praksissens kompetencekatalog (Aspirer som "
                    + "udgangspunkt for nyuddannede → Udfører for seniorer), plus DNA Q4: "
                    + "komplicerede emner formidlet enkelt — konklusionen først, tilpasset "
                    + "modtageren. I caserunden scores der på praksissens egen case; i samtaler "
                    + "uden case dybdeprøves CV'et: tag den stærkeste påståede erfaring og test, om "
                    + "den holder til tre opfølgende spørgsmål (Hvorfor det trade-off? Hvad brød "
                    + "sammen først? Hvad vidste du ikke dengang?).",
            List.of(
                    "Casen — eller en dybdeprøve af den stærkeste erfaring på CV'et.",
                    "Forklar kernen i din løsning, som var det til kundens styregruppe — to minutter.",
                    "Forklar den nu for den nyuddannede, der starter på mandag."),
            List.of(
                    "Under det katalogniveau, rollen kræver; bliver upræcis, når der bedes om "
                            + "detaljer; påstandene falder fra hinanden ved opfølgning; taber "
                            + "tilhøreren i kompleksitet.",
                    "Fragmenter af det krævede niveau; nævner frameworks frem for at anvende dem; "
                            + "kun tydelig med et manuskript.",
                    "Rammer katalogniveauet: anvender sin kompetence, ræsonnerer om trade-offs, "
                            + "siger klart, hvad vedkommende ikke ved — og formidler det enkelt, "
                            + "konklusionen først.",
                    "Over rolleniveau: omformulerer selve problemet, lærer intervieweren noget, får "
                            + "det komplekse til at virke indlysende — klar til en styregruppe i morgen."));

    private static final ScorecardGuidance COMMERCIAL_DRIVE = new ScorecardGuidance(
            "COMMERCIAL_DRIVE",
            "Commercial drive",
            "Nysgerrighed på kundens forretning og tryghed ved at tale værdi og pris "
                    + "på kundens niveau.",
            "“Konsulenter først, kommercielt bevidste dernæst.” Ægte nysgerrighed på kundens "
                    + "forretning, øje for mersalg og evne til at give det videre, tryghed ved at "
                    + "tale værdi og pris på kundens niveau — og realisme om tal (et selvsikkert "
                    + "“10 millioner” uden at spørge til rater er et signal, ikke et svar).",
            List.of(
                    "På dit seneste projekt: hvad burde dit firma have solgt som det næste — og "
                            + "hvorfor skete det ikke?",
                    "Hvordan holder du en relation i live, efter projektet er slut?",
                    "(Kommercielle roller) Giv mig et tal, du vil binde dig til, og ræsonnementet bag."),
            List.of(
                    "Salg er andres beskidte arbejde; ingen nysgerrighed på kundens forretning; "
                            + "tallene er rent gætværk.",
                    "Forstår den kommercielle sammenhæng, når den bliver peget ud; ser muligheder, "
                            + "men handler ikke på dem og giver dem ikke videre.",
                    "Ægte nysgerrig på kundens forretning; ser muligheder og giver dem videre; tryg "
                            + "ved værdi og pris på kundens niveau.",
                    "Har en historik for selvsalg og forlængelser; tænker uopfordret i kundens "
                            + "udbytte og Trustworks' positionering; tallene er velbegrundede."));

    /**
     * The standard subjects in interview order. {@code CULTURE_FIT} is
     * resolvable but deliberately absent here — it is legacy, never offered
     * as a choice when building a new template.
     */
    private static final List<ScorecardGuidance> STANDARD = List.of(
            WHY_CONSULTING, CULTURE, SELF_LEADERSHIP, UNCERTAINTY, FAGLIGHED, COMMERCIAL_DRIVE);

    private static final Map<String, ScorecardGuidance> BY_CODE = index();

    private static Map<String, ScorecardGuidance> index() {
        Map<String, ScorecardGuidance> map = new LinkedHashMap<>();
        for (ScorecardGuidance guidance : STANDARD) {
            map.put(guidance.code(), guidance);
        }
        map.put(CULTURE_FIT.code(), CULTURE_FIT);
        return Map.copyOf(map);
    }

    /** @return the standard subjects in interview order — the offer list for new templates. */
    public static List<ScorecardGuidance> standard() {
        return STANDARD;
    }

    /** @return guidance for a code, empty for custom subjects the catalog knows nothing about. */
    public static Optional<ScorecardGuidance> forCode(String code) {
        return Optional.ofNullable(code).map(BY_CODE::get);
    }

    /** @return the attribute list a new position starts from — codes and labels only. */
    public static List<ScorecardAttribute> standardTemplate() {
        return STANDARD.stream()
                .map(g -> new ScorecardAttribute(g.code(), g.label(), null))
                .toList();
    }
}
