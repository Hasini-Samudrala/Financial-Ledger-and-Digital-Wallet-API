package com.finledger.api.security;

import tools.jackson.databind.ObjectMapper;
import com.finledger.api.config.AppProperties;
import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.exception.GlobalExceptionHandler;
import com.finledger.api.user.ApiKeyService;
import com.finledger.api.user.UserRecord;
import com.finledger.api.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates requests using {@code X-User-API-Key} or {@code X-Admin-API-Key}
 * with constant-time comparison (ADR-002).
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String USER_API_KEY_HEADER = "X-User-API-Key";
    public static final String ADMIN_API_KEY_HEADER = "X-Admin-API-Key";

    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;
    private final SqliteTransactionExecutor txExecutor;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(
            ApiKeyService apiKeyService,
            UserRepository userRepository,
            SqliteTransactionExecutor txExecutor,
            AppProperties appProperties,
            ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.userRepository = userRepository;
        this.txExecutor = txExecutor;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return (HttpMethod.GET.matches(method) && "/health".equals(path))
                || (HttpMethod.POST.matches(method) && "/users".equals(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path.startsWith("/admin/")) {
            if (!authenticateAdmin(request)) {
                writeUnauthorized(response);
                return;
            }
        } else {
            if (!authenticateUser(request)) {
                writeUnauthorized(response);
                return;
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean authenticateAdmin(HttpServletRequest request) {
        String configured = appProperties.getAdminApiKey();
        String supplied = request.getHeader(ADMIN_API_KEY_HEADER);
        if (configured == null || configured.isBlank() || supplied == null || supplied.isBlank()) {
            return false;
        }
        boolean matches = MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            return false;
        }
        var auth = new UsernamePasswordAuthenticationToken(
                "ADMIN", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return true;
    }

    private boolean authenticateUser(HttpServletRequest request) {
        String rawKey = request.getHeader(USER_API_KEY_HEADER);
        ApiKeyService.ParsedKey parsed = apiKeyService.parse(rawKey);
        if (parsed == null) {
            return false;
        }

        UserRecord user = txExecutor.executeRead(conn -> userRepository.findById(conn, parsed.userId()));
        if (user == null || !apiKeyService.verify(parsed.secretBytes(), user.apiKeySalt(), user.apiKeyHash())) {
            return false;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                parsed.userId(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                GlobalExceptionHandler.envelope("INVALID_API_KEY", "Missing or invalid API key."));
    }
}
