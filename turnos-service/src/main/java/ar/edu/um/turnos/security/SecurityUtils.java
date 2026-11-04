package ar.edu.um.turnos.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Acceso a la identidad autenticada. La identidad sale siempre del JWT validado, nunca del body. */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<String> currentLogin() {
        return currentJwt().map(Jwt::getSubject);
    }

    public static Optional<Long> currentUserId() {
        return currentJwt().map(jwt -> {
            Object uid = jwt.getClaim(JwtConfig.USER_ID_CLAIM);
            return uid instanceof Number n ? n.longValue() : null;
        });
    }

    /** Token crudo del request actual: se reenvia a catalogo (propagacion del JWT del usuario). */
    public static Optional<String> currentBearerToken() {
        return currentJwt().map(Jwt::getTokenValue);
    }

    private static Optional<Jwt> currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return Optional.of(token.getToken());
        }
        return Optional.empty();
    }
}
