package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.bvisionry.programflow.dto.GamificationDto;
import com.bvisionry.programflow.dto.JourneyResponse;
import com.bvisionry.programflow.dto.LeaderboardResponse;
import com.bvisionry.programflow.dto.LearnerCohortDto;
import com.bvisionry.programflow.dto.PlayerResponse;
import com.bvisionry.programflow.dto.SaveAnswersRequest;
import com.bvisionry.programflow.dto.SaveAnswersResponse;
import com.bvisionry.programflow.dto.SubmitResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Learner-facing program flow.
 *
 * <ul>
 *   <li>GET  /api/my/program                          — journey timeline</li>
 *   <li>GET  /api/my/program/tasks/{taskId}           — task player payload</li>
 *   <li>PUT  /api/my/program/tasks/{taskId}/answers   — autosave draft</li>
 *   <li>POST /api/my/program/tasks/{taskId}/submit    — submit task</li>
 *   <li>GET  /api/my/program/leaderboard              — sprint leaderboard</li>
 *   <li>GET  /api/my/program/gamification             — points/streak/level chip</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/my/program", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@Tag(name = "Program Flow (learner)", description = "Journey, task player, leaderboard.")
public class MyProgramController {

    private final MyProgramService service;
    private final ProgramAiService aiService;

    public MyProgramController(MyProgramService service, ProgramAiService aiService) {
        this.service = service;
        this.aiService = aiService;
    }

    /** The cohorts the learner is enrolled in (for the cohort switcher). */
    @GetMapping("/cohorts")
    public List<LearnerCohortDto> cohorts() {
        return service.myCohorts();
    }

    @GetMapping
    public JourneyResponse journey(@RequestParam(required = false) UUID cohortId) {
        return service.journey(cohortId);
    }

    @GetMapping("/tasks/{taskId}")
    public PlayerResponse player(@PathVariable UUID taskId) {
        return service.player(taskId);
    }

    @PutMapping("/tasks/{taskId}/answers")
    public SaveAnswersResponse saveAnswers(
            @PathVariable UUID taskId,
            @Valid @RequestBody SaveAnswersRequest req) {
        return service.saveAnswers(taskId, req.answers());
    }

    @PostMapping("/tasks/{taskId}/submit")
    public SubmitResponse submit(
            @PathVariable UUID taskId,
            @Valid @RequestBody SaveAnswersRequest req) {
        return service.submit(taskId, req.answers());
    }

    @GetMapping("/leaderboard")
    public LeaderboardResponse leaderboard(@RequestParam(required = false) UUID cohortId) {
        return service.leaderboard(cohortId);
    }

    @GetMapping("/gamification")
    public GamificationDto gamification() {
        return service.myGamification();
    }

    /** AI coach hint — SSE: {@code token}* then {@code done} or {@code error}. */
    @PostMapping(value = "/tasks/{taskId}/coach", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter coach(
            @PathVariable UUID taskId,
            @Valid @RequestBody com.bvisionry.programflow.dto.CoachRequest req) {
        return aiService.coach(taskId, req.fieldId(), req.draft());
    }
}
