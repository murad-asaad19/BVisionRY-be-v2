package com.bvisionry.coaching.dto;

import java.util.List;

/** The coach console landing payload: every founder the caller may see. */
public record CoachRosterResponse(List<CoachFounderSummary> founders) {
}
