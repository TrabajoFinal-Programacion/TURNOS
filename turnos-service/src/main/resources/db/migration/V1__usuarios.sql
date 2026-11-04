-- Usuarios finales de la aplicacion.
-- Tablas y columnas compatibles con el modelo de usuario de JHipster
-- (jhi_user, jhi_authority, jhi_user_authority), mas external_id:
-- identificador estable que se envia a la catedra como externalPatientId.

CREATE TABLE jhi_authority (
    name VARCHAR(50) NOT NULL,
    CONSTRAINT pk_jhi_authority PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE jhi_user (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    login              VARCHAR(50)  NOT NULL,
    password_hash      VARCHAR(60)  NOT NULL,
    first_name         VARCHAR(50),
    last_name          VARCHAR(50),
    email              VARCHAR(254),
    image_url          VARCHAR(256),
    activated          BIT(1)       NOT NULL,
    lang_key           VARCHAR(10),
    activation_key     VARCHAR(20),
    reset_key          VARCHAR(20),
    reset_date         DATETIME(6)  NULL,
    external_id        VARCHAR(36)  NOT NULL,
    created_by         VARCHAR(50)  NOT NULL,
    created_date       DATETIME(6)  NULL,
    last_modified_by   VARCHAR(50),
    last_modified_date DATETIME(6)  NULL,
    CONSTRAINT pk_jhi_user PRIMARY KEY (id),
    CONSTRAINT ux_user_login UNIQUE (login),
    CONSTRAINT ux_user_email UNIQUE (email),
    CONSTRAINT ux_user_external_id UNIQUE (external_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE jhi_user_authority (
    user_id        BIGINT      NOT NULL,
    authority_name VARCHAR(50) NOT NULL,
    CONSTRAINT pk_jhi_user_authority PRIMARY KEY (user_id, authority_name),
    CONSTRAINT fk_user_authority_user FOREIGN KEY (user_id) REFERENCES jhi_user (id),
    CONSTRAINT fk_user_authority_authority FOREIGN KEY (authority_name) REFERENCES jhi_authority (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO jhi_authority (name) VALUES ('ROLE_USER'), ('ROLE_ADMIN');
