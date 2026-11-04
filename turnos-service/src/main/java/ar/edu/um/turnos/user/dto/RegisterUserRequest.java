package ar.edu.um.turnos.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registro de usuario final, con las mismas validaciones que ManagedUserVM de JHipster.
 * Solo contiene los campos que puede elegir quien se registra: id, activated, authorities
 * y auditoria los decide el backend (los campos extra del JSON se ignoran).
 */
public record RegisterUserRequest(
    @NotBlank @Size(min = 1, max = 50) @Pattern(regexp = LOGIN_REGEX) String login,
    @NotNull @Size(min = PASSWORD_MIN, max = PASSWORD_MAX) String password,
    @Size(max = 50) String firstName,
    @Size(max = 50) String lastName,
    @Email @Size(min = 5, max = 254) String email,
    @Size(max = 256) String imageUrl,
    @Size(min = 2, max = 10) String langKey
) {
    /** Regex de login de JHipster. */
    public static final String LOGIN_REGEX =
        "^(?>[a-zA-Z0-9!$&*+=?^_`{|}~.-]+@[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*)|(?>[_.@A-Za-z0-9-]+)$";
    public static final int PASSWORD_MIN = 4;
    public static final int PASSWORD_MAX = 100;
}
