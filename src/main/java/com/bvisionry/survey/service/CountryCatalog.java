package com.bvisionry.survey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical ISO-3166-1 alpha-2 country catalog, loaded once at startup from
 * {@code data/countries.json}. That resource is generated from
 * {@code web/src/lib/countries.ts} so the server's validation and display names
 * stay byte-for-byte in sync with the client dropdown and map (e.g. "Türkiye",
 * "Kosovo"/XK) — never the JVM {@link Locale} names, which drift per platform.
 *
 * <p>Fails fast at startup if the resource is missing or malformed.
 */
@Component
public class CountryCatalog {

    private static final String RESOURCE_PATH = "data/countries.json";

    /** ISO alpha-2 (uppercase) -> display name, in catalog order. */
    private final Map<String, String> namesByCode;

    public CountryCatalog() {
        this.namesByCode = load();
    }

    /** Whether {@code code} is a real ISO-3166-1 alpha-2 in the canonical catalog (case-insensitive). */
    public boolean isValidCode(String code) {
        if (code == null) return false;
        return namesByCode.containsKey(code.toUpperCase(Locale.ROOT));
    }

    /** Resolve an alpha-2 code to its canonical display name, falling back to the raw code. */
    public String displayName(String code) {
        if (code == null) return null;
        return namesByCode.getOrDefault(code.toUpperCase(Locale.ROOT), code);
    }

    private static Map<String, String> load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream in = resource.getInputStream()) {
            List<CountryEntry> entries = new ObjectMapper()
                    .readValue(in, new com.fasterxml.jackson.core.type.TypeReference<List<CountryEntry>>() {});
            Map<String, String> map = new LinkedHashMap<>();
            for (CountryEntry e : entries) {
                if (e.code() == null || e.code().isBlank() || e.name() == null || e.name().isBlank()) {
                    throw new IllegalStateException("blank code or name in entry: " + e);
                }
                map.put(e.code().toUpperCase(Locale.ROOT), e.name());
            }
            if (map.isEmpty()) {
                throw new IllegalStateException("country catalog is empty");
            }
            return map;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load country catalog from classpath:" + RESOURCE_PATH, e);
        }
    }

    private record CountryEntry(String code, String name) {}
}
