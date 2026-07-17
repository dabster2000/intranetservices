package dk.trustworks.intranet.communicationsservice.services;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import dk.trustworks.intranet.communicationsservice.dto.NewLeadNotificationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Block-building is exercised without a Slack client: LayoutBlocks are plain objects until sent.
 *
 * <p>Regression cover for the production incident where Slack rejected the whole notification with
 * {@code invalid_blocks} because a lead's free-text overflowed the 3000-char text object limit.
 */
class SlackServiceNewLeadBlocksTest {

    private static final int SLACK_TEXT_OBJECT_MAX_CHARS = 3000;

    private SlackService slackService;

    @BeforeEach
    void setUp() throws Exception {
        slackService = new SlackService();
        // @ConfigProperty fields are null outside CDI; constructLeadUrl reads applicationBaseUrl.
        injectField(slackService, "applicationBaseUrl", "https://intra.trustworks.dk");
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** A lead with everything populated and nothing oversized. */
    private NewLeadNotificationDTO lead() {
        NewLeadNotificationDTO dto = new NewLeadNotificationDTO();
        dto.setClientName("Sundhedsdatastyrelsen");
        dto.setDescription("Architecture support for the national health data platform");
        dto.setDetailedDescription("Two senior architects needed from Q3.");
        dto.setStatus("QUALIFIED");
        dto.setAllocation(80);
        dto.setPeriod(12);
        dto.setRate(1250.0);
        dto.setCloseDate(LocalDate.of(2026, 9, 1));
        dto.setLeadManagerName("Hans Lassen");
        dto.setLeadUuid("b3f1c0de-0000-4000-8000-000000000001");
        return dto;
    }

    private static List<SectionBlock> sectionsOf(List<LayoutBlock> blocks) {
        return blocks.stream()
                .filter(SectionBlock.class::isInstance)
                .map(SectionBlock.class::cast)
                .toList();
    }

    /** Every text object Slack would validate, flattened. */
    private static List<String> allTexts(List<LayoutBlock> blocks) {
        return sectionsOf(blocks).stream()
                .flatMap(s -> {
                    var texts = new java.util.ArrayList<String>();
                    if (s.getText() != null) texts.add(s.getText().getText());
                    if (s.getFields() != null) s.getFields().forEach(f -> texts.add(f.getText()));
                    return texts.stream();
                })
                .toList();
    }

    @Test
    void oversizedDetailedDescription_isClampedToSlackLimit() {
        NewLeadNotificationDTO dto = lead();
        dto.setDetailedDescription("x".repeat(5000));

        List<LayoutBlock> blocks = slackService.buildNewLeadBlocks(dto);

        String detailed = sectionsOf(blocks).stream()
                .map(s -> s.getText() == null ? "" : s.getText().getText())
                .filter(t -> t.startsWith("*Detailed Description:*"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Detailed Description section missing"));

        assertEquals(SLACK_TEXT_OBJECT_MAX_CHARS, detailed.length(),
                "Detailed Description must be clamped to Slack's text object limit");
        assertTrue(detailed.endsWith("..."), "Clamped text should show it was truncated");
    }

    /** The path a detailedDescription-only fix would have missed: description is also a TEXT column. */
    @Test
    void oversizedDescription_isClampedToSlackLimit() {
        NewLeadNotificationDTO dto = lead();
        dto.setDescription("y".repeat(5000));

        List<LayoutBlock> blocks = slackService.buildNewLeadBlocks(dto);

        String clientText = sectionsOf(blocks).getFirst().getText().getText();
        assertEquals(SLACK_TEXT_OBJECT_MAX_CHARS, clientText.length(),
                "Client/description section must be clamped to Slack's text object limit");
        assertTrue(clientText.startsWith("*Sundhedsdatastyrelsen*"), "Client name must survive clamping");
    }

    /** No text object may exceed the limit, whichever field is oversized. */
    @Test
    void everyTextObjectStaysWithinSlackLimit_whenAllFreeTextIsOversized() {
        NewLeadNotificationDTO dto = lead();
        dto.setDescription("y".repeat(9000));
        dto.setDetailedDescription("x".repeat(9000));
        dto.setClientName("z".repeat(9000));

        List<LayoutBlock> blocks = slackService.buildNewLeadBlocks(dto);

        assertFalse(allTexts(blocks).isEmpty(), "Expected section text objects to assert on");
        allTexts(blocks).forEach(text ->
                assertTrue(text.length() <= SLACK_TEXT_OBJECT_MAX_CHARS,
                        "Text object of length " + text.length() + " exceeds Slack's limit"));
    }

    /** Text that exactly fills the budget must not be truncated. */
    @Test
    void detailedDescriptionAtExactlyTheLimit_isNotClamped() {
        String label = "*Detailed Description:*\n";
        String body = "x".repeat(SLACK_TEXT_OBJECT_MAX_CHARS - label.length());
        NewLeadNotificationDTO dto = lead();
        dto.setDetailedDescription(body);

        List<LayoutBlock> blocks = slackService.buildNewLeadBlocks(dto);

        String detailed = sectionsOf(blocks).stream()
                .map(s -> s.getText().getText())
                .filter(t -> t.startsWith(label))
                .findFirst()
                .orElseThrow();

        assertEquals(SLACK_TEXT_OBJECT_MAX_CHARS, detailed.length());
        assertEquals(label + body, detailed, "Text at exactly the limit must be sent verbatim");
    }

    /** Slack rejects empty text objects, so a blank field must drop its block, not emit an empty one. */
    @Test
    void blankDetailedDescription_omitsTheSectionEntirely() {
        for (String blank : new String[]{"", "   ", "\n\t"}) {
            NewLeadNotificationDTO dto = lead();
            dto.setDetailedDescription(blank);

            List<LayoutBlock> blocks = slackService.buildNewLeadBlocks(dto);

            assertTrue(sectionsOf(blocks).stream()
                            .noneMatch(s -> s.getText() != null
                                    && s.getText().getText().startsWith("*Detailed Description:*")),
                    "Blank detailedDescription must not produce a Detailed Description section");
            allTexts(blocks).forEach(text ->
                    assertFalse(text.isBlank(), "Slack rejects empty text objects"));
        }
    }

    /** Null optionals must render fallbacks, never an empty text object. */
    @Test
    void allNullableFieldsNull_producesNoEmptyTextObjects() {
        NewLeadNotificationDTO dto = new NewLeadNotificationDTO();
        dto.setClientName("Sundhedsdatastyrelsen");

        List<LayoutBlock> blocks = slackService.buildNewLeadBlocks(dto);

        assertFalse(blocks.isEmpty(), "Expected blocks even for a sparse lead");
        allTexts(blocks).forEach(text -> {
            assertFalse(text.isBlank(), "Slack rejects empty text objects");
            assertTrue(text.length() <= SLACK_TEXT_OBJECT_MAX_CHARS);
        });
    }

    /** Slack caps notification text well below the 3000-char block limit. */
    @Test
    void oversizedDescription_clampsTheFallbackText() {
        NewLeadNotificationDTO dto = lead();
        dto.setDescription("y".repeat(50_000));

        assertTrue(slackService.newLeadFallback(dto).length() <= 4000,
                "Fallback text must stay within Slack's notification text limit");
    }

    @Test
    void normalLead_isUnchangedAndWithinAllLimits() {
        List<LayoutBlock> blocks = slackService.buildNewLeadBlocks(lead());

        assertTrue(blocks.size() <= 50, "Slack allows at most 50 blocks per message");
        assertEquals("*Sundhedsdatastyrelsen*\nArchitecture support for the national health data platform",
                sectionsOf(blocks).getFirst().getText().getText());
        allTexts(blocks).forEach(text ->
                assertTrue(text.length() <= SLACK_TEXT_OBJECT_MAX_CHARS));
    }
}
