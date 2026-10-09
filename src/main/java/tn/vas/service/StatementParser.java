package tn.vas.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.*;

/** Lecture des relevés opérateur CSV ou XLSX avec mapping configurable des colonnes (index ou nom d'en-tête). */
public final class StatementParser {
    private StatementParser() {}

    public record Row(String eventId, BigDecimal amount, String status) {}

    /** Mapping : noms d'en-tête (insensibles à la casse) des colonnes id / montant / statut. */
    public record Mapping(String idColumn, String amountColumn, String statusColumn) {
        public static Mapping defaults() { return new Mapping("event_id", "amount", "status"); }
    }

    public static List<Row> parse(InputStream in, String filename, char sep, Mapping m) throws IOException {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? xlsx(in, m) : csv(in, sep, m);
    }

    private static List<Row> csv(InputStream in, char sep, Mapping m) throws IOException {
        List<Row> out = new ArrayList<>();
        try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (line == null) return out;
            List<String> header = Arrays.asList(line.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1));
            int[] idx = indexes(header, m);
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
                out.add(row(c.length > idx[0] ? c[idx[0]] : "", c.length > idx[1] ? c[idx[1]] : "0", idx[2] >= 0 && c.length > idx[2] ? c[idx[2]] : null));
            }
        }
        return out;
    }

    private static List<Row> xlsx(InputStream in, Mapping m) throws IOException {
        List<Row> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            Iterator<org.apache.poi.ss.usermodel.Row> it = sh.iterator();
            if (!it.hasNext()) return out;
            List<String> header = new ArrayList<>();
            for (Cell c : it.next()) header.add(fmt.formatCellValue(c));
            int[] idx = indexes(header, m);
            while (it.hasNext()) {
                var r = it.next();
                String id = cell(r, idx[0], fmt);
                if (id.isBlank()) continue;
                out.add(row(id, cell(r, idx[1], fmt), idx[2] >= 0 ? cell(r, idx[2], fmt) : null));
            }
        }
        return out;
    }

    private static String cell(org.apache.poi.ss.usermodel.Row r, int i, DataFormatter f) {
        Cell c = r.getCell(i);
        if (c == null) return "";
        return c.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(c)
                ? BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString() : f.formatCellValue(c);
    }

    private static int[] indexes(List<String> header, Mapping m) {
        return new int[]{col(header, m.idColumn(), 0, true), col(header, m.amountColumn(), 1, true), col(header, m.statusColumn(), 2, false)};
    }

    private static int col(List<String> header, String name, int dflt, boolean required) {
        for (int i = 0; i < header.size(); i++) if (header.get(i).trim().equalsIgnoreCase(name)) return i;
        if (name != null && name.matches("\\d+")) return Integer.parseInt(name);
        if (required && header.size() > dflt) return dflt; // repli positionnel si l'en-tête ne correspond pas au mapping
        return required ? dflt : (header.size() > dflt ? dflt : -1);
    }

    private static Row row(String id, String amount, String status) {
        return new Row(id.trim(), new BigDecimal(amount.trim().replace(',', '.')), status == null || status.isBlank() ? "CHARGED" : status.trim().toUpperCase(Locale.ROOT));
    }
}
