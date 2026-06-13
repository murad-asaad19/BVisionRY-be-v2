package com.bvisionry.pipeline.dto;

import java.util.List;
import java.util.UUID;

public record PillarPreviewResponse(
        UUID id,
        String name,
        String description,
        String iconKey,
        int displayOrder,
        List<QuestionPreviewResponse> questions
) {}
