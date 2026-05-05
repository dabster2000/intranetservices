package dk.trustworks.intranet.recruitmentservice.util;

public final class HtmlEscape {
    private HtmlEscape() {}

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
