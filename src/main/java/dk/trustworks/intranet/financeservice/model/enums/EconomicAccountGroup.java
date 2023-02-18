package dk.trustworks.intranet.financeservice.model.enums;

import org.apache.commons.lang3.Range;

public enum EconomicAccountGroup {
    OMSAETNING_ACCOUNTS(Range.between(2100, 2199)),
    PRODUKTION_ACCOUNTS(Range.between(3000, 3099)),
    LOENNINGER_ACCOUNTS(Range.between(3500, 3599)),
    PERSONALE_ACCOUNTS(Range.between(10000, 10100)),
    VARIABEL_ACCOUNTS(Range.between(3600, 3699)),
    LOKALE_ACCOUNTS(Range.between(3700, 3799)),
    SALG_ACCOUNTS(Range.between(4000, 4099)),
    ADMINISTRATION_ACCOUNTS(Range.between(5200, 5299)),
    KREDITNOTA_ACCOUNTS(Range.between(8610, 8614));

    private final Range<Integer> range;

    EconomicAccountGroup(Range<Integer> range) {
        this.range = range;
    }

    public Range<Integer> getRange() {
        return range;
    }
}
