package com.campussphere.backend.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XlsxTest {
    static byte[] zip(Map<String, String> parts) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, String> workbook(String sheetXml) {
        var parts = new LinkedHashMap<String, String>();
        parts.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
        parts.put("xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                + "<sheet name=\"Timetable\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        parts.put("xl/sharedStrings.xml", "<sst><si><t>Division</t></si><si><r><t>Mon</t></r><r><t>day</t></r></si></sst>");
        parts.put("xl/worksheets/sheet1.xml", sheetXml);
        return parts;
    }

    @Test void writtenWorkbooksReadBackWithTextIntact() throws Exception {
        var bytes = Xlsx.write(List.of(new Xlsx.Sheet("Timetable", List.of(
                List.of("Division", "Day", "Start"), List.of("A", "Monday", "09:00"), List.of("B & C", "", "<10:00>")))));
        var rows = Xlsx.read(bytes);
        assertEquals(List.of("Division", "Day", "Start"), rows.get(0));
        assertEquals(List.of("A", "Monday", "09:00"), rows.get(1));
        assertEquals(List.of("B & C", "", "<10:00>"), rows.get(2));
    }

    @Test void sharedStringsNumbersAndGapsAreRead() throws Exception {
        var rows = Xlsx.read(zip(workbook("<worksheet><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>1</v></c></row>"
                + "<row r=\"3\"><c r=\"B3\"><v>0.375</v></c><c r=\"D3\" t=\"inlineStr\"><is><t> Lab </t></is></c></row>"
                + "<row r=\"4\"/></sheetData></worksheet>")));
        assertEquals(List.of("Division", "", "Monday"), rows.get(0));
        assertEquals(List.of(), rows.get(1));            // missing row 2 becomes an empty row
        assertEquals(List.of("", "0.375", "", "Lab"), rows.get(2));
        assertEquals(3, rows.size());                    // trailing empty row dropped
    }

    @Test void unsafeOrWrongFilesAreRefused() throws Exception {
        assertThrows(Xlsx.Invalid.class, () -> Xlsx.read("Division,Day\nA,Monday".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Xlsx.Invalid.class, () -> Xlsx.read(new byte[0]));
        assertThrows(Xlsx.Invalid.class, () -> Xlsx.read(new byte[Xlsx.MAX_FILE_BYTES + 1]));

        var macro = workbook("<worksheet><sheetData/></worksheet>");
        macro.put("xl/vbaProject.bin", "binary");
        var refused = assertThrows(Xlsx.Invalid.class, () -> Xlsx.read(zip(macro)));
        assertTrue(refused.getMessage().contains("macros"));

        // External entities (XXE) are never resolved.
        var xxe = workbook("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY secret SYSTEM \"file:///C:/Windows/win.ini\">]>"
                + "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>&secret;</t></is></c></row></sheetData></worksheet>");
        assertThrows(Xlsx.Invalid.class, () -> Xlsx.read(zip(xxe)));
    }

    @Test void zipBombsAreStoppedByTheUnpackedLimit() throws Exception {
        var big = workbook("<worksheet><sheetData/></worksheet>");
        big.put("xl/worksheets/sheet2.xml", " ".repeat((int) Xlsx.MAX_UNPACKED_BYTES + 10));
        byte[] bytes = zip(big);
        assertTrue(bytes.length < Xlsx.MAX_FILE_BYTES);   // small on disk, huge when unpacked
        var refused = assertThrows(Xlsx.Invalid.class, () -> Xlsx.read(bytes));
        assertTrue(refused.getMessage().contains("unpacked"));
    }

    @Test void columnNamesAndIndexesMatch() {
        assertEquals(0, Xlsx.columnIndex("A1"));
        assertEquals(27, Xlsx.columnIndex("AB12"));
        assertEquals("A", Xlsx.columnName(0));
        assertEquals("AB", Xlsx.columnName(27));
        assertEquals("a &amp; &lt;b&gt;", Xlsx.escape("a & <b>"));
    }
}
