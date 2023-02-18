package dk.trustworks.intranet.financeservice.model.enums;

public enum ExcelFinanceType {

    PRODUKTION("Produktionsomk. i alt"),
    LØNNINGER("Lønninger i alt"),
    PERSONALE("Personaleomkostninger i alt"),
    LOKALE("Lokaleomkostninger i alt"),
    SALG("SALGSFREMMENDE OMK I ALT"),
    ADMINISTRATION("Øvrige administrationsomk. i alt");

    private final String text;

    ExcelFinanceType(String text) {
        this.text = text;
    }

    public String getText() {
        return this.text;
    }

    public static ExcelFinanceType fromString(String text) {
        for (ExcelFinanceType b : ExcelFinanceType.values()) {
            if (b.text.equalsIgnoreCase(text)) {
                return b;
            }
        }
        return null;
    }
}
