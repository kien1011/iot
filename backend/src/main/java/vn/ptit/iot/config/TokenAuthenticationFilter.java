package vn.ptit.iot.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.ptit.iot.service.TokenService;

public class TokenAuthenticationFilter extends OncePerRequestFilter {
    public static final String TOKEN_ATTRIBUTE = TokenAuthenticationFilter.class.getName() + ".token";
    private final TokenService tokens;
    public TokenAuthenticationFilter(TokenService tokens) { this.tokens = tokens; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        return path.equals("/api/auth/login") || !(path.startsWith("/api/") || path.equals("/ws"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        var user = tokens.find(token);
        if (user != null) {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, token, List.of()));
            SecurityContextHolder.setContext(context);
            request.setAttribute(TOKEN_ATTRIBUTE, token);
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null) {
            return header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        }
        // Browser WebSocket cannot set Authorization. Offer the token as a second subprotocol.
        if (pathOf(request).equals("/ws")) {
            String protocols = request.getHeader("Sec-WebSocket-Protocol");
            if (protocols != null) {
                String[] values = protocols.split(",");
                if (values.length == 2 && values[0].trim().equals("bearer")) return values[1].trim();
            }
        }
        return null;
    }

    private String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
