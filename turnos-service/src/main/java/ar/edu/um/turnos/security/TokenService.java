package ar.edu.um.turnos.security;

import ar.edu.um.turnos.config.JwtProperties;
import ar.edu.um.turnos.user.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Emite el JWT del usuario final. Claims compatibles con JHipster: sub=login, auth=roles. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String createToken(User user, boolean rememberMe) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(rememberMe ? properties.tokenValidityRememberMe() : properties.tokenValidity());
        String authorities = user.getAuthorities().stream()
            .map(a -> a.getName())
            .sorted()
            .collect(Collectors.joining(" "));

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .id(UUID.randomUUID().toString())
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .subject(user.getLogin())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(JwtConfig.AUTHORITIES_CLAIM, authorities)
            .claim(JwtConfig.USER_ID_CLAIM, user.getId())
            .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
