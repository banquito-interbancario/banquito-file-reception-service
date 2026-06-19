package ec.edu.espe.switchbatch.event;

import ec.edu.espe.switchbatch.dto.ParsedBatch;
import java.time.Instant;

/**
 * RF-02: Evento publicado inmediatamente tras validación estructural.
 * Lleva el lote completo (sin pre-filtrar) para que el listener lo procese
 * de forma asíncrona vía RabbitMQ.
 */
public record PaymentLinesReadyEvent(
        String batchId,
        Instant scheduledProcessAt,
        ParsedBatch batch,
        boolean duplicateValid) {
}
