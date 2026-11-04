package ar.edu.um.turnos.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Evento Kafka ya procesado. eventId es la clave de idempotencia del contrato. */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 200)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId, String eventType, Instant now) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = now;
        this.lastSeenAt = now;
    }

    public void registerDuplicate(Instant now) {
        this.duplicateCount++;
        this.lastSeenAt = now;
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public int getDuplicateCount() { return duplicateCount; }
    public Instant getProcessedAt() { return processedAt; }
}
