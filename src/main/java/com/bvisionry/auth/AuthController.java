package com.bvisionry.auth;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.auth.dto.ChangePasswordRequest;
import com.bvisionry.auth.dto.DownloadTokenResponse;
import com.bvisionry.auth.dto.ForgotPasswordRequest;
import com.bvisionry.auth.dto.LoginRequest;
import com.bvisionry.auth.dto.RefreshTokenRequest;
import com.bvisionry.auth.dto.RegisterRequest;
import com.bvisionry.auth.dto.ResetPasswordRequest;
import com.bvisionry.auth.dto.UserResponse;
import com.bvisionry.auth.entity.User;
import com.bvisionry.auth.jwt.JwtProvider;
import com.bvisionry.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final CookieService cookieService;
    private final JwtProvider jwtProvider;
    private final String publicBaseUrl;

    public AuthController(AuthService authService,
                          PasswordResetService passwordResetService,
                          RateLimitService rateLimitService,
                          ClientIpResolver clientIpResolver,
                          CookieService cookieService,
                          JwtProvider jwtProvider,
                          @Value("${bvisionry.public.base-url}") String publicBaseUrl) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.cookieService = cookieService;
        this.jwtProvider = jwtProvider;
        this.publicBaseUrl = publicBaseUrl;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                  HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        rateLimitService.checkAuthLimit(clientIpResolver.resolve(httpRequest));
        AuthResponse response = authService.register(request, contextOf(httpRequest));
        writeAuthCookies(httpResponse, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        rateLimitService.checkAuthLimit(clientIpResolver.resolve(httpRequest));
        AuthResponse response = authService.login(request, contextOf(httpRequest));
        writeAuthCookies(httpResponse, response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) RefreshTokenRequest request,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        rateLimitService.checkRefreshLimit(clientIpResolver.resolve(httpRequest));

        String token = cookieService.readRefreshToken(httpRequest)
                .orElseGet(() -> request == null ? null : request.refreshToken());

        AuthResponse response = authService.refresh(token, contextOf(httpRequest));
        writeAuthCookies(httpResponse, response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshTokenRequest request,
                                        HttpServletRequest httpRequest,
                                        HttpServletResponse httpResponse) {
        cookieService.clearAuthCookies(httpResponse);

        String token = cookieService.readRefreshToken(httpRequest)
                .orElseGet(() -> request == null ? null : request.refreshToken());
        authService.logout(token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(UserResponse.from(user));
    }

    /**
     * Issues a short-lived ({@code bvisionry.jwt.download-token-expiration-ms},
     * default 60 s) JWT used by the SPA to authenticate direct browser → Railway
     * binary fetches. Returned alongside this backend's public origin so the FE
     * doesn't hardcode hostnames.
     */
    @GetMapping("/download-token")
    public ResponseEntity<DownloadTokenResponse> downloadToken(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        String token = jwtProvider.generateDownloadToken(user);
        long expiresInSeconds = jwtProvider.getDownloadTokenExpirationMs() / 1000L;
        return ResponseEntity.ok(new DownloadTokenResponse(token, publicBaseUrl, expiresInSeconds));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        rateLimitService.checkAuthLimit(clientIpResolver.resolve(httpRequest));
        authService.changePassword(SecurityUtils.getCurrentUserId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * Always 204 whether or not the email has an account — the response must
     * not reveal which addresses exist. Limited per IP AND per target email so
     * neither a scanning bot nor an inbox-bombing attack gets past the ceiling.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest httpRequest) {
        rateLimitService.checkPasswordResetLimit(clientIpResolver.resolve(httpRequest));
        rateLimitService.checkPasswordResetLimit("email:" + request.email().toLowerCase().trim());
        passwordResetService.requestReset(request.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                              HttpServletRequest httpRequest) {
        rateLimitService.checkAuthLimit(clientIpResolver.resolve(httpRequest));
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    private AuthService.ClientContext contextOf(HttpServletRequest request) {
        return AuthService.ClientContext.of(request.getHeader("User-Agent"), clientIpResolver.resolve(request));
    }

    private void writeAuthCookies(HttpServletResponse response, AuthResponse auth) {
        cookieService.setAccessTokenCookie(response, auth.token());
        cookieService.setRefreshTokenCookie(response, auth.refreshToken());
    }
}
