package com.bvisionry.workshops.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bvisionry.workshops.domain.WorkshopExercise;
import com.bvisionry.workshops.domain.WorkshopExerciseTask;
import com.bvisionry.workshops.domain.WorkshopTaskAssignee;
import com.bvisionry.workshops.domain.WorkshopTaskType;

/** The admin builder payload: every exercise of a workshop with its full task pipeline. */
public record BuilderResponse(List<ExerciseDto> exercises) {

    public record ExerciseDto(UUID id, String title, int position, List<TaskDto> tasks) {
        public static ExerciseDto from(WorkshopExercise e, List<TaskDto> tasks) {
            return new ExerciseDto(e.getId(), e.getTitle(), e.getPosition(), tasks);
        }
    }

    /** Admin view — includes the raw config (answer key and all). */
    public record TaskDto(
            UUID id,
            WorkshopTaskType type,
            WorkshopTaskAssignee assignee,
            String title,
            int position,
            Map<String, Object> config) {
        public static TaskDto from(WorkshopExerciseTask t) {
            return new TaskDto(t.getId(), t.getTaskType(), t.getAssignee(),
                    t.getTitle(), t.getPosition(), t.getConfig());
        }
    }
}
