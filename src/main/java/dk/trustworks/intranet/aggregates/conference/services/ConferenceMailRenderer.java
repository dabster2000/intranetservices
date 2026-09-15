package dk.trustworks.intranet.aggregates.conference.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.conference.dto.UnsubscribeFooter;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

/** Always renders from immutable source HTML. Author HTML cannot define the capability destination. */
@ApplicationScoped
public class ConferenceMailRenderer {
    @Inject ObjectMapper json;

    public void preparePhase(ConferencePhase phase) {
        if (phase.getMailJson() == null || phase.getMailJson().isBlank()) {
            phase.setUnsubscribeFooter(null); // replacing designer content with legacy HTML clears stale settings
        } else {
            try {
                JsonNode rows = json.readTree(phase.getMailJson()).path("rows");
                UnsubscribeFooter footer = null;
                if (!rows.isArray()) throw new BadRequestException("Invalid email document");
                for (int i = 0; i < rows.size(); i++) {
                    JsonNode managed = rows.get(i).get("managedFooter");
                    if (managed == null || managed.isNull()) continue;
                    if (footer != null || i != rows.size() - 1 || managed.size() != 4)
                        throw new BadRequestException("Email requires one final managed footer");
                    footer = json.treeToValue(managed, UnsubscribeFooter.class);
                }
                if (footer == null) throw new BadRequestException("Email requires a managed footer");
                if (phase.getUnsubscribeFooter() != null && !phase.getUnsubscribeFooter().equals(footer))
                    throw new BadRequestException("Email footer settings do not match designer document");
                phase.setUnsubscribeFooter(footer);
            } catch (BadRequestException e) { throw e; }
            catch (Exception e) { throw new BadRequestException("Invalid email document"); }
        }
        if (phase.isUseMail()) {
            try {
                String body = new String(Base64.getDecoder().decode(phase.getMail()), StandardCharsets.UTF_8);
                validateContent(phase.getSubject(), body);
            } catch (IllegalArgumentException e) { throw new BadRequestException("Email body must be Base64 HTML"); }
        }
    }

    public static void validateContent(String subject, String body) {
        if (subject == null || subject.isBlank() || subject.length() > 255
                || subject.codePoints().anyMatch(Character::isISOControl))
            throw new BadRequestException("A valid email subject is required");
        Document document = bodyDocument(body);
        rejectGeneratedSource(document);
        normalizeLegacy(document);
        document.select("script,style,head,noscript,template,[hidden],[aria-hidden=true]").remove();
        // Editor preheaders are hidden copy, not substantive message content.
        for (Element element : document.body().getAllElements()) {
            String style = element.attr("style").replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            if (style.contains("display:none") || style.contains("visibility:hidden") || style.matches(".*(?:^|;)opacity:0(?:;|!|$).*")) {
                if (element == document.body()) throw new BadRequestException("Email body must remain visible");
                element.remove();
            }
        }
        if (document.body().text().replace('\u00a0', ' ').isBlank()
                && document.body().select("img[src]").isEmpty())
            throw new BadRequestException("Email body must contain content besides the unsubscribe footer");
    }

    public static String render(String sourceBody, UnsubscribeFooter descriptor, String listName, String url) {
        URI destination = URI.create(url);
        if (!"https".equals(destination.getScheme()) || destination.getRawAuthority() == null
                || destination.getUserInfo() != null || !"/unsubscribe".equals(destination.getPath())
                || destination.getQuery() != null || destination.getFragment() == null
                || !destination.getFragment().matches("token=[A-Za-z0-9_-]{43}"))
            throw new IllegalStateException("CONFERENCE_FOOTER_DESTINATION_INVALID");
        Document document = bodyDocument(sourceBody);
        validateConditionalComments(document);
        normalizeLegacy(document);
        document.select("script,iframe,object,embed,form,base,meta[http-equiv],link,svg,math").remove();
        for (Element element : document.getAllElements()) {
            rejectOverlappingDeclarations(element.attr("style"));
            for (var attribute : element.attributes().asList()) {
                if (attribute.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("on")) element.removeAttr(attribute.getKey());
            }
        }
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href").replaceAll("[\\s\\p{Cntrl}]", "").toLowerCase(java.util.Locale.ROOT);
            if (href.startsWith("javascript:") || href.startsWith("vbscript:") || href.startsWith("data:")) anchor.removeAttr("href");
        }
        if (document.outerHtml().contains("{{unsubscribe_url}}") || document.outerHtml().contains("{{preferences_url}}"))
            throw new IllegalStateException("CONFERENCE_FOOTER_UNRESOLVED_ACTION");
        UnsubscribeFooter settings = UnsubscribeFooter.orDefault(descriptor);
        // Direct child of body: outside all author-controlled tables/hidden containers.
        rejectGeneratedSource(document);
        String alignment = "centre".equals(settings.alignment()) ? "center" : "left";
        Element footer = document.body().appendElement("table").attr("role", "presentation")
                .attr("width", "100%").attr("cellpadding", "0").attr("cellspacing", "0")
                .attr("data-conference-footer", "true")
                .attr("style", "display:table!important;visibility:visible!important;opacity:1!important;width:100%;background:#ffffff;color:#374151;margin:0;");
        Element cell = footer.appendElement("tbody").appendElement("tr").appendElement("td")
                .attr("align", alignment).attr("style", "display:table-cell!important;visibility:visible!important;padding:24px 20px;font:14px Arial,sans-serif;color:#374151;text-align:" + alignment + ";");
        cell.appendElement("p").attr("style", "display:block!important;font-size:14px!important;color:#374151!important;margin:0 0 12px;")
                .text("You’re receiving emails from " + (listName == null || listName.isBlank() ? "this mailing list" : listName) + ".");
        String linkStyle = "display:inline-block!important;visibility:visible!important;opacity:1!important;color:#1f2937!important;font:14px Arial,sans-serif!important;";
        linkStyle += "outlined-button".equals(settings.appearance())
                ? "border:1px solid #374151;padding:12px 18px;text-decoration:none;" : "text-decoration:underline!important;padding:8px 0;";
        cell.appendElement("a").attr("href", url).attr("style", linkStyle).text(settings.label());
        // Explicit resets protect the managed section from safe inherited/global email defaults.
        for (Element element : footer.getAllElements()) {
            StringBuilder forced = new StringBuilder("background-color:#ffffff!important;background-image:none!important;font-family:Arial,sans-serif!important;font-size:14px!important;line-height:1.5!important;");
            if (element.tagName().equals("table")) forced.append("border-spacing:0!important;border-collapse:collapse!important;");
            for (String declaration : element.attr("style").split(";")) {
                if (!declaration.isBlank()) forced.append(declaration.replace("!important", "")).append("!important;");
            }
            element.attr("style", forced.toString());
        }
        validateGlobalStyles(document, footer);
        document.outputSettings().prettyPrint(false);
        return document.outerHtml();
    }

    private static void validateConditionalComments(Document document) {
        var pending = new java.util.ArrayDeque<org.jsoup.nodes.Node>();
        pending.add(document);
        while (!pending.isEmpty()) {
            org.jsoup.nodes.Node node = pending.removeFirst();
            if (node instanceof org.jsoup.nodes.Comment comment) {
                String data = comment.getData().trim();
                // Outlook interprets conditional comments as active markup; preserve only our exact safe DPI settings.
                if (data.toLowerCase(java.util.Locale.ROOT).contains("[if") && !data.matches(
                        "(?is)\\[if mso\\]><xml><o:OfficeDocumentSettings><o:AllowPNG\\s*/><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><!\\[endif\\]"))
                    throw new BadRequestException("Unsupported conditional email markup");
            }
            pending.addAll(node.childNodes());
        }
    }

    private static final Set<String> SAFE_GLOBAL_STYLE_PROPERTIES = Set.of(
            "font-family", "font-size", "font-weight", "font-style", "line-height", "color", "background-color",
            "text-align", "text-decoration", "border-collapse", "border-spacing", "-webkit-text-size-adjust", "-ms-text-size-adjust");

    private static void rejectGeneratedSource(Document document) {
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href");
            if (href.equals("#unsubscribe-preview")) throw new BadRequestException("Preview footer must not be stored in source HTML");
            if (href.matches("https://[^/]+/unsubscribe#token=[A-Za-z0-9_-]{43}"))
                throw new BadRequestException("Recipient-specific footer must not be stored in source HTML");
        }
    }

    private static void validateGlobalStyles(Document document, Element footer) {
        for (Element root : document.select("html,body")) {
            if (root.hasAttr("hidden") || root.hasAttr("inert")) throw new BadRequestException("Email body must remain visible");
            validateProtectedDeclarations(root.attr("style"));
        }
        for (Element stylesheet : document.select("style")) validateStyleRules(stylesheet.data(), document, footer);
    }

    /** Restrict only CSS capable of matching roots/managed footer; author table classes retain their layout. */
    private static void validateStyleRules(String css, Document document, Element footer) {
        css = css.replaceAll("(?s)/\\*.*?\\*/", "").trim();
        int position = 0;
        while (position < css.length()) {
            int open = css.indexOf('{', position);
            if (open < 0) {
                if (!css.substring(position).isBlank()) throw new BadRequestException("Unsupported email stylesheet");
                break;
            }
            String selector = css.substring(position, open).trim();
            int nesting = 1, close = open + 1;
            while (close < css.length() && nesting > 0) {
                if (css.charAt(close) == '{') nesting++;
                if (css.charAt(close) == '}') nesting--;
                close++;
            }
            if (nesting != 0) throw new BadRequestException("Invalid email stylesheet");
            String declarations = css.substring(open + 1, close - 1);
            if (selector.toLowerCase(java.util.Locale.ROOT).startsWith("@media ")) {
                validateStyleRules(declarations, document, footer);
            } else {
                rejectOverlappingDeclarations(declarations);
                if (selector.startsWith("@") || selector.contains("\\") || selector.contains("::"))
                    throw new BadRequestException("Unsupported email stylesheet selector");
                try {
                    for (Element matched : document.select(selector)) {
                        if (matched == document.body() || matched.tagName().equals("html")
                                || matched == footer || matched.parents().contains(footer)) {
                            validateProtectedDeclarations(declarations);
                            break;
                        }
                    }
                } catch (org.jsoup.select.Selector.SelectorParseException e) {
                    throw new BadRequestException("Unsupported email stylesheet selector");
                }
            }
            position = close;
        }
    }

    private static void rejectOverlappingDeclarations(String style) {
        if (style.contains("\\")) throw new BadRequestException("Unsupported email style escape");
        String normalized = style.replaceAll("(?s)/\\*.*?\\*/", "").toLowerCase(java.util.Locale.ROOT);
        for (String declaration : normalized.split(";")) {
            if (declaration.isBlank()) continue;
            String[] pair = declaration.split(":", 2);
            if (pair.length != 2) throw new BadRequestException("Invalid email style");
            String property = pair[0].trim(), value = pair[1].trim().replace("!important", "").trim();
            boolean overlap = Set.of("position", "z-index", "transform", "translate", "rotate", "scale", "filter",
                    "box-shadow", "text-shadow", "top", "right", "bottom", "left", "inset", "zoom").contains(property)
                    || property.startsWith("animation") || property.endsWith("transform") || property.endsWith("filter")
                    || (property.startsWith("margin") && (value.contains("-") || value.contains("calc(") || value.contains("var(")));
            if (overlap) throw new BadRequestException("Email content must not overlap the unsubscribe footer");
            if (value.contains("expression(") || value.contains("javascript:")) throw new BadRequestException("Unsafe email style");
        }
    }

    private static void validateProtectedDeclarations(String style) {
        if (style.contains("\\") || style.toLowerCase(java.util.Locale.ROOT).contains("var("))
            throw new BadRequestException("Unsupported global email style");
        for (String declaration : style.split(";")) {
            if (declaration.isBlank()) continue;
            String[] pair = declaration.split(":", 2);
            if (pair.length != 2) throw new BadRequestException("Invalid global email style");
            String property = pair[0].trim().toLowerCase(java.util.Locale.ROOT);
            String value = pair[1].trim().toLowerCase(java.util.Locale.ROOT).replace("!important", "").trim();
            boolean safeSpacing = Set.of("margin", "margin-top", "margin-bottom", "margin-left", "margin-right",
                    "padding", "padding-top", "padding-bottom", "padding-left", "padding-right").contains(property)
                    && value.matches("(?:0(?:px)?|[1-5]?[0-9]px)(?: +(?:0(?:px)?|[1-5]?[0-9]px)){0,3}");
            boolean safeWidth = property.equals("width") && value.equals("100%");
            boolean safeOutlookReset = Set.of("mso-table-lspace", "mso-table-rspace").contains(property)
                    && Set.of("0", "0pt", "0px").contains(value);
            if (!SAFE_GLOBAL_STYLE_PROPERTIES.contains(property) && !safeSpacing && !safeWidth && !safeOutlookReset)
                throw new BadRequestException("Email styles must not hide or reposition the unsubscribe footer");
            if (value.contains("url(") || value.contains("expression(") || value.contains("@"))
                throw new BadRequestException("Unsupported global email style");
        }
    }

    public static String personalize(String body, String name) {
        return body == null ? null : body.replace("[name]", org.jsoup.nodes.Entities.escape(name == null ? "" : name));
    }

    private static Document bodyDocument(String body) {
        if (body == null || body.isBlank() || body.length() > 2_000_000) throw new BadRequestException("A valid email body is required");
        return Jsoup.parse(body);
    }
    private static void normalizeLegacy(Document document) {
        // Only exact known compatibility destinations; unrelated external anchors survive.
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href");
            if ("{{unsubscribe_url}}".equals(href) || "{{preferences_url}}".equals(href)) anchor.remove();
        }
        // Arbitrary markers carry no authority: never delete surrounding author content based on one.
    }
}
