package com.bvisionry.programflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creates a DRAFT platform cohort — orgs and their rosters come via assignment. */
public record CreateCohortRequest(
        @NotBlank @Size(max = 200) String name) {
}
