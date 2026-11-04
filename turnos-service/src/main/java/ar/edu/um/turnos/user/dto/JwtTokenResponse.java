package ar.edu.um.turnos.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JwtTokenResponse(@JsonProperty("id_token") String idToken) {}
