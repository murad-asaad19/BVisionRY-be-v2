package com.bvisionry.survey.controller;

import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.common.web.RequestContextUtils;
import com.bvisionry.survey.dto.PublicSurveyDto;
import com.bvisionry.survey.dto.SurveySubmitRequest;
import com.bvisionry.survey.dto.SurveySubmitResponseDto;
import com.bvisionry.survey.service.SurveyResponseService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.util.WebUtils;

import java.util.UUID;

@RestController
@RequestMapping("/api/public/surveys")
@RequiredArgsConstructor
@PreAuthorize("permitAll()")
public class PublicSurveyController {

    private static final String COOKIE_NAME = "svy_rid";
    private static final int COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 90; // 90 days

    private final SurveyResponseService responseService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/by-token/{token}")
    public ResponseEntity<PublicSurveyDto> getByToken(@PathVariable UUID token) {
        return ResponseEntity.ok(responseService.getPublicByToken(token));
    }

    @PostMapping("/by-token/{token}/responses")
    public ResponseEntity<SurveySubmitResponseDto> submit(
            @PathVariable UUID token,
            @Valid @RequestBody SurveySubmitRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Rate limit is enforced upstream in SurveySubmitRateLimitFilter so malformed
        // payloads also consume tokens. By the time we get here, the request is within limits.
        String ip = clientIpResolver.resolve(request);
        String ipHash = RequestContextUtils.sha256Hex(token + ":" + ip);
        String cookieId = readOrIssueCookie(request, response);
        String userAgent = request.getHeader("User-Agent");

        SurveySubmitResponseDto result = responseService.submitPublic(
                token, body, ipHash, cookieId, userAgent);
        return ResponseEntity.ok(result);
    }

    private String readOrIssueCookie(HttpServletRequest request, HttpServletResponse response) {
        Cookie existing = WebUtils.getCookie(request, COOKIE_NAME);
        if (existing != null && existing.getValue() != null && !existing.getValue().isBlank()) {
            return existing.getValue();
        }
        String fresh = UUID.randomUUID().toString();
        Cookie cookie = new Cookie(COOKIE_NAME, fresh);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/api/public/surveys");
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return fresh;
    }
}
