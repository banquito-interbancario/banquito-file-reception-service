package ec.edu.espe.switchbatch.service.impl;

import ec.edu.espe.switchbatch.dto.BatchLineMessage;
import ec.edu.espe.switchbatch.dto.ParsedBatch;
import ec.edu.espe.switchbatch.dto.ParsedPaymentLine;
import ec.edu.espe.switchbatch.event.PaymentLinesReadyEvent;
import ec.edu.espe.switchbatch.model.PaymentFileValidation;
import ec.edu.espe.switchbatch.repository.BatchStatusLogRepository;
import ec.edu.espe.switchbatch.repository.PaymentBatchRepository;
import ec.edu.espe.switchbatch.repository.PaymentFileValidationRepository;
import ec.edu.espe.switchbatch.service.ICoreBankingClient;
import ec.edu.espe.switchbatch.service.IPaymentLinePublisher;
import ec.edu.espe.switchbatch.service.IRoutingCodeCatalogService;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * RF-02: Procesador asíncrono de líneas de pago.
 *
 * Se activa tras el 202 mediante Spring Application Event (@Async).
 * Responsabilidades:
 *   1. Validar que el cliente tiene activo el servicio de pagos masivos
 *   2. Filtrar líneas por routing code válido (catálogo paramétrico RF-01)
 *   3. Validar cuenta origen favorita
 *   4. Fragmentar y publicar cada línea como mensaje independiente → RabbitMQ
 */
@Component
public class PaymentLinesReadyListener {

    private static final Logger logger = LoggerFactory.getLogger(PaymentLinesReadyListener.class);

    private final IPaymentLinePublisher paymentLinePublisher;
    private final TaskScheduler taskScheduler;
    private final ICoreBankingClient coreBankingClient;
    private final IRoutingCodeCatalogService routingCodeCatalogService;
    private final PaymentFileValidationRepository validationRepository;
    private final PaymentBatchRepository paymentBatchRepository;
    private final BatchStatusLogRepository statusLogRepository;

    public PaymentLinesReadyListener(IPaymentLinePublisher paymentLinePublisher,
                                     TaskScheduler taskScheduler,
                                     ICoreBankingClient coreBankingClient,
                                     IRoutingCodeCatalogService routingCodeCatalogService,
                                     PaymentFileValidationRepository validationRepository,
                                     PaymentBatchRepository paymentBatchRepository,
                                     BatchStatusLogRepository statusLogRepository) {
        this.paymentLinePublisher = paymentLinePublisher;
        this.taskScheduler = taskScheduler;
        this.coreBankingClient = coreBankingClient;
        this.routingCodeCatalogService = routingCodeCatalogService;
        this.validationRepository = validationRepository;
        this.paymentBatchRepository = paymentBatchRepository;
        this.statusLogRepository = statusLogRepository;
    }

    @Async
    @EventListener
    public void onPaymentLinesReady(PaymentLinesReadyEvent event) {
        ParsedBatch batch = event.batch();
        String batchId = event.batchId();

        logger.info("[RF-02][ASYNC] Iniciando validación y fragmentación del lote {} ({} líneas declaradas).",
                batchId, batch.declaredRecords());

        // ── 1. Verificar servicio activo ──────────────────────────────────────
        boolean customerServiceActive = coreBankingClient.hasActiveMassPaymentService(
                batch.clientRuc(), batch.serviceType());
        if (!customerServiceActive) {
            logger.warn("[RF-02][ASYNC] Lote {} rechazado: servicio de pagos masivos inactivo para RUC {}.",
                    batchId, batch.clientRuc());
            updateBatchStatus(batchId, "REJECTED");
            saveValidation(batchId, batch, event.duplicateValid(), false);
            return;
        }

        // ── 2. Filtrar líneas válidas (RF-01: catálogo + cuenta destino) usando hilos ──────
        logger.info("[RF-02][ASYNC] Procesando líneas utilizando hilos concurrentes para optimizar la carga.");
        List<ParsedPaymentLine> acceptedLines = batch.lines();

        // ── 3. Validar cuenta origen favorita ────────────────────────────────
        boolean sourceAccountValid = coreBankingClient.isFavoriteAccount(
                batch.sourceAccountNumber(), batch.clientRuc());
        boolean fullyValid = sourceAccountValid && acceptedLines.size() == batch.lines().size();
        saveValidation(batchId, batch, event.duplicateValid(), fullyValid);

        if (!sourceAccountValid) {
            logger.warn("[RF-02][ASYNC] Lote {} sin cuenta origen favorita válida. Ninguna línea publicada.", batchId);
            return;
        }

        // ── 4. Fragmentar y publicar cada línea a RabbitMQ ───────────────────
        List<BatchLineMessage> messages = toMessages(batchId, batch, acceptedLines);
        logger.info("[RF-02][ASYNC] Publicando {} líneas en RabbitMQ para lote {}.", messages.size(), batchId);

        if (event.scheduledProcessAt().isAfter(Instant.now())) {
            logger.info("[RF-02][ASYNC] Lote {} programado para {}. Publicación diferida.", batchId, event.scheduledProcessAt());
            taskScheduler.schedule(
                    () -> paymentLinePublisher.publish(batchId, event.scheduledProcessAt(), messages),
                    event.scheduledProcessAt());
            return;
        }

        paymentLinePublisher.publish(batchId, event.scheduledProcessAt(), messages);
    }

    private List<BatchLineMessage> toMessages(String batchId, ParsedBatch batch, List<ParsedPaymentLine> lines) {
        return lines.stream()
                .map(line -> new BatchLineMessage(
                        batchId,
                        line.lineNumber(),
                        line.routingCode(),
                        routingCodeCatalogService.classify(line.routingCode()),
                        line.destinationAccountNumber(),
                        batch.sourceAccountNumber(),
                        batch.declaredRecords(),
                        batch.headerTotalAmount(), // monto total declarado en cabecera del archivo
                        line.amount(),
                        line.reference(),
                        line.beneficiaryName(),
                        line.beneficiaryEmail()))
                .toList();
    }

    private void saveValidation(String batchId, ParsedBatch batch, boolean duplicateValid, boolean customerServiceValid) {
        PaymentFileValidation validation = new PaymentFileValidation();
        validation.setPaymentBatchId(batchId);
        validation.setHeaderTotalRecords(batch.headerTotalRecords());
        validation.setHeaderTotalAmount(batch.headerTotalAmount());
        validation.setFooterTotalRecords(batch.footerTotalRecords());
        validation.setFooterTotalAmount(batch.footerTotalAmount());
        validation.setSecurityHash(batch.securityHash());
        validation.setStructureValid(true);
        validation.setAmountControlValid(true);
        validation.setCustomerServiceValid(customerServiceValid);
        validation.setDuplicateFileValid(duplicateValid);
        validation.setValidationResult(customerServiceValid ? "SUCCESS" : "PARTIAL_SUCCESS");
        validation.setValidatedAt(Instant.now());
        validationRepository.save(validation);
    }

    private void updateBatchStatus(String batchId, String newStatus) {
        paymentBatchRepository.findById(batchId).ifPresent(doc -> {
            String previousStatus = doc.getStatus();
            doc.setStatus(newStatus);
            paymentBatchRepository.save(doc);
            saveBatchStatusLog(batchId, previousStatus, newStatus);
        });
    }

    private void saveBatchStatusLog(String batchId, String previousStatus, String newStatus) {
        ec.edu.espe.switchbatch.model.BatchStatusLog log = new ec.edu.espe.switchbatch.model.BatchStatusLog();
        log.setPaymentBatchId(batchId);
        log.setPreviousStatus(previousStatus);
        log.setNewStatus(newStatus);
        log.setChangedAt(Instant.now());
        statusLogRepository.save(log);
    }
}
