package com.campussphere.backend.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A small, defensive reader and writer for Excel .xlsx workbooks (Office Open XML), so timetable import does not
 * need a large spreadsheet library. Reading takes only the first worksheet's cell text. Safety limits: file size,
 * number of zip entries, total unpacked size (zip bombs), macro-enabled workbooks are refused, and XML DTDs and
 * external entities are disabled.
 */
final class Xlsx {
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final long MAX_UNPACKED_BYTES = 30L * 1024 * 1024;
    static final int MAX_ENTRIES = 300;
    static final int MAX_ROWS = 1_001;
    static final int MAX_COLUMNS = 30;

    /** A user-facing reason the file cannot be read. */
    static final class Invalid extends Exception {
        Invalid(String message) { super(message); }
    }

    record Sheet(String name, List<List<String>> rows) {}

    private Xlsx() {}

    /** Rows of the first worksheet; empty cells are "" and trailing empty rows are dropped. */
    static List<List<String>> read(byte[] bytes) throws Invalid {
        if (bytes == null || bytes.length < 4) throw new Invalid("The file is empty.");
        if (bytes.length > MAX_FILE_BYTES) throw new Invalid("The file is larger than 2 MB.");
        if (bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4)
            throw new Invalid("This is not an Excel .xlsx file. Save it as Excel Workbook (.xlsx) and try again.");
        Map<String, byte[]> parts = unzip(bytes);
        if (parts.containsKey("xl/vbaProject.bin") || new String(parts.getOrDefault("[Content_Types].xml", new byte[0]),
                StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("macroenabled"))
            throw new Invalid("Workbooks with macros are not accepted. Save it as a plain .xlsx file.");
        if (!parts.containsKey("xl/workbook.xml")) throw new Invalid("This is not an Excel workbook.");
        // Genuine workbooks never declare a DTD; refusing them rules out entity tricks (XXE, entity expansion).
        for (byte[] part : parts.values()) {
            if (new String(part, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT).contains("<!DOCTYPE"))
                throw new Invalid("The workbook contains a document type declaration, which is not allowed.");
        }
        try {
            String sheetPath = firstSheetPath(parts);
            byte[] sheet = parts.get(sheetPath);
            if (sheet == null) throw new Invalid("The workbook has no worksheet.");
            List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            return rows(sheet, shared);
        } catch (XMLStreamException | RuntimeException e) {
            throw new Invalid("The workbook could not be read. Open it in Excel, save it as .xlsx and try again.");
        }
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Invalid {
        var parts = new HashMap<String, byte[]>();
        long total = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new Invalid("The workbook has too many parts.");
                String name = entry.getName();
                boolean wanted = name.equals("[Content_Types].xml") || name.equals("xl/workbook.xml")
                        || name.equals("xl/_rels/workbook.xml.rels") || name.equals("xl/sharedStrings.xml")
                        || name.equals("xl/vbaProject.bin") || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"));
                var out = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_UNPACKED_BYTES) throw new Invalid("The workbook is too large when unpacked.");
                    if (wanted) out.write(buffer, 0, n);
                }
                if (wanted) parts.put(name, out.toByteArray());
            }
        } catch (IOException e) {
            throw new Invalid("This is not a valid Excel .xlsx file.");
        }
        return parts;
    }

    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    private static String attr(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++) if (r.getAttributeLocalName(i).equals(local)) return r.getAttributeValue(i);
        return null;
    }

    /** The first sheet listed in the workbook, resolved through the workbook relationships. */
    private static String firstSheetPath(Map<String, byte[]> parts) throws XMLStreamException {
        String relId = null;
        var r = xml(parts.get("xl/workbook.xml"));
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) { relId = attr(r, "id"); break; }
        }
        if (relId != null && parts.containsKey("xl/_rels/workbook.xml.rels")) {
            var rels = xml(parts.get("xl/_rels/workbook.xml.rels"));
            while (rels.hasNext()) {
                if (rels.next() == XMLStreamConstants.START_ELEMENT && rels.getLocalName().equals("Relationship")
                        && relId.equals(attr(rels, "Id"))) {
                    String target = attr(rels, "Target");
                    if (target == null || target.contains("..")) break;
                    return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                }
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> sharedStrings(byte[] bytes) throws XMLStreamException {
        var strings = new ArrayList<String>();
        var r = xml(bytes);
        StringBuilder current = null;
        boolean inText = false;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (r.getLocalName().equals("si")) current = new StringBuilder();
                else if (r.getLocalName().equals("t")) inText = true;
            } else if (event == XMLStreamConstants.CHARACTERS && inText && current != null) {
                current.append(r.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("t")) inText = false;
                else if (r.getLocalName().equals("si") && current != null) {
                    strings.add(current.toString());
                    current = null;
                    if (strings.size() > 50_000) throw new IllegalStateException("too many strings");
                }
            }
        }
        return strings;
    }

    private static List<List<String>> rows(byte[] bytes, List<String> shared) throws XMLStreamException {
        var rows = new ArrayList<List<String>>();
        var r = xml(bytes);
        List<String> row = null;
        int column = -1;
        String type = null;
        StringBuilder value = null;
        boolean capture = false;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        int index = parseIndex(attr(r, "r"), rows.size() + 1) - 1;
                        if (index >= MAX_ROWS + 50) return trim(rows);
                        while (rows.size() < index) rows.add(new ArrayList<>());
                        row = new ArrayList<>();
                    }
                    case "c" -> {
                        String ref = attr(r, "r");
                        column = ref == null ? (row == null ? 0 : row.size()) : columnIndex(ref);
                        type = attr(r, "t");
                        value = new StringBuilder();
                    }
                    case "v", "t" -> capture = value != null;
                    default -> { }
                }
            } else if (event == XMLStreamConstants.CHARACTERS && capture) {
                value.append(r.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> capture = false;
                    case "c" -> {
                        if (row != null && column >= 0 && column < MAX_COLUMNS) {
                            String text = value == null ? "" : value.toString();
                            if ("s".equals(type)) {
                                int i = text.isBlank() ? -1 : Integer.parseInt(text.trim());
                                text = i >= 0 && i < shared.size() ? shared.get(i) : "";
                            } else if ("b".equals(type)) {
                                text = "1".equals(text.trim()) ? "TRUE" : "FALSE";
                            }
                            while (row.size() < column) row.add("");
                            if (row.size() == column) row.add(text.strip());
                        }
                        value = null;
                        type = null;
                    }
                    case "row" -> {
                        if (row != null) rows.add(row);
                        row = null;
                    }
                    default -> { }
                }
            }
        }
        return trim(rows);
    }

    private static List<List<String>> trim(List<List<String>> rows) {
        int last = rows.size() - 1;
        while (last >= 0 && rows.get(last).stream().allMatch(String::isBlank)) last--;
        return new ArrayList<>(rows.subList(0, last + 1));
    }

    private static int parseIndex(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "C12" → 2. */
    static int columnIndex(String ref) {
        int index = 0;
        for (char ch : ref.toCharArray()) {
            if (ch < 'A' || ch > 'Z') break;
            index = index * 26 + (ch - 'A' + 1);
        }
        return index - 1;
    }

    static String columnName(int index) {
        var name = new StringBuilder();
        for (int i = index + 1; i > 0; i = (i - 1) / 26) name.insert(0, (char) ('A' + (i - 1) % 26));
        return name.toString();
    }

    // ---- Writing ----

    /** A workbook with the given sheets; the first row of each sheet is bold. All cells are text. */
    static byte[] write(List<Sheet> sheets) {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            var types = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                    <Default Extension="xml" ContentType="application/xml"/>\
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""");
            var book = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""");
            var rels = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""");
            for (int i = 0; i < sheets.size(); i++) {
                int n = i + 1;
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(n)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                book.append("<sheet name=\"").append(escape(sheets.get(i).name())).append("\" sheetId=\"").append(n)
                        .append("\" r:id=\"rId").append(n).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(n)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(n).append(".xml\"/>");
            }
            rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            put(zip, "[Content_Types].xml", types.append("</Types>").toString());
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                    </Relationships>""");
            put(zip, "xl/workbook.xml", book.append("</sheets></workbook>").toString());
            put(zip, "xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>\
                    <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>\
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
                    <cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
                    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>\
                    <xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>\
                    </styleSheet>""");
            for (int i = 0; i < sheets.size(); i++) put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** Text cells only; the body cells use the Text number format so times like 09:00 stay as typed. */
    private static String sheetXml(Sheet sheet) {
        int width = sheet.rows().stream().mapToInt(List::size).max().orElse(1);
        var xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols>""");
        xml.append("<col min=\"1\" max=\"").append(width).append("\" width=\"20\" style=\"2\" customWidth=\"1\"/></cols><sheetData>");
        for (int r = 0; r < sheet.rows().size(); r++) {
            xml.append("<row r=\"").append(r + 1).append("\">");
            var cells = sheet.rows().get(r);
            for (int c = 0; c < cells.size(); c++) {
                xml.append("<c r=\"").append(columnName(c)).append(r + 1).append("\" t=\"inlineStr\" s=\"")
                        .append(r == 0 ? 1 : 2).append("\"><is><t xml:space=\"preserve\">")
                        .append(escape(cells.get(c))).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String escape(String text) {
        var out = new StringBuilder();
        for (char ch : (text == null ? "" : text).toCharArray()) {
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> { if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') out.append(ch); }
            }
        }
        return out.toString();
    }
}
