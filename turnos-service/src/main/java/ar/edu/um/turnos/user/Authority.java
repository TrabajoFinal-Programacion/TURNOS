package ar.edu.um.turnos.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "jhi_authority")
public class Authority {

    public static final String USER = "ROLE_USER";
    public static final String ADMIN = "ROLE_ADMIN";

    @Id
    @Column(length = 50)
    private String name;

    protected Authority() {}

    public Authority(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Authority other && Objects.equals(name, other.name));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
