package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentConfig;
import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentTestConfig;
import dk.trustworks.intranet.aggregates.crm.enrichment.dto.ClientEnrichmentDTO;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.ClientEnrichment;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.CvrEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.LogoEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.SectorEnrichmentStatus;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of the enrichment row that do not need a database: how a row is seeded from
 * the client, what a person's edit does to it, what the caps and the staging gate answer,
 * and which states the account page has to shout about.
 */
class ClientEnrichmentRulesTest {

    private static Client client(String cvr, Integer industryCode, ClientType type, ClientSegment segment, String country) {
        Client c = new Client();
        c.setUuid("11111111-1111-1111-1111-111111111111");
        c.setName("Nykredit");
        c.setCvr(cvr);
        c.setIndustryCode(industryCode);
        c.setType(type);
        c.setSegment(segment);
        c.setBillingCountry(country);
        return c;
    }

    // ---- Seeding --------------------------------------------------------------

    @Test
    void aClientAlreadyLookedUpStartsVerified() {
        ClientEnrichment row = ClientEnrichmentRepository.initialState(client("12719280", 641900, ClientType.CLIENT, ClientSegment.FINANCIAL, "DK"));
        assertEquals(CvrEnrichmentStatus.VERIFIED, row.cvrStatus());
        assertEquals(LogoEnrichmentStatus.PENDING, row.logoStatus());
        assertEquals(SectorEnrichmentStatus.HUMAN_SET, row.sectorStatus());
    }

    @Test
    void aFreshDanishProspectUnderOtherStartsPendingEverywhereButLogo() {
        ClientEnrichment row = ClientEnrichmentRepository.initialState(client(null, null, ClientType.PROSPECT, ClientSegment.OTHER, "DK"));
        assertEquals(CvrEnrichmentStatus.PENDING, row.cvrStatus());
        assertEquals(LogoEnrichmentStatus.SKIPPED, row.logoStatus(), "logos are for clients only");
        assertEquals(SectorEnrichmentStatus.PENDING, row.sectorStatus());
    }

    @Test
    void aCvrWithoutRegistryDataIsStillPendingAndAForeignRowIsSkipped() {
        assertEquals(CvrEnrichmentStatus.PENDING,
                ClientEnrichmentRepository.initialState(client("12719280", null, ClientType.CLIENT, ClientSegment.OTHER, "DK")).cvrStatus());
        assertEquals(CvrEnrichmentStatus.PENDING,
                ClientEnrichmentRepository.initialState(client("12719280", 0, ClientType.CLIENT, ClientSegment.OTHER, null)).cvrStatus());
        assertEquals(CvrEnrichmentStatus.SKIPPED,
                ClientEnrichmentRepository.initialState(client(null, null, ClientType.CLIENT, ClientSegment.OTHER, "SE")).cvrStatus());
    }

    // ---- Edits ----------------------------------------------------------------

    @Test
    void aChangedCvrReopensTheCvrQuestion() {
        ClientEnrichment row = new ClientEnrichment("u");
        row.setCvr(CvrEnrichmentStatus.NOT_FOUND);
        row.setCvrError("No CVR could be found");
        row.setCvrCandidate("12719280");
        Client before = client(null, null, ClientType.CLIENT, ClientSegment.FINANCIAL, "DK");
        Client after = client("12719280", null, ClientType.CLIENT, ClientSegment.FINANCIAL, "DK");

        ClientEnrichmentService.applyEdit(row, before, after);

        assertEquals(CvrEnrichmentStatus.PENDING, row.cvrStatus());
        assertNull(row.getCvrError());
        assertNull(row.getCvrCandidate());
    }

    @Test
    void aPersonChoosingASectorClosesTheQuestion() {
        ClientEnrichment row = new ClientEnrichment("u");
        row.setSector(SectorEnrichmentStatus.PENDING);
        boolean check = ClientEnrichmentService.applyEdit(row,
                client(null, null, ClientType.CLIENT, ClientSegment.OTHER, "DK"),
                client(null, null, ClientType.CLIENT, ClientSegment.HEALTH, "DK"));
        assertFalse(check);
        assertEquals(SectorEnrichmentStatus.HUMAN_SET, row.sectorStatus());
    }

    @Test
    void aPersonPuttingAnAiSetClientBackToOtherIsAnOverrideAndNeverRechecked() {
        ClientEnrichment row = new ClientEnrichment("u");
        row.setSector(SectorEnrichmentStatus.CHANGED);
        boolean check = ClientEnrichmentService.applyEdit(row,
                client(null, null, ClientType.CLIENT, ClientSegment.FINANCIAL, "DK"),
                client(null, null, ClientType.CLIENT, ClientSegment.OTHER, "DK"));
        assertFalse(check, "the model must not get another turn");
        assertEquals(SectorEnrichmentStatus.HUMAN_OVERRIDE, row.sectorStatus());
    }

    @Test
    void aPersonPuttingTheirOwnSectorBackToOtherAsksForACheck() {
        ClientEnrichment row = new ClientEnrichment("u");
        row.setSector(SectorEnrichmentStatus.HUMAN_SET);
        boolean check = ClientEnrichmentService.applyEdit(row,
                client(null, null, ClientType.CLIENT, ClientSegment.FINANCIAL, "DK"),
                client(null, null, ClientType.CLIENT, ClientSegment.OTHER, "DK"));
        assertTrue(check);
        assertEquals(SectorEnrichmentStatus.PENDING, row.sectorStatus());
    }

    @Test
    void anEditThatKeepsOtherRechecksOnlyWhenTheIdentityChanged() {
        ClientEnrichment confirmed = new ClientEnrichment("u");
        confirmed.setSector(SectorEnrichmentStatus.CONFIRMED_OTHER);
        Client before = client("12719280", 1, ClientType.CLIENT, ClientSegment.OTHER, "DK");
        Client sameName = client("12719280", 1, ClientType.CLIENT, ClientSegment.OTHER, "DK");
        assertFalse(ClientEnrichmentService.applyEdit(confirmed, before, sameName));
        assertEquals(SectorEnrichmentStatus.CONFIRMED_OTHER, confirmed.sectorStatus());

        Client renamed = client("12719280", 1, ClientType.CLIENT, ClientSegment.OTHER, "DK");
        renamed.setName("Nykredit Realkredit A/S");
        assertTrue(ClientEnrichmentService.applyEdit(confirmed, before, renamed));
        assertEquals(SectorEnrichmentStatus.PENDING, confirmed.sectorStatus());

        ClientEnrichment pending = new ClientEnrichment("u");
        pending.setSector(SectorEnrichmentStatus.PENDING);
        assertTrue(ClientEnrichmentService.applyEdit(pending, before, sameName), "a pending row is checked on any edit");

        ClientEnrichment overridden = new ClientEnrichment("u");
        overridden.setSector(SectorEnrichmentStatus.HUMAN_OVERRIDE);
        assertFalse(ClientEnrichmentService.applyEdit(overridden, before, renamed), "an override survives a rename");
    }

    @Test
    void anAiSetSectorLeftStandingStaysChanged() {
        ClientEnrichment row = new ClientEnrichment("u");
        row.setSector(SectorEnrichmentStatus.CHANGED);
        Client before = client(null, null, ClientType.CLIENT, ClientSegment.FINANCIAL, "DK");
        Client after = client(null, null, ClientType.CLIENT, ClientSegment.FINANCIAL, "DK");
        after.setPhone("12345678");
        assertFalse(ClientEnrichmentService.applyEdit(row, before, after));
        assertEquals(SectorEnrichmentStatus.CHANGED, row.sectorStatus(), "editing the phone is not a decision about the sector");
    }

    // ---- Config ---------------------------------------------------------------

    @Test
    void capsAreClampedAndStagingIsGatedOffTheNightlyCron() {
        ClientEnrichmentConfig staging = ClientEnrichmentTestConfig.with("staging", -5, 9999, 20, "  ");
        assertEquals(0, staging.cvrNightlyCap());
        assertEquals(500, staging.logoNightlyCap());
        assertEquals(20, staging.sectorNightlyCap());
        assertFalse(staging.nightlyAllowedHere());
        assertNull(staging.reasoningEffort(), "a blank effort omits the reasoning node");
        ClientEnrichmentConfig production = ClientEnrichmentTestConfig.with("production", 20, 10, 20, "low");
        assertTrue(production.nightlyAllowedHere());
        assertEquals("low", production.reasoningEffort());
        assertNull(ClientEnrichmentTestConfig.with("production", 1, 1, 1, null).reasoningEffort());
    }

    @Test
    void eightDigitsPadsAndRefusesNonsense() {
        assertEquals("00123456", CvrEnrichmentService.eightDigits(123456L));
        assertEquals("26573572", CvrEnrichmentService.eightDigits(26573572L));
        assertNull(CvrEnrichmentService.eightDigits(0L));
        assertNull(CvrEnrichmentService.eightDigits(123456789L));
    }

    // ---- What the page shouts about ---------------------------------------------

    @Test
    void attentionIsTheActionableStatesOnly() {
        ClientEnrichment row = new ClientEnrichment("u");
        row.setCvr(CvrEnrichmentStatus.CANDIDATE);
        row.setCvrCandidate("26573572");
        row.setCvrCandidateName("Fiskars Denmark (Vita) A/S");
        row.setLogo(LogoEnrichmentStatus.GENERATED);
        row.setSector(SectorEnrichmentStatus.CHANGED);
        row.setSectorAiSegment("FINANCIAL");
        ClientEnrichmentDTO dto = ClientEnrichmentDTO.of(row);
        assertTrue(dto.needsAttention());
        assertEquals(1, dto.attention().size(), "a generated logo and an AI-set sector are provenance, not problems");
        assertTrue(dto.attention().get(0).contains("26573572"));

        ClientEnrichment quiet = new ClientEnrichment("u");
        quiet.setCvr(CvrEnrichmentStatus.VERIFIED);
        quiet.setLogo(LogoEnrichmentStatus.FOUND);
        quiet.setSector(SectorEnrichmentStatus.CONFIRMED_OTHER);
        assertFalse(ClientEnrichmentDTO.of(quiet).needsAttention());

        ClientEnrichment loud = new ClientEnrichment("u");
        loud.setCvr(CvrEnrichmentStatus.FAILED);
        loud.setCvrError("Registry lookup failed: HTTP 502");
        loud.setLogo(LogoEnrichmentStatus.FAILED);
        loud.setSector(SectorEnrichmentStatus.FAILED);
        assertEquals(3, ClientEnrichmentDTO.of(loud).attention().size());
    }

    @Test
    void unknownStatusStringsReadAsPending() {
        ClientEnrichment row = new ClientEnrichment("u");
        row.setCvrStatus("SOMETHING_NEW");
        row.setLogoStatus(null);
        row.setSectorStatus("");
        assertEquals(CvrEnrichmentStatus.PENDING, row.cvrStatus());
        assertEquals(LogoEnrichmentStatus.PENDING, row.logoStatus());
        assertEquals(SectorEnrichmentStatus.PENDING, row.sectorStatus());
        assertTrue(ClientEnrichmentService.parseJob("Logo").isPresent());
        assertTrue(ClientEnrichmentService.parseJob("x").isEmpty());
    }
}
