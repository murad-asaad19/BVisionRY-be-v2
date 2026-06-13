package com.bvisionry.pipeline.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pin the wire shape of the sealed {@link PostCompletionLinkDto}: the FE
 * branches on {@code kind} as a discriminated union, so an accidental
 * Jackson-config drift (e.g. dropping {@code @JsonTypeInfo} or removing the
 * default {@code kind()} method) would break the FE without any compile
 * signal. This test catches that.
 */
class PostCompletionLinkDtoJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void survey_serializesWithKindDiscriminatorAndSurveyName() throws Exception {
        PostCompletionLinkDto dto = new PostCompletionLinkDto.Survey(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Post-survey",
                "/my/assessments/abc/post-completion-survey",
                "Take it");

        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"kind\":\"SURVEY\"");
        assertThat(json).contains("\"surveyName\":\"Post-survey\"");
        assertThat(json).contains("\"surveyId\":\"00000000-0000-0000-0000-000000000001\"");
        assertThat(json).contains("\"url\":\"/my/assessments/abc/post-completion-survey\"");
        assertThat(json).contains("\"label\":\"Take it\"");
    }

    @Test
    void external_serializesWithKindDiscriminator() throws Exception {
        PostCompletionLinkDto dto = new PostCompletionLinkDto.External(
                "https://example.com/onboard",
                "Continue");

        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"kind\":\"EXTERNAL\"");
        assertThat(json).contains("\"url\":\"https://example.com/onboard\"");
        assertThat(json).contains("\"label\":\"Continue\"");
        assertThat(json).doesNotContain("surveyId");
        assertThat(json).doesNotContain("surveyName");
    }
}
