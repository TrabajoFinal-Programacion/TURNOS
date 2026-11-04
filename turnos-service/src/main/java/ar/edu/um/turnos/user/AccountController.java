package ar.edu.um.turnos.user;

import ar.edu.um.turnos.security.SecurityUtils;
import ar.edu.um.turnos.security.TokenService;
import ar.edu.um.turnos.user.dto.AccountResponse;
import ar.edu.um.turnos.user.dto.JwtTokenResponse;
import ar.edu.um.turnos.user.dto.LoginRequest;
import ar.edu.um.turnos.user.dto.RegisterUserRequest;
import ar.edu.um.turnos.web.error.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registro, login y cuenta del usuario final. Rutas iguales a las de JHipster. */
@RestController
@RequestMapping("/api")
public class AccountController {

    private final UserService userService;
    private final TokenService tokenService;

    public AccountController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody RegisterUserRequest request) {
        userService.register(request);
    }

    @PostMapping("/authenticate")
    public ResponseEntity<JwtTokenResponse> authenticate(@Valid @RequestBody LoginRequest request) {
        User user = userService.authenticate(request.username(), request.password())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Credenciales invalidas"));
        String token = tokenService.createToken(user, request.rememberMe());
        return ResponseEntity.ok()
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .body(new JwtTokenResponse(token));
    }

    @GetMapping("/account")
    public AccountResponse account() {
        Long userId = SecurityUtils.currentUserId()
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Token sin usuario"));
        // Si el usuario fue eliminado, el token deja de servir aunque no haya vencido.
        return userService.findById(userId)
            .filter(User::isActivated)
            .map(AccountResponse::from)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Usuario inexistente o inactivo"));
    }
}
