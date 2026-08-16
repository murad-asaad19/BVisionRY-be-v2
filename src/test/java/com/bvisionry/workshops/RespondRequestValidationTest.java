package com.bvisionry.workshops;

import com.bvisionry.workshops.dto.RespondRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean validation on {@link RespondRequest}: an EMPTY answers list is legal —
 * it is the no-shared-cards escape hatch. The "answer every shared card"
 * invariant lives in MyWorkshopService.respond, not in the DTO.
 */
class RespondRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void anEmptyAnswersListIsLegal() {
        // Goes red if @NotEmpty ever comes back on answers.
        assertThat(validator.validate(new RespondRequest(List.of()))).isEmpty();
    }

    @Test
    void aNullAnswersListIsRejected() {
        Set<ConstraintViolation<RespondRequest>> violations =
                validator.validate(new RespondRequest(null));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo("answers");
    }

    @Test
    void aBlankAnswerTextStillViolatesNotBlank() {
        Set<ConstraintViolation<RespondRequest>> violations = validator.validate(
                new RespondRequest(List.of(new RespondRequest.Answer("c1", " "))));
        assertThat(violations)
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).endsWith("text"));
    }
}
