package com.bvisionry.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * Sealed wire DTO for the post-completion CTA paired to a pipeline. The two
 * variants split fields that used to be intertwined behind a {@code Kind}
 * enum:
 *
 * <ul>
 *   <li>{@link Survey} — authenticated, submission-scoped survey link;
 *       carries {@code surveyId}/{@code surveyName} so callers (e.g. the
 *       results-ready / survey-invite emails) can render them without
 *       another repository round-trip.</li>
 *   <li>{@link External} — opaque off-platform redirect.</li>
 * </ul>
 *
 * <p>Jackson serializes both variants with a {@code "kind"} discriminator
 * (literally {@code "SURVEY"} or {@code "EXTERNAL"}) so the FE can branch on
 * a discriminated union exactly like the Java pattern-match. The
 * {@link com.fasterxml.jackson.annotation.JsonTypeInfo.As#EXISTING_PROPERTY}
 * mode means the {@code kind()} default method on each record contributes
 * the property without Jackson injecting a wrapper or a duplicate field.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = PostCompletionLinkDto.Survey.class, name = "SURVEY"),
        @JsonSubTypes.Type(value = PostCompletionLinkDto.External.class, name = "EXTERNAL")
})
public sealed interface PostCompletionLinkDto
        permits PostCompletionLinkDto.Survey, PostCompletionLinkDto.External {

    /**
     * Discriminator surfaced on the wire so the FE can switch on it.
     * Tagged with {@code @JsonProperty} on the interface method so
     * Jackson serializes it as a regular field (sealed records don't
     * advertise the default method as a bean property by default).
     */
    @JsonProperty("kind")
    String kind();

    String url();

    String label();

    record Survey(UUID surveyId, String surveyName, String url, String label)
            implements PostCompletionLinkDto {
        @Override public String kind() { return "SURVEY"; }
    }

    record External(String url, String label) implements PostCompletionLinkDto {
        @Override public String kind() { return "EXTERNAL"; }
    }
}
