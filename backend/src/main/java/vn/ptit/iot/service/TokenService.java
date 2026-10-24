package vn.ptit.iot.service;

import java.security.SecureRandom;
import java.time.*;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.ptit.iot.dto.AuthenticatedUser;

/** Random bearer tokens are valid until expiry, explicit logout or a backend restart. */
@Service
public class TokenService {
    private record Access(AuthenticatedUser user, Instant expiresAt) {}
    private final Map<String, Access> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration lifetime;

    public TokenService(Clock clock, @Value("${auth.token-ttl-seconds:1800}") long ttlSeconds) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("Token lifetime must be positive.");
        this.clock = clock;
        this.lifetime = Duration.ofSeconds(ttlSeconds);
    }

    public String issue(AuthenticatedUser user) {
        removeExpired();
        String token;
        Access access = new Access(user, clock.instant().plus(lifetime));
        do {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (tokens.putIfAbsent(token, access) != null);
        return token;
    }

    public AuthenticatedUser find(String token) {
        if (token == null) return null;
        var access = tokens.get(token);
        if (access == null) return null;
        if (!clock.instant().isBefore(access.expiresAt())) {
            tokens.remove(token, access);
            return null;
        }
        return access.user();
    }

    public void revoke(String token) { if (token != null) tokens.remove(token); }

    public void removeExpired() {
        var now = clock.instant();
        tokens.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }
}
