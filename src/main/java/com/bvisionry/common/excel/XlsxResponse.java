package com.bvisionry.common.excel;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class XlsxResponse {

    private XlsxResponse() {}

    public static ResponseEntity<byte[]> build(byte[] body, String filename, String mode) {
        String disposition = "preview".equalsIgnoreCase(mode) ? "inline" : "attachment";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ExcelWorkbookBuilder.XLSX_CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition + "; filename=\"" + filename + "\"")
                .contentLength(body.length)
                .body(body);
    }

    public static String sanitizeFilename(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^a-zA-Z0-9 _-]", "").replace(" ", "_");
    }
}
