package vn.ptit.iot.service;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.repository.UserRepository;
import vn.ptit.iot.websocket.LiveUpdates;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final LiveUpdates live;

    public AuthService(UserRepository users, PasswordEncoder passwords,
                       TokenService tokens, LiveUpdates live) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.live = live;
    }

    public LoginResponse login(LoginRequest body) {
        if (body.username() == null || body.username().isBlank() || body.password() == null || body.password().isBlank()) {
            throw new BadCredentialsException("Incorrect username or password.");
        }
        var user = users.findByUsername(body.username().trim())
            .orElseThrow(() -> new BadCredentialsException("Incorrect username or password."));
        if (!passwords.matches(body.password(), user.getPassword())) {
            throw new BadCredentialsException("Incorrect username or password.");
        }
        var principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.getFullName());
        return new LoginResponse(user.getId(), user.getUsername(), user.getFullName(), tokens.issue(principal));
    }

    public void logout(String token) {
        tokens.revoke(token);
        live.closeForToken(token);
    }
}
