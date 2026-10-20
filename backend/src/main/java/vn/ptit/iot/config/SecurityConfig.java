package vn.ptit.iot.config;
import java.util.List;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import vn.ptit.iot.repository.UserRepository;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.session.HttpSessionEventPublisher;
@Configuration
public class SecurityConfig {
    @Bean HttpSessionEventPublisher sessionEvents() { return new HttpSessionEventPublisher(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService userDetails(UserRepository users) {
        return username -> {
            var user = users.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
            return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password(user.getPassword()).authorities(List.of()).build();
        };
    }
    @Bean AuthenticationManager authenticationManager(UserDetailsService details, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityContextRepository securityContexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository csrfTokens() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy sessionAuthentication(CsrfTokenRepository tokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(tokens)));
    }
    @Bean SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts,
                                       CsrfTokenRepository tokens) throws Exception {
        http.securityContext(c -> c.securityContextRepository(contexts))
            .csrf(c -> c.csrfTokenRepository(tokens))
            .requestCache(c -> c.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/index.html", "/html/login.html", "/css/**", "/js/**", "/vendor/**",
                    "/favicon.ico", "/error", "/api/auth/csrf", "/api/auth/login").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                if (req.getRequestURI().startsWith("/html/")) res.sendRedirect("/html/login.html");
                else res.sendError(HttpStatus.UNAUTHORIZED.value());
            }).accessDeniedHandler((req, res, ex) -> {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                res.sendError(auth == null || auth instanceof AnonymousAuthenticationToken ? 401 : 403);
            }))
            .logout(l -> l.logoutUrl("/api/auth/logout").invalidateHttpSession(true)
                .deleteCookies("JSESSIONID").logoutSuccessHandler((req, res, auth) -> res.setStatus(204)));
        return http.build();
    }
}
