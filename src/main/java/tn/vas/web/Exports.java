package tn.vas.web;

import com.lowagie.text.Document;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;

/** Exports CSV / XLSX / PDF de tableaux de lignes (liste de maps ordonnées). */
final class Exports {
    private Exports() {}

    static ResponseEntity<byte[]> respond(String format, String name, String title, List<? extends Map<String, ?>> rows) throws IOException {
        List<String> cols = new ArrayList<>();
        for (var r : rows) for (String k : r.keySet()) if (!cols.contains(k)) cols.add(k);
        byte[] body;
        MediaType type;
        String ext;
        switch (format) {
            case "xlsx" -> { body = xlsx(title, cols, rows); type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); ext = "xlsx"; }
            case "pdf" -> { body = pdf(title, cols, rows); type = MediaType.APPLICATION_PDF; ext = "pdf"; }
            default -> { body = csv(cols, rows); type = MediaType.parseMediaType("text/csv; charset=utf-8"); ext = "csv"; }
        }
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "." + ext + "\"").body(body);
    }

    /** Neutralise l'injection de formules dans Excel/LibreOffice (préfixe ' pour = + - @). */
    static String safe(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+([.,]\\d+)?") ? "'" + s : s;
    }

    private static byte[] csv(List<String> cols, List<? extends Map<String, ?>> rows) {
        StringBuilder sb = new StringBuilder("﻿").append(String.join(";", cols)).append('\n');
        for (var r : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : cols) cells.add("\"" + safe(r.get(c)).replace("\"", "\"\"") + "\"");
            sb.append(String.join(";", cells)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] xlsx(String title, List<String> cols, List<? extends Map<String, ?>> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            String sheetName = title.replaceAll("[\\\\/?*\\[\\]:]", "-").strip(); // Excel interdit \ / ? * [ ] : dans un nom de feuille (max 31 caractères)
            Sheet sh = wb.createSheet(sheetName.isEmpty() ? "Export" : sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName);
            org.apache.poi.ss.usermodel.Row h = sh.createRow(0);
            for (int i = 0; i < cols.size(); i++) h.createCell(i).setCellValue(cols.get(i));
            int n = 1;
            for (var r : rows) {
                org.apache.poi.ss.usermodel.Row row = sh.createRow(n++);
                for (int i = 0; i < cols.size(); i++) {
                    Object v = r.get(cols.get(i));
                    if (v instanceof Number num) row.createCell(i).setCellValue(num.doubleValue());
                    else row.createCell(i).setCellValue(safe(v));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdf(String title, List<String> cols, List<? extends Map<String, ?>> rows) {
        var out = new ByteArrayOutputStream();
        Document d = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(d, out);
        d.open();
        d.add(new Paragraph(title, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        d.add(new Paragraph(" "));
        if (!cols.isEmpty()) {
            PdfPTable t = new PdfPTable(cols.size());
            t.setWidthPercentage(100);
            var hf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
            var bf = FontFactory.getFont(FontFactory.HELVETICA, 8);
            for (String c : cols) t.addCell(new Phrase(c, hf));
            for (var r : rows) for (String c : cols) t.addCell(new Phrase(String.valueOf(r.get(c) == null ? "" : r.get(c)), bf));
            d.add(t);
        }
        d.close();
        return out.toByteArray();
    }
}
