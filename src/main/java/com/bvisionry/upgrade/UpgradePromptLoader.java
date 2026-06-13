package com.bvisionry.upgrade;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Loads the static upgrade-prompt copy (and cooldown duration) from
 * {@code upgrade/upgrade-prompt.properties} once at startup. To change the
 * prompt, edit the file and redeploy — there is no admin UI for it.
 *
 * <p>Reads through a UTF-8 {@link Reader} so non-ASCII characters in the
 * copy (e.g. the ✓ on the cooldown headline) survive without manual unicode
 * escapes in the properties file.
 *
 * <p>Fails fast at startup if the file is missing, malformed, or any required
 * key is blank — better than rendering a half-broken gate at runtime.
 */
@Component
public class UpgradePromptLoader {

    private static final String RESOURCE_PATH = "upgrade/upgrade-prompt.properties";
    private static final String BULLET_SEPARATOR = "\\|";

    public record UpgradePrompt(
            String headline,
            List<String> bullets,
            String noteLabel,
            String notePlaceholder,
            String buttonLabel,
            String helperText,
            String cooldownHeadline,
            String cooldownBody,
            int cooldownHours
    ) {}

    private final UpgradePrompt prompt;

    public UpgradePromptLoader() {
        this.prompt = load();
    }

    public UpgradePrompt get() {
        return prompt;
    }

    private static UpgradePrompt load() {
        Properties props = new Properties();
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load upgrade prompt from classpath:" + RESOURCE_PATH, e);
        }
        try {
            return new UpgradePrompt(
                    requireNonBlank(props, "headline"),
                    parseBullets(requireNonBlank(props, "bullets")),
                    requireNonBlank(props, "note.label"),
                    requireNonBlank(props, "note.placeholder"),
                    requireNonBlank(props, "button.label"),
                    requireNonBlank(props, "helper.text"),
                    requireNonBlank(props, "cooldown.headline"),
                    requireNonBlank(props, "cooldown.body"),
                    parsePositiveInt(props, "cooldown.hours")
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Malformed upgrade prompt in classpath:" + RESOURCE_PATH + " — " + e.getMessage(), e);
        }
    }

    private static String requireNonBlank(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing or blank key '" + key + "'");
        }
        return value.trim();
    }

    private static List<String> parseBullets(String raw) {
        List<String> bullets = Arrays.stream(raw.split(BULLET_SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (bullets.isEmpty()) {
            throw new IllegalArgumentException("'bullets' must contain at least one entry");
        }
        return bullets;
    }

    private static int parsePositiveInt(Properties props, String key) {
        String raw = requireNonBlank(props, key);
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("key '" + key + "' must be an integer, got '" + raw + "'");
        }
        if (value <= 0) {
            throw new IllegalArgumentException("key '" + key + "' must be > 0, got " + value);
        }
        return value;
    }
}
