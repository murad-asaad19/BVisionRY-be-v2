package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * One anonymous fill of a public exercise. Exactly one of {@code answers}
 * (WORKSHEET) and {@code rows} (SHEET) carries content; the service ignores
 * whichever does not match the exercise's kind.
 *
 * <p>Respondent fields are shaped here only ("is this a plausible email");
 * whether they are asked for at all, and whether blank is allowed, is the
 * exercise's {@code respondent*Mode} and is enforced in the service.
 */
public record PublicExerciseSubmitRequest(
        @Size(max = 200, message = "Name must be at most 200 characters")
        String respondentName,

        @Email(message = "Enter a valid email address")
        @Size(max = 320, message = "Email must be at most 320 characters")
        String respondentEmail,

        /** WORKSHEET: block id → answer. */
        Map<String, Object> answers,

        /** SHEET: the rows in display order, each one columnId → cell. */
        List<Map<String, Object>> rows
) {}
