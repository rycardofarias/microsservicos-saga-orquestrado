package br.com.microservices.orchestrated.paymentservice.core.service;

import br.com.microservices.orchestrated.paymentservice.config.exception.ValidationException;
import br.com.microservices.orchestrated.paymentservice.core.dto.EventDto;
import br.com.microservices.orchestrated.paymentservice.core.dto.HistoryDto;
import br.com.microservices.orchestrated.paymentservice.core.dto.OrderProductsDto;
import br.com.microservices.orchestrated.paymentservice.core.enums.EPaymentStatus;
import br.com.microservices.orchestrated.paymentservice.core.model.Payment;
import br.com.microservices.orchestrated.paymentservice.core.producer.KafkaProducer;
import br.com.microservices.orchestrated.paymentservice.core.repository.PaymentRepository;
import br.com.microservices.orchestrated.paymentservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static br.com.microservices.orchestrated.paymentservice.core.enums.ESagaStatus.*;

@Slf4j
@Service
@AllArgsConstructor
public class PaymentService {

    private static final String CURRENT_SOURCE = "PRODUCT_VALIDATION_SERVICE";

    private static final Double REDUCE_SUM_VALUE = 0.0;
    private static final Double MIN_AMOUNT_VALUE = 0.1;

    private final JsonUtil jsonUtil;
    private final KafkaProducer kafkaProducer;
    private final PaymentRepository paymentRepository;

    public void realizePayment(EventDto eventDto) {
        try {
            checkCurrentValidation(eventDto);
            createPendingPayment(eventDto);
            var payment = findByOrderIdAndTransactionId(eventDto);
            validateAmount(payment.getTotalAmount());
            changePaymentToSuccess(payment);
            handleSuccess(eventDto);

        } catch (Exception e) {
            log.error("Error tryning to make payment", e);
            handleFailCurrentNotExecuted(eventDto, e.getMessage());
        }
        kafkaProducer.sendEvent(jsonUtil.toJson(eventDto));
    }

    public void realizedRefund(EventDto eventDto) {
        eventDto.setStatus(FAIL);
        eventDto.setSource(CURRENT_SOURCE);
        try {
            changePaymentStatusToRefund(eventDto);
            addHistory(eventDto, "Rollback executed for payment!");
        } catch (Exception e) {
            addHistory(eventDto, "Rollback not executed for payment: ".concat(e.getMessage()));
        }
        kafkaProducer.sendEvent(jsonUtil.toJson(eventDto));
    }

    private void checkCurrentValidation(EventDto eventDto) {
        if (paymentRepository.existsByOrderIdAndTransactionId(
                eventDto.getPayload().getId(), eventDto.getTransactionId())) {
            throw new ValidationException("There's another transactionId for this validation.");
        }
    }

    private void createPendingPayment(EventDto eventDto) {

        var totalAmount = calculeAmount(eventDto);
        var totalItems = calculeTotalItems(eventDto);

        var payment = Payment
                .builder()
                .orderId(eventDto.getPayload().getId())
                .transactionId(eventDto.getTransactionId())
                .totalAmount(totalAmount)
                .totalItems(totalItems)
                .build();

        savePayment(payment);
        setEventAmountItens(eventDto, payment);
    }

    private double calculeAmount(EventDto eventDto) {
        return eventDto.getPayload().getProducts()
                .stream()
                .map(product -> product.getQuantity() * product.getProduct().getUnitValue())
                .reduce(REDUCE_SUM_VALUE, Double::sum);
    }

    private int calculeTotalItems(EventDto eventDto) {
        return eventDto.getPayload().getProducts()
                .stream()
                .mapToInt(OrderProductsDto::getQuantity)
                .reduce(REDUCE_SUM_VALUE.intValue(), Integer::sum);
    }

    private void setEventAmountItens(EventDto eventDto, Payment payment) {
        eventDto.getPayload().setTotalAmount(payment.getTotalAmount());
        eventDto.getPayload().setTotalItems(payment.getTotalItems());
    }

    private void validateAmount(double amount) {
        if (amount < MIN_AMOUNT_VALUE) {
            throw new ValidationException("The minimum amount available is ".concat(MIN_AMOUNT_VALUE.toString()));
        }
    }

    private void changePaymentToSuccess(Payment payment) {
        payment.setStatus(EPaymentStatus.SUCCESS);
        savePayment(payment);
    }

    private Payment findByOrderIdAndTransactionId(EventDto eventDto) {
        return paymentRepository
                .findByOrderIdAndTransactionId(eventDto.getPayload().getId(), eventDto.getTransactionId())
                .orElseThrow(() -> new ValidationException("Payment not found by orderId and transactionId"));
    }

    private void savePayment(Payment payment) {
        paymentRepository.save(payment);
    }

    private void handleSuccess(EventDto eventDto) {
        eventDto.setStatus(SUCCESS);
        eventDto.setSource(CURRENT_SOURCE);
        addHistory(eventDto, "Payment realized successfully!");
    }

    private void changePaymentStatusToRefund(EventDto eventDto) {
        var payment = findByOrderIdAndTransactionId(eventDto);
        payment.setStatus(EPaymentStatus.REFUND);
        setEventAmountItens(eventDto, payment);
        savePayment(payment);
    }

    private void addHistory(EventDto eventDto, String message) {
        var history = HistoryDto
                .builder()
                .status(eventDto.getStatus())
                .source(eventDto.getSource())
                .message(message)
                .createdAt(LocalDateTime.now())
                .build();

        eventDto.addHistory(history);
    }
    private void handleFailCurrentNotExecuted(EventDto eventDto, String message){
        eventDto.setStatus(ROLLBACK_PENDING);
        eventDto.setSource(CURRENT_SOURCE);
        addHistory(eventDto, "Fail to realized payment: " .concat(message));
    }

}

