package vn.ptit.iot.controller;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.service.AuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/login") public LoginResponse login(@RequestBody LoginRequest body) {
        return auth.login(body);
    }
    @GetMapping("/me") public UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return user.response();
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication) {
        auth.logout((String) authentication.getCredentials());
    }
    @ExceptionHandler(AuthenticationException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> badCredentials() { return Map.of("message", "Incorrect username or password."); }
}
