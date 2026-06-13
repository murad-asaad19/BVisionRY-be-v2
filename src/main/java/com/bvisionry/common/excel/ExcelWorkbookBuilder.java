package com.bvisionry.common.excel;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class ExcelWorkbookBuilder implements AutoCloseable {

    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a 'UTC'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    public static String formatInstant(Instant instant) {
        return instant == null ? "" : DATE_FORMAT.format(instant);
    }

    public static String humanize(String token) {
        if (token == null || token.isBlank()) return "";
        String lower = token.replace('_', ' ').toLowerCase(Locale.ENGLISH).trim();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static String formatPercent(BigDecimal value) {
        if (value == null) return "";
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    public static String bullets(Iterable<?> items) {
        if (items == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Object item : items) {
            if (item == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(item);
        }
        return sb.toString();
    }

    private final XSSFWorkbook workbook;
    private final CellStyle headerStyle;
    private final CellStyle wrappedStyle;
    private final CellStyle labelStyle;

    public ExcelWorkbookBuilder() {
        this.workbook = new XSSFWorkbook();
        this.headerStyle = buildHeaderStyle(workbook);
        this.wrappedStyle = buildWrappedStyle(workbook);
        this.labelStyle = buildLabelStyle(workbook);
    }

    public SheetBuilder newSheet(String desiredName) {
        String safe = WorkbookUtil.createSafeSheetName(desiredName);
        String unique = uniqueSheetName(safe);
        return new SheetBuilder(workbook.createSheet(unique));
    }

    public void write(OutputStream out) throws IOException {
        workbook.write(out);
    }

    @Override
    public void close() throws IOException {
        workbook.close();
    }

    private String uniqueSheetName(String base) {
        if (workbook.getSheet(base) == null) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = WorkbookUtil.createSafeSheetName(base.length() > 28 ? base.substring(0, 28) : base)
                    + " " + i;
            if (workbook.getSheet(candidate) == null) return candidate;
        }
        throw new IllegalStateException("Could not derive unique sheet name from " + base);
    }

    public class SheetBuilder {

        private final Sheet sheet;
        private int rowIndex = 0;
        private int columnCount = 0;

        private SheetBuilder(Sheet sheet) {
            this.sheet = sheet;
        }

        public SheetBuilder headers(String... headers) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = row.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
            columnCount = Math.max(columnCount, headers.length);
            sheet.createFreezePane(0, 1);
            return this;
        }

        public SheetBuilder row(Object... cells) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < cells.length; i++) {
                writeCell(row.createCell(i), cells[i], false);
            }
            columnCount = Math.max(columnCount, cells.length);
            return this;
        }

        public SheetBuilder labeledRow(String label, Object value) {
            Row row = sheet.createRow(rowIndex++);
            Cell labelCell = row.createCell(0);
            labelCell.setCellValue(label);
            labelCell.setCellStyle(labelStyle);
            writeCell(row.createCell(1), value, true);
            columnCount = Math.max(columnCount, 2);
            return this;
        }

        public SheetBuilder blankRow() {
            sheet.createRow(rowIndex++);
            return this;
        }

        public SheetBuilder autoSize() {
            for (int i = 0; i < columnCount; i++) {
                sheet.autoSizeColumn(i);
                int width = sheet.getColumnWidth(i);
                sheet.setColumnWidth(i, Math.min(width + 512, 18_000));
            }
            return this;
        }

        private void writeCell(Cell cell, Object value, boolean wrap) {
            if (value == null) {
                cell.setBlank();
                return;
            }
            switch (value) {
                case String s -> cell.setCellValue(s);
                case Boolean b -> cell.setCellValue(b ? "Yes" : "No");
                case BigDecimal b -> cell.setCellValue(b.doubleValue());
                case Number n -> cell.setCellValue(n.doubleValue());
                case Instant i -> cell.setCellValue(formatInstant(i));
                case Enum<?> e -> cell.setCellValue(humanize(e.name()));
                default -> cell.setCellValue(value.toString());
            }
            if (wrap) cell.setCellStyle(wrappedStyle);
        }
    }

    private static CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static CellStyle buildWrappedStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }

    private static CellStyle buildLabelStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }
}
