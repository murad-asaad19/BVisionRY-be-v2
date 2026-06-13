package com.bvisionry.publicassessment.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Anonymous session-start payload. Whether each field is required is governed
 * by the link's respondent field modes (validated in the service); format
 * constraints live here, mirroring the public survey submit request.
 */
public record PublicAssessmentSessionRequest(
        @Email(message = "Invalid email format")
        String respondentEmail,

        @Size(max = 255, message = "Name must be 255 characters or fewer")
        String respondentName
) {}
