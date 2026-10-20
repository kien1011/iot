package vn.ptit.iot.controller;
import java.security.Principal;
import java.util.List;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.service.AuthService;
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessionAuthentication;
    public AuthController(AuthService auth, SecurityContextRepository contexts, SessionAuthenticationStrategy sessionAuthentication) {
        this.auth = auth; this.contexts = contexts; this.sessionAuthentication = sessionAuthentication;
    }
    @GetMapping("/csrf") public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }
    @PostMapping("/login") public UserResponse login(@RequestBody LoginRequest body,
        HttpServletRequest request, HttpServletResponse response) {
        UserResponse user = auth.authenticate(body);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user.username(), null, List.of());
        sessionAuthentication.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return user;
    }
    @GetMapping("/me") public UserResponse me(Principal principal) { return auth.me(principal.getName()); }
}
