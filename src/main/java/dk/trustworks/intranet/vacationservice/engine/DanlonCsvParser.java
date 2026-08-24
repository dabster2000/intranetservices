package dk.trustworks.intranet.vacationservice.engine;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the Danløn "Feriepengeforpligtelse" CSV export: semicolon
 * separated, Danish number format (comma decimal, dot thousands), header row
 * with a rolling window of ferieår columns:
 *
 * <pre>Navn;Bogføringsgruppe;Optjent dage 2024;Afholdt dage 2024;Optjent 2024;Hensættes 2024;Optjent dage 2025;…</pre>
 *
 * <p>Year columns are detected dynamically from the header — future exports
 * carry different years. The DKK columns are captured verbatim (never
 * interpreted; the system tracks days only).</p>
 */
public final class DanlonCsvParser {

    private static final Pattern EARNED_DAYS = Pattern.compile("^Optjent dage (\\d{4})$");
    private static final Pattern USED_DAYS = Pattern.compile("^Afholdt dage (\\d{4})$");
    private static final Pattern EARNED_KR = Pattern.compile("^Optjent (\\d{4})$");
    private static final Pattern PROVISION_KR = Pattern.compile("^Hensættes (\\d{4})$");

    public record YearFigures(Integer earnedDaysCol, Integer usedDaysCol, Integer earnedKrCol, Integer provisionKrCol) {
        YearFigures withEarnedDays(int i) { return new YearFigures(i, usedDaysCol, earnedKrCol, provisionKrCol); }
        YearFigures withUsedDays(int i) { return new YearFigures(earnedDaysCol, i, earnedKrCol, provisionKrCol); }
        YearFigures withEarnedKr(int i) { return new YearFigures(earnedDaysCol, usedDaysCol, i, provisionKrCol); }
        YearFigures withProvisionKr(int i) { return new YearFigures(earnedDaysCol, usedDaysCol, earnedKrCol, i); }
    }

    public record YearValues(double earnedDays, double usedDays, String earnedKrRaw, String provisionKrRaw) {
    }

    public record ParsedRow(int lineNo, String name, String bogfoeringsgruppe, Map<Integer, YearValues> years) {
    }

    public record ParsedCsv(List<Integer> ferieaar, List<ParsedRow> rows) {
    }

    private DanlonCsvParser() {
    }

    public static ParsedCsv parse(byte[] content) {
        return parse(decode(content));
    }

    public static ParsedCsv parse(String content) {
        // A browser's file.text() keeps the UTF-8 BOM as U+FEFF; trim() won't.
        if (!content.isEmpty() && content.charAt(0) == '﻿') {
            content = content.substring(1);
        }
        List<String> lines = content.lines().toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("The file is empty");
        }

        String[] header = splitLine(lines.get(0));
        if (header.length < 2 || !"Navn".equalsIgnoreCase(header[0].trim())) {
            throw new IllegalArgumentException(
                    "Unrecognized header — expected a Danløn feriepengeforpligtelse export starting with 'Navn;Bogføringsgruppe;…'");
        }

        Map<Integer, YearFigures> columns = detectYearColumns(header);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("No 'Optjent dage <year>' columns found in the header");
        }
        for (Map.Entry<Integer, YearFigures> e : columns.entrySet()) {
            if (e.getValue().earnedDaysCol() == null || e.getValue().usedDaysCol() == null) {
                throw new IllegalArgumentException(
                        "Year " + e.getKey() + " is missing its 'Optjent dage' or 'Afholdt dage' column");
            }
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = splitLine(lines.get(i));
            String name = cells.length > 0 ? cells[0].trim() : "";
            if (name.isEmpty()) continue; // Danløn appends an all-empty trailer row

            Map<Integer, YearValues> years = new LinkedHashMap<>();
            for (Map.Entry<Integer, YearFigures> e : columns.entrySet()) {
                YearFigures cols = e.getValue();
                years.put(e.getKey(), new YearValues(
                        parseDanishNumber(cell(cells, cols.earnedDaysCol()), i + 1, "Optjent dage " + e.getKey()),
                        parseDanishNumber(cell(cells, cols.usedDaysCol()), i + 1, "Afholdt dage " + e.getKey()),
                        cell(cells, cols.earnedKrCol()),
                        cell(cells, cols.provisionKrCol())));
            }
            rows.add(new ParsedRow(i + 1, name, cell(cells, 1), years));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file contains no employee rows");
        }
        return new ParsedCsv(columns.keySet().stream().sorted().toList(), rows);
    }

    private static Map<Integer, YearFigures> detectYearColumns(String[] header) {
        Map<Integer, YearFigures> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            final int col = i;
            String h = header[i].trim();
            Matcher m;
            if ((m = EARNED_DAYS.matcher(h)).matches()) {
                columns.merge(Integer.parseInt(m.group(1)), new YearFigures(col, null, null, null),
                        (old, ignored) -> old.withEarnedDays(col));
            } else if ((m = USED_DAYS.matcher(h)).matches()) {
                columns.merge(Integer.parseInt(m.group(1)), new YearFigures(null, col, null, null),
                        (old, ignored) -> old.withUsedDays(col));
            } else if ((m = EARNED_KR.matcher(h)).matches()) {
                columns.merge(Integer.parseInt(m.group(1)), new YearFigures(null, null, col, null),
                        (old, ignored) -> old.withEarnedKr(col));
            } else if ((m = PROVISION_KR.matcher(h)).matches()) {
                columns.merge(Integer.parseInt(m.group(1)), new YearFigures(null, null, null, col),
                        (old, ignored) -> old.withProvisionKr(col));
            }
        }
        return columns;
    }

    private static String[] splitLine(String line) {
        return line.split(";", -1);
    }

    private static String cell(String[] cells, Integer index) {
        if (index == null || index >= cells.length) return "";
        return cells[index].trim();
    }

    /** "118.530,44" → 118530.44 · "27,50" → 27.5 · "" → 0. */
    static double parseDanishNumber(String raw, int lineNo, String column) {
        if (raw == null || raw.isBlank()) return 0.0;
        String cleaned = raw.replace(".", "").replace(",", ".");
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Line " + lineNo + ": '" + raw + "' in column '" + column + "' is not a number");
        }
    }

    /** UTF-8 with a strict decode; falls back to ISO-8859-1 (legacy Danløn exports). */
    static String decode(byte[] content) {
        byte[] body = stripBom(content);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    private static byte[] stripBom(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            byte[] stripped = new byte[content.length - 3];
            System.arraycopy(content, 3, stripped, 0, stripped.length);
            return stripped;
        }
        return content;
    }
}
