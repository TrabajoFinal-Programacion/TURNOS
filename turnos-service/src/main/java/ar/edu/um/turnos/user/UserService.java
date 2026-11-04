package ar.edu.um.turnos.user;

import ar.edu.um.turnos.user.dto.RegisterUserRequest;
import ar.edu.um.turnos.web.error.ApiException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Hash de una clave aleatoria. Se compara contra el cuando el usuario no existe, para que el login
     * tarde lo mismo exista o no el usuario (evita enumerar usuarios por tiempo de respuesta).
     */
    private final String dummyHash;

    public UserService(UserRepository userRepository, AuthorityRepository authorityRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public User register(RegisterUserRequest request) {
        String login = request.login().toLowerCase(Locale.ROOT);
        String email = request.email() == null ? null : request.email().toLowerCase(Locale.ROOT);

        if (userRepository.existsByLogin(login)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOGIN_ALREADY_USED", "El login ya esta en uso");
        }
        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_ALREADY_USED", "El email ya esta en uso");
        }

        User user = new User();
        user.setLogin(login);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(email);
        user.setImageUrl(request.imageUrl());
        user.setLangKey(request.langKey() == null ? "es" : request.langKey());
        user.setActivated(true); // el enunciado pide activacion inmediata, sin verificacion por email
        user.setExternalId(UUID.randomUUID().toString());
        // Las autoridades las decide el backend: todo registro publico es ROLE_USER.
        user.setAuthorities(new HashSet<>(Set.of(authorityRepository.getReferenceById(Authority.USER))));

        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Carrera entre dos registros simultaneos con el mismo login/email: gana la restriccion UNIQUE.
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOGIN_OR_EMAIL_ALREADY_USED", "Login o email ya en uso");
        }
    }

    /** Devuelve el usuario si las credenciales son correctas y esta activo. Acepta login o email. */
    @Transactional(readOnly = true)
    public Optional<User> authenticate(String username, String rawPassword) {
        String key = username.toLowerCase(Locale.ROOT);
        Optional<User> user = key.contains("@")
            ? userRepository.findOneByEmailIgnoreCase(key).or(() -> userRepository.findOneByLogin(key))
            : userRepository.findOneByLogin(key);

        if (user.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            return Optional.empty();
        }
        User u = user.get();
        boolean ok = passwordEncoder.matches(rawPassword, u.getPassword());
        return ok && u.isActivated() ? Optional.of(u) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
}
