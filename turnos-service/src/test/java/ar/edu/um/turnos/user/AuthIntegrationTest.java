package ar.edu.um.turnos.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.turnos.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Registro, login y acceso protegido de usuarios finales, contra MySQL real. */
class AuthIntegrationTest extends IntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired JwtDecoder jwtDecoder;

    @Test
    void registerLoginAndReadOwnAccount() throws Exception {
        String login = uniqueLogin();
        register(login, "secreto123", login + "@example.com");

        String token = login(login, "secreto123");

        mvc.perform(get("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.login").value(login))
            .andExpect(jsonPath("$.activated").value(true))
            .andExpect(jsonPath("$.langKey").value("es"))
            .andExpect(jsonPath("$.createdBy").value("system"))
            .andExpect(jsonPath("$.authorities.length()").value(1))
            .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"))
            .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void passwordIsStoredHashed() throws Exception {
        String login = uniqueLogin();
        register(login, "secreto123", null);

        User user = userRepository.findOneByLogin(login).orElseThrow();
        assertThat(user.getPassword()).isNotEqualTo("secreto123").startsWith("$2");
    }

    @Test
    void clientCannotChooseAuthoritiesActivationOrId() throws Exception {
        String login = uniqueLogin();
        String body = """
            {"login":"%s","password":"secreto123","id":999999,"activated":false,
             "authorities":["ROLE_ADMIN"],"createdBy":"hacker"}
            """.formatted(login);
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        User user = userRepository.findOneByLogin(login).orElseThrow();
        assertThat(user.getId()).isNotEqualTo(999999L);
        assertThat(user.isActivated()).isTrue();
        assertThat(user.getCreatedBy()).isEqualTo("system");
        assertThat(user.getAuthorities()).extracting(Authority::getName).containsExactly("ROLE_USER");
    }

    @Test
    void duplicateLoginIsRejectedWithStableCode() throws Exception {
        String login = uniqueLogin();
        register(login, "secreto123", null);

        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(login.toUpperCase(), "otra-clave", null)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("LOGIN_ALREADY_USED"));
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"con espacios\",\"password\":\"123\",\"email\":\"no-es-email\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'login')]").exists())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").exists());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        String login = uniqueLogin();
        register(login, "secreto123", null);

        mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(login, "incorrecta")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
            .andExpect(jsonPath("$.id_token").doesNotExist());
    }

    @Test
    void tokenHasExpectedClaims() throws Exception {
        String login = uniqueLogin();
        register(login, "secreto123", null);
        Long userId = userRepository.findOneByLogin(login).orElseThrow().getId();

        Jwt jwt = jwtDecoder.decode(login(login, "secreto123"));

        assertThat(jwt.getSubject()).isEqualTo(login);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("turnos-service");
        assertThat(jwt.getAudience()).containsExactly("turnos-app");
        assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
        assertThat(((Number) jwt.getClaim("uid")).longValue()).isEqualTo(userId);
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/account"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String login = uniqueLogin();
        register(login, "secreto123", null);
        String token = login(login, "secreto123");
        // Cambia un caracter en el medio de la firma (el ultimo puede ser solo relleno de base64).
        int i = token.lastIndexOf('.') + 20;
        char c = token.charAt(i);
        String tampered = token.substring(0, i) + (c == 'A' ? 'B' : 'A') + token.substring(i + 1);

        mvc.perform(get("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsAreForbiddenForRegularUsers() throws Exception {
        String login = uniqueLogin();
        register(login, "secreto123", null);
        String token = login(login, "secreto123");

        mvc.perform(get("/api/admin/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- helpers ---

    private static String uniqueLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void register(String login, String password, String email) throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(login, password, email)))
            .andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(username, password)))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.AUTHORIZATION))
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id_token").asText();
    }

    private String registerJson(String login, String password, String email) throws Exception {
        var node = objectMapper.createObjectNode()
            .put("login", login)
            .put("password", password)
            .put("firstName", "Nombre")
            .put("lastName", "Apellido");
        if (email != null) {
            node.put("email", email);
        }
        return objectMapper.writeValueAsString(node);
    }

    private String loginJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
            .put("username", username).put("password", password).put("rememberMe", false));
    }
}
