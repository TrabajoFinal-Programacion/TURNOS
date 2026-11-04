package ar.edu.um.turnos.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.um.turnos.user.dto.RegisterUserRequest;
import ar.edu.um.turnos.web.error.ApiException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock AuthorityRepository authorityRepository;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4); // costo bajo: solo para que el test sea rapido
    UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, authorityRepository, passwordEncoder);
    }

    @Test
    void registerNormalizesLoginHashesPasswordAndAssignsOnlyRoleUser() {
        when(authorityRepository.getReferenceById(Authority.USER)).thenReturn(new Authority(Authority.USER));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = service.register(new RegisterUserRequest("Ana.Perez", "secreto123", "Ana", "Perez",
            "Ana@Example.com", null, null));

        assertThat(user.getLogin()).isEqualTo("ana.perez");
        assertThat(user.getEmail()).isEqualTo("ana@example.com");
        assertThat(user.getPassword()).isNotEqualTo("secreto123").startsWith("$2");
        assertThat(passwordEncoder.matches("secreto123", user.getPassword())).isTrue();
        assertThat(user.isActivated()).isTrue();
        assertThat(user.getLangKey()).isEqualTo("es");
        assertThat(user.getExternalId()).hasSize(36);
        assertThat(user.getAuthorities()).extracting(Authority::getName).containsExactly(Authority.USER);
    }

    @Test
    void registerRejectsDuplicateLogin() {
        when(userRepository.existsByLogin("ana")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterUserRequest("ana", "secreto123", null, null, null, null, null)))
            .isInstanceOf(ApiException.class)
            .extracting("code").isEqualTo("LOGIN_ALREADY_USED");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByLogin("ana")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("ana@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
            new RegisterUserRequest("ana", "secreto123", null, null, "ana@example.com", null, null)))
            .isInstanceOf(ApiException.class)
            .extracting("code").isEqualTo("EMAIL_ALREADY_USED");
    }

    @Test
    void authenticateAcceptsCorrectPasswordOnly() {
        User user = activeUser("ana", "secreto123");
        when(userRepository.findOneByLogin("ana")).thenReturn(Optional.of(user));

        assertThat(service.authenticate("ANA", "secreto123")).contains(user);
        assertThat(service.authenticate("ana", "otra-clave")).isEmpty();
    }

    @Test
    void authenticateRejectsUnknownUser() {
        when(userRepository.findOneByLogin(anyString())).thenReturn(Optional.empty());
        assertThat(service.authenticate("nadie", "secreto123")).isEmpty();
    }

    @Test
    void authenticateRejectsInactiveUser() {
        User inactive = activeUser("inactivo", "secreto123");
        inactive.setActivated(false);
        when(userRepository.findOneByLogin("inactivo")).thenReturn(Optional.of(inactive));
        assertThat(service.authenticate("inactivo", "secreto123")).isEmpty();
    }

    @Test
    void authenticateByEmail() {
        User user = activeUser("ana", "secreto123");
        when(userRepository.findOneByEmailIgnoreCase("ana@example.com")).thenReturn(Optional.of(user));

        assertThat(service.authenticate("Ana@Example.com", "secreto123")).contains(user);
    }

    private User activeUser(String login, String rawPassword) {
        User user = new User();
        user.setLogin(login);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setActivated(true);
        return user;
    }
}
