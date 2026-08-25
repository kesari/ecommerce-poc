package com.poc.account.api;

import com.poc.account.api.dto.LoginRequest;
import com.poc.account.api.dto.RefreshRequest;
import com.poc.account.api.dto.SignupRequest;
import com.poc.account.api.dto.TokenResponse;
import com.poc.account.application.AuthService;
import com.poc.account.domain.exception.InvalidCredentialsException;
import com.poc.account.domain.model.AuthenticationOutcome;
import com.poc.account.domain.model.TokenPair;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created and tokens issued"),
            @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/signup")
    ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(response(auth.signup(request.email(), request.password())));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens issued"),
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return response(auth.login(request.email(), request.password()));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refreshed pair; old refresh token is revoked"),
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return switch (auth.refresh(request.refreshToken())) {
            case AuthenticationOutcome.Authenticated authenticated -> response(authenticated.tokens());
            case AuthenticationOutcome.Invalid invalid -> throw new InvalidCredentialsException();
        };
    }

    private TokenResponse response(TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", auth.accessTtlSeconds());
    }
}
