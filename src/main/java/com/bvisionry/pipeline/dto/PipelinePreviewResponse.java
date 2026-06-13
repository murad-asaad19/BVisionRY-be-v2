package com.bvisionry.pipeline.dto;

import java.util.List;
import java.util.UUID;

public record PipelinePreviewResponse(
        UUID id,
        String name,
        String description,
        int version,
        List<PillarPreviewResponse> pillars
) {}
