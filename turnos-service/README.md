# turnos-service

Servicio de **turnos y reservas** del proyecto integrador 2026. También es el **emisor de identidad** de los usuarios finales: registra usuarios, autentica y emite el JWT que usa la app KMP.

> Estado: **Etapa 3**. Usuarios finales con JWT, disponibilidad de turnos, proceso de reserva completo (REST + Kafka con la cátedra), consulta y cancelación de reservas propias, y recuperación de procesos pendientes.

## Requisitos

- Java 21
- Maven 3.9+ (o IntelliJ IDEA, que trae Maven integrado)
- Docker con Docker Compose v2 (para la infraestructura local y para los tests con Testcontainers)
- `openssl` (en Windows viene con Git for Windows; usar Git Bash para correr el script)

Los dos repositorios backend tienen que estar clonados en la misma carpeta:

```
proyecto/
├── turnos-service/     (este repo; el compose está en deploy/)
└── catalogo-service/
```

## Puesta en marcha

```bash
cd turnos-service/deploy
./generate-keys.sh            # una sola vez: par RSA para firmar los JWT
cp .env.example .env          # completar contraseñas y datos de la cátedra
docker compose up -d --build
```

Los dos servicios necesitan los datos de la cuenta técnica de la cátedra (`CATEDRA_*` en `.env`). Sin ellos no arrancan. Los dos usan la misma cuenta técnica y el mismo JWT técnico, que nunca llega a la app.

Quedan expuestos:

| Servicio | URL |
| --- | --- |
| turnos-service | http://localhost:8082 |
| catalogo-service | http://localhost:8081 |
| MySQL (turnos) | 127.0.0.1:3306 (solo local) |
| PostgreSQL (catálogo) | 127.0.0.1:5432 (solo local) |

### Correr desde el IDE

Levantar solo la base: `docker compose up -d turnos-db` y correr `TurnosApplication` con la variable `TURNOS_DB_PASSWORD`. Por defecto lee las claves de `deploy/secrets/`.

## Tests

```bash
mvn verify
```

Requiere Docker en ejecución: los tests de integración levantan MySQL y Kafka reales con Testcontainers. La API REST de la cátedra y el catálogo se reemplazan por mocks; los eventos de la cátedra se publican de verdad en Kafka.

| Test | Qué verifica |
| --- | --- |
| `UserServiceTest` | normalización de login/email, hash BCrypt, rol fijo `ROLE_USER`, duplicados, login por usuario o email, usuario inactivo |
| `AuthIntegrationTest` | registro → login → `/api/account`; el cliente no puede elegir id, roles ni activación; errores con `code` estable; 401 sin token o con token alterado; 403 en rutas de administrador |
| `ReservationProcessTest` | máquina de estados: camino feliz, `Requested` antes de la respuesta de confirmación, reintento tras rechazo, estados finales que no se reabren, confirmada solo pasa a cancelada |
| `SlotCalculatorTest` | turnos = agenda vigente − ocupaciones − horarios pasados; `CONFIRMED` gana sobre `HELD`; hold vencido = libre; profesional o categoría deshabilitados |
| `PhoneNumbersTest` | misma normalización y regla que la cátedra |
| `ReservationFlowIntegrationTest` | **reserva confirmada** por REST + Kafka (incluye el mensaje publicado en el topic de teléfono); **reserva que expira** y evento tardío que no la reabre; rechazo y reenvío del teléfono; **evento duplicado procesado una vez**; **cancelación propia** idempotente; **sin acceso cruzado** entre usuarios; turno tomado; catálogo caído (no se inicia la reserva, pero "mis reservas" sigue funcionando); `Idempotency-Key`; **recuperación** de una confirmación sin respuesta y de un evento perdido; disponibilidad |

## API: usuarios

Rutas y formatos compatibles con JHipster.

| Método | Ruta | Acceso | Resultado |
| --- | --- | --- | --- |
| `POST` | `/api/register` | público | `201` sin body |
| `POST` | `/api/authenticate` | público | `200` `{"id_token": "..."}` y cabecera `Authorization` |
| `GET` | `/api/account` | `ROLE_USER` | datos de la cuenta autenticada |

Registro:

```json
{ "login": "ana", "password": "secreto123", "firstName": "Ana", "lastName": "Gomez",
  "email": "ana@example.com", "imageUrl": null, "langKey": "es" }
```

Login:

```json
{ "username": "ana", "password": "secreto123", "rememberMe": false }
```

### Errores

Todos los errores usan `application/problem+json` con un `code` estable. Los clientes deciden por `status` + `code`, nunca por el texto.

| HTTP | `code` | Cuándo |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | campo faltante o inválido (incluye `fieldErrors`) |
| 400 | `LOGIN_ALREADY_USED` | login ya registrado |
| 400 | `EMAIL_ALREADY_USED` | email ya registrado |
| 401 | `BAD_CREDENTIALS` | usuario o contraseña incorrectos |
| 401 | `UNAUTHORIZED` | falta el token o es inválido / vencido |
| 403 | `FORBIDDEN` | el rol no alcanza para la operación |

## API: turnos y reservas

Todas requieren `Authorization: Bearer <jwt-usuario>`. El usuario sale siempre del JWT. El contrato completo, con ejemplos y errores, está en [`docs/contrato-api.md`](docs/contrato-api.md).

| Método | Ruta | Descripción |
| --- | --- | --- |
| `GET` | `/api/availability?professionalId=&date=` | turnos del día con estado `AVAILABLE`, `HELD`, `RESERVED` o `PAST` |
| `POST` | `/api/reservations` | inicia la reserva (hold + confirmación inicial). Header opcional `Idempotency-Key` |
| `GET` | `/api/reservations?status=&page=&size=` | mis reservas (solo datos propios) |
| `GET` | `/api/reservations/{id}` | una reserva propia |
| `POST` | `/api/reservations/{id}/phone` | envía el teléfono pedido por la cátedra |
| `POST` | `/api/reservations/{id}/cancel` | cancela una reserva propia confirmada |
| `POST` | `/api/reservations/sync` | reconcilia mis reservas con el listado de la cátedra |

El proceso, la máquina de estados, la idempotencia y la recuperación están explicados en [`docs/reservas.md`](docs/reservas.md).

## Modelo de datos (MySQL, propiedad exclusiva de este servicio)

| Tabla | Contenido |
| --- | --- |
| `jhi_user`, `jhi_authority`, `jhi_user_authority` | usuarios finales (modelo JHipster) y `external_id` |
| `reservation_process` | un proceso por intento de reserva: dueño (`user_id`), estado local, turno, datos del paciente, identificadores de la cátedra (`hold_id`, `reservation_process_id`, `request_event_id`, `reservation_id`), vencimiento, motivos |
| `processed_event` | `eventId` de Kafka ya procesados y cantidad de duplicados |

Turnos **no** guarda una réplica del catálogo. Cada proceso conserva el nombre del profesional y la categoría solo como dato histórico, para mostrarlo en "mis reservas".

## Decisiones (Etapa 1)

- **Los usuarios viven en turnos.** Las reservas pertenecen a un usuario, así que la identidad y la propiedad quedan en el mismo servicio y en la misma base. Catálogo no guarda usuarios.
- **JWT RS256 (clave asimétrica).** Turnos firma con la clave privada; catálogo solo tiene la pública. Catálogo puede validar tokens pero no puede fabricarlos. Con HS256 (clave compartida) cualquiera de los dos podría emitir tokens válidos.
- **Propagación del JWT del usuario.** Cuando turnos llame a catálogo (Etapa 3), reenviará el mismo token del usuario. Así catálogo valida firma, vigencia y rol, y la trazabilidad conserva quién originó la operación. Detalle en [`docs/adr/0001-autenticacion-entre-servicios.md`](docs/adr/0001-autenticacion-entre-servicios.md).
- **Claims compatibles con JHipster:** `sub` = login, `auth` = roles separados por espacio. Se agrega `uid` (id del usuario) para no depender del login. También se validan `iss` y `aud`.
- **`external_id` (UUID).** Es el identificador que se enviará a la cátedra como `externalPatientId`. No se usa el id autoincremental, porque si la base se recrea los ids se reutilizan y un usuario nuevo podría quedar asociado a reservas viejas de la cuenta técnica.
- **Login sin enumeración por tiempo.** Si el usuario no existe, igual se compara la contraseña contra un hash ficticio, para que la respuesta tarde lo mismo.
- **Esquema administrado por Flyway** (`ddl-auto: none`). Las tablas tienen los mismos nombres que en JHipster (`jhi_user`, `jhi_authority`, `jhi_user_authority`).

## Decisiones (Etapa 3)

- **Una sola llamada para iniciar la reserva** (`POST /api/reservations`): valida con el catálogo, crea el hold y hace la confirmación inicial. Desde la app es un solo toque, y después se consulta el estado.
- **Nunca hay una transacción abierta durante una llamada remota.** Cada paso guarda su resultado en una transacción corta (`ReservationStore`).
- **El hold no se reintenta.** No se puede consultar por REST y un segundo intento chocaría con el propio hold huérfano. Si no hay respuesta, el proceso queda `FAILED (HOLD_UNKNOWN)` y el hold vence solo.
- **La confirmación inicial sí se reintenta.** Si el primer intento había llegado, la cátedra responde `RESERVATION_PROCESS_ALREADY_CONFIRMED`, y eso se trata como éxito.
- **Idempotencia de eventos en la misma transacción que el efecto:** el `eventId` y el cambio de estado se guardan juntos en MySQL.
- **Grupo de consumidores propio** (`alumnos-{groupId}-turnos`), distinto del de catálogo, para que un reinicio de un servicio no rebalancee al otro. Se puede sobrescribir con `CATEDRA_KAFKA_TURNOS_CONSUMER_GROUP`.

## Seguridad: limitaciones conocidas

- No hay revocación de JWT antes de su vencimiento (24 h, o 30 días con `rememberMe`). Mitigación: `/api/account` rechaza tokens de usuarios inexistentes o inactivos.
- No hay límite de intentos de login.
- En local, la comunicación es HTTP. El cifrado en tránsito (TLS) se resuelve en una etapa posterior.
