package ar.edu.um.turnos.user.dto;

import ar.edu.um.turnos.user.Authority;
import ar.edu.um.turnos.user.User;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/** Equivalente a AdminUserDTO de JHipster (sin password ni claves internas). */
public record AccountResponse(
    Long id,
    String login,
    String firstName,
    String lastName,
    String email,
    String imageUrl,
    boolean activated,
    String langKey,
    String createdBy,
    Instant createdDate,
    String lastModifiedBy,
    Instant lastModifiedDate,
    Set<String> authorities
) {
    public static AccountResponse from(User user) {
        return new AccountResponse(
            user.getId(), user.getLogin(), user.getFirstName(), user.getLastName(), user.getEmail(),
            user.getImageUrl(), user.isActivated(), user.getLangKey(), user.getCreatedBy(), user.getCreatedDate(),
            user.getLastModifiedBy(), user.getLastModifiedDate(),
            user.getAuthorities().stream().map(Authority::getName).collect(Collectors.toCollection(java.util.TreeSet::new)));
    }
}
