package com.bvisionry.programflow.web;

import java.util.List;

import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramSettings;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskField;
import com.bvisionry.programflow.dto.AudienceDto;
import com.bvisionry.programflow.dto.FieldDto;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.TaskDto;

/** Hand-written entity → DTO mapping (no MapStruct in this codebase). */
final class ProgramMapper {

    private ProgramMapper() {
    }

    static FieldDto toDto(ProgramTaskField f) {
        return new FieldDto(f.getId(), f.getFieldType(), f.isRequired(), f.getPosition(), f.getConfig());
    }

    static TaskDto toDto(ProgramTask t) {
        return new TaskDto(
                t.getId(),
                t.getName(),
                t.getDueDate(),
                t.getStatus(),
                t.isAiDraft(),
                t.getPosition(),
                t.getFields().stream().map(ProgramMapper::toDto).toList());
    }

    static ModuleDto toDto(ProgramModule m, int reached) {
        return new ModuleDto(
                m.getId(),
                m.getName(),
                m.getSummary(),
                m.getPosition(),
                m.getLockMode(),
                m.getUnlockAt(),
                new AudienceDto(m.getAssignMode(), List.copyOf(m.getTeamIds()), List.copyOf(m.getMemberIds()), reached),
                m.getTasks().stream().map(ProgramMapper::toDto).toList());
    }

    static ProgramSettingsDto toDto(ProgramSettings s) {
        return s == null
                ? ProgramSettingsDto.defaults()
                : new ProgramSettingsDto(s.getStageLabel(), s.isDripEnabled(), s.getDueSoonDays(),
                        s.getEndLabel(), s.getEndAt());
    }
}
