package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.dao.crm.client.CvrApiResponse;
import dk.trustworks.intranet.dao.crm.model.Client;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Registry wins" (decision of 2026-09-14) and its two exceptions, pinned without a
 * database: a blank registry value never blanks a stored one, and the invoice address is
 * filled only when empty.
 */
class RegistryApplierTest {

    private static CvrApiResponse registry() {
        CvrApiResponse r = new CvrApiResponse();
        r.vat = 26573572L;
        r.name = "Fiskars Denmark (Vita) A/S";
        r.address = "Baltorpvej 20";
        r.zipcode = "2750";
        r.city = "Ballerup";
        r.phone = "44 66 00 00";
        r.email = "info@fiskars.dk";
        r.industrycode = 464410;
        r.industrydesc = "Engroshandel med porcelæn, glas og husholdningsartikler";
        r.companydesc = "Aktieselskab";
        return r;
    }

    @Test
    void registryOverwritesStoredNameAddressPhoneAndIndustry() {
        Client c = new Client();
        c.setUuid("u1");
        c.setName("Fiskars");
        c.setBillingAddress("Old street 1");
        c.setPhone("11 11 11 11");
        c.setIndustryCode(1);
        c.setIndustryDesc("Old");

        List<RegistryApplier.FieldChange> changes = RegistryApplier.apply(c, registry(), "26573572");

        assertEquals("Fiskars Denmark (Vita) A/S", c.getName());
        assertEquals("Baltorpvej 20", c.getBillingAddress());
        assertEquals("2750", c.getBillingZipcode());
        assertEquals("Ballerup", c.getBillingCity());
        assertEquals("44 66 00 00", c.getPhone());
        assertEquals(464410, c.getIndustryCode());
        assertEquals("Aktieselskab", c.getCompanyDesc());
        assertEquals("26573572", c.getCvr());
        assertTrue(changes.stream().anyMatch(ch -> ch.field().equals("name") && "Fiskars".equals(ch.oldValue())));
        assertTrue(changes.stream().anyMatch(ch -> ch.field().equals("cvr") && ch.oldValue() == null));
        assertTrue(changes.stream().anyMatch(ch -> ch.field().equals("industryCode") && "1".equals(ch.oldValue())));
    }

    @Test
    void blankRegistryValuesNeverBlankStoredOnes() {
        CvrApiResponse r = registry();
        r.phone = "";
        r.address = null;
        r.industrycode = 0;
        r.companydesc = "  ";
        Client c = new Client();
        c.setUuid("u1");
        c.setName("Fiskars Denmark (Vita) A/S");
        c.setCvr("26573572");
        c.setPhone("11 11 11 11");
        c.setBillingAddress("Old street 1");
        c.setIndustryCode(464410);
        c.setCompanyDesc("Aktieselskab");

        List<RegistryApplier.FieldChange> changes = RegistryApplier.apply(c, r, "26573572");

        assertEquals("11 11 11 11", c.getPhone());
        assertEquals("Old street 1", c.getBillingAddress());
        assertEquals(464410, c.getIndustryCode());
        assertEquals("Aktieselskab", c.getCompanyDesc());
        assertTrue(changes.stream().noneMatch(ch -> ch.field().equals("phone") || ch.field().equals("billingAddress")
                || ch.field().equals("industryCode") || ch.field().equals("companyDesc")));
    }

    @Test
    void billingEmailIsFilledOnlyWhenEmpty() {
        Client kept = new Client();
        kept.setUuid("u1");
        kept.setBillingEmail("ap@fiskars.dk");
        RegistryApplier.apply(kept, registry(), "26573572");
        assertEquals("ap@fiskars.dk", kept.getBillingEmail());

        Client empty = new Client();
        empty.setUuid("u2");
        List<RegistryApplier.FieldChange> changes = RegistryApplier.apply(empty, registry(), "26573572");
        assertEquals("info@fiskars.dk", empty.getBillingEmail());
        assertTrue(changes.stream().anyMatch(ch -> ch.field().equals("billingEmail")));
    }

    @Test
    void unchangedValuesProduceNoChangeEntries() {
        Client c = new Client();
        c.setUuid("u1");
        RegistryApplier.apply(c, registry(), "26573572");
        List<RegistryApplier.FieldChange> second = RegistryApplier.apply(c, registry(), "26573572");
        assertTrue(second.isEmpty(), "applying the same registry answer twice must change nothing: " + second);
    }

    @Test
    void aCvrIsOnlyWrittenWhenItDiffers() {
        Client c = new Client();
        c.setUuid("u1");
        c.setCvr("26573572");
        List<RegistryApplier.FieldChange> changes = RegistryApplier.apply(c, registry(), "26573572");
        assertTrue(changes.stream().noneMatch(ch -> ch.field().equals("cvr")));
        assertNull(RegistryApplier.apply(new Client(), registry(), null).stream()
                .filter(ch -> ch.field().equals("cvr")).findFirst().orElse(null));
    }
}
