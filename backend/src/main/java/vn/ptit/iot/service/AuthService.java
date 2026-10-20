package vn.ptit.iot.service;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.repository.UserRepository;
@Service
public class AuthService {
    private final UserRepository users;
    private final AuthenticationManager authentication;
    public AuthService(UserRepository users, AuthenticationManager authentication) {
        this.users = users; this.authentication = authentication;
    }
    public UserResponse authenticate(LoginRequest request) {
        try {
            var result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                request.username() == null ? "" : request.username().trim(), request.password()));
            return me(result.getName());
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect username or password.", ex);
        }
    }
    public UserResponse me(String username) {
        var u = users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new UserResponse(u.getId(), u.getUsername(), u.getFullName());
    }
}
