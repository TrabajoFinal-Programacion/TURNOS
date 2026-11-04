package ar.edu.um.turnos.reservation.dto;

import java.util.List;

public record ReservationPage(List<ReservationResponse> content, int page, int size, long totalElements,
                              int totalPages) {}
