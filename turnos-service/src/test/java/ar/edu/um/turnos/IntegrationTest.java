package ar.edu.um.turnos;

import ar.edu.um.turnos.catalogo.CatalogoClient;
import ar.edu.um.turnos.catedra.CatedraTurnosClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base de los tests de integracion: MySQL y Kafka reales en Docker (Testcontainers).
 * La API REST de la catedra y el servicio de catalogo se reemplazan por mocks.
 * Los contenedores se comparten entre clases para no levantarlos en cada test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String ACTIONS_TOPIC = "alumnos.turnos.acciones.test-group";
    public static final String PHONE_TOPIC = "catedra.turnos.telefono.test-group";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("turnos")
        .withUsername("turnos")
        .withPassword("turnos");

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        MYSQL.start();
        KAFKA.start();
        try (AdminClient admin = AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(ACTIONS_TOPIC, 1, (short) 1),
                new NewTopic(PHONE_TOPIC, 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudieron crear los topics de prueba", e);
        }
    }

    @MockitoBean
    protected CatedraTurnosClient catedraClient;

    @MockitoBean
    protected CatalogoClient catalogoClient;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** Registra un usuario final nuevo y devuelve su JWT. */
    protected String newUserToken() throws Exception {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "login", login, "password", "secreto123", "firstName", "Nombre", "lastName", "Apellido"))))
            .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", login, "password", "secreto123", "rememberMe", false))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id_token").asText();
    }
}
