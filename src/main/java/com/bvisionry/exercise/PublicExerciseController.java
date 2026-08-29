package com.bvisionry.exercise;

import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.common.web.RequestContextUtils;
import com.bvisionry.exercise.dto.PublicExerciseDto;
import com.bvisionry.exercise.dto.PublicExerciseSubmitRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The anonymous half of a public exercise — read the exercise by its token,
 * submit one fill. Mirrors {@code PublicSurveyController}, minus the
 * respondent cookie: an exercise fill is one-shot, so there is no second visit
 * to recognise.
 */
@RestController
@RequestMapping("/api/public/exercises")
@RequiredArgsConstructor
@PreAuthorize("permitAll()")
public class PublicExerciseController {

    private final PublicExerciseService publicExerciseService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/by-token/{token}")
    public ResponseEntity<PublicExerciseDto> getByToken(@PathVariable UUID token) {
        return ResponseEntity.ok(publicExerciseService.getByToken(token));
    }

    @PostMapping("/by-token/{token}/responses")
    public ResponseEntity<Void> submit(
            @PathVariable UUID token,
            @Valid @RequestBody PublicExerciseSubmitRequest body,
            HttpServletRequest request) {

        // The rate limit is enforced upstream in PublicExerciseSubmitRateLimitFilter so
        // malformed payloads also consume tokens; by here we are within limits.
        String ipHash = RequestContextUtils.sha256Hex(
                token + ":" + clientIpResolver.resolve(request));
        publicExerciseService.submit(token, body, ipHash, request.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }
}
