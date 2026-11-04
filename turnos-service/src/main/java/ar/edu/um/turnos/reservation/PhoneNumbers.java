package ar.edu.um.turnos.reservation;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Misma normalizacion que aplica la catedra (contrato 15.5): se quitan espacios, guiones,
 * parentesis y puntos, y el resultado debe cumplir ^\+?[0-9]{7,15}$.
 * Validar antes de enviar evita un ida y vuelta por Kafka con un telefono que seguro se rechaza.
 */
public final class PhoneNumbers {

    private static final Pattern VALID = Pattern.compile("^\\+?[0-9]{7,15}$");

    private PhoneNumbers() {}

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.replaceAll("[\\s\\-().]", "");
        return VALID.matcher(cleaned).matches() ? Optional.of(cleaned) : Optional.empty();
    }
}
