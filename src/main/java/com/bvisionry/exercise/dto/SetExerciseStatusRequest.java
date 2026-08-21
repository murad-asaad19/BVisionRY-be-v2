package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;
import jakarta.validation.constraints.NotNull;

/** Body for the super-admin status override: the target status, any value. */
public record SetExerciseStatusRequest(@NotNull ExerciseSubmissionStatus status) {}
