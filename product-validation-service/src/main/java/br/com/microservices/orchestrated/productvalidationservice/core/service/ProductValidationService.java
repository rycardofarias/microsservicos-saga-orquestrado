package br.com.microservices.orchestrated.productvalidationservice.core.service;

import br.com.microservices.orchestrated.productvalidationservice.config.exception.ValidationException;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.EventDto;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.HistoryDto;
import br.com.microservices.orchestrated.productvalidationservice.core.dto.OrderProductsDto;
import br.com.microservices.orchestrated.productvalidationservice.core.model.Validation;
import br.com.microservices.orchestrated.productvalidationservice.core.producer.KafkaProducer;
import br.com.microservices.orchestrated.productvalidationservice.core.repository.ProductRepository;
import br.com.microservices.orchestrated.productvalidationservice.core.repository.ValidationRepository;
import br.com.microservices.orchestrated.productvalidationservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static br.com.microservices.orchestrated.productvalidationservice.core.enums.ESagaStatus.*;
import static org.springframework.util.ObjectUtils.isEmpty;

@Slf4j
@Service
@AllArgsConstructor
public class ProductValidationService {

    private static final String CURRENT_SOURCE = "PRODUCT_VALIDATION_SERVICE";

    private final JsonUtil jsonUtil;
    private final KafkaProducer kafkaProducer;
    private final ProductRepository productRepository;
    private final ValidationRepository validationRepository;

    public void validateExistingProducts(EventDto eventDto){
        try{

            checkCurrentValidation(eventDto);
            createValidation(eventDto, true);
            handleSuccess(eventDto);

        } catch (Exception e){
            log.error("Error tryning to validate products", e);
            handleFailCurrentNotExecuted(eventDto, e.getMessage());
        }
        kafkaProducer.sendEvent(jsonUtil.toJson(eventDto));
    }

    private void validateProductsInformed(EventDto eventDto){

        if (isEmpty(eventDto.getPayload().getProducts())
                || isEmpty(eventDto.getPayload())){
            throw new RuntimeException("Products list is empty");
        }

        if (isEmpty(eventDto.getPayload().getId())
                || isEmpty(eventDto.getPayload().getTransactionId())){
            throw new RuntimeException("OrderId and TransactionId must be informed");
        }
    }

    private void checkCurrentValidation(EventDto eventDto){

        validateProductsInformed(eventDto);
        if (validationRepository.existsByOrderIdAndTransactionId(eventDto.getOrderId(),
                eventDto.getTransactionId())){
            throw new RuntimeException("There's another transactionId for this validation");
        }
        eventDto.getPayload().getProducts().forEach(productDto -> {
            validateProductInformad(productDto);
        });
    }

    private void validateProductInformad(OrderProductsDto productDto){
        if (isEmpty(productDto.getProduct())
                || isEmpty(productDto.getProduct().getCode())){
            throw new RuntimeException("Product must be informed");
        }
    }

    private void validateExistingProduct(String code){
        if (!productRepository.existsByCode(code)){
            throw new ValidationException("Product does not exist in database!");
        }
    }

    private void createValidation(EventDto eventDto, boolean success){
        var validation = Validation
                .builder()
                .orderId(eventDto.getPayload().getId())
                .transactionId(eventDto.getTransactionId())
                .sucess(success)
                .build();

        validationRepository.save(validation);
    }

    private void handleSuccess(EventDto eventDto){
        eventDto.setStatus(SUCESSO);
        eventDto.setSource(CURRENT_SOURCE);
        addHistory(eventDto, "Products are validated successfully!");
    }

    private void addHistory(EventDto eventDto, String message){
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
        addHistory(eventDto, "Fail to validate products: " .concat(message));
        createValidation(eventDto, false);
    }

    public void rollbackEvent(EventDto eventDto){
        changeValidationToFail(eventDto);
        eventDto.setStatus(FAIL);
        eventDto.setSource(CURRENT_SOURCE);
        addHistory(eventDto, "Rollback executed on product validation!");
        kafkaProducer.sendEvent(jsonUtil.toJson(eventDto));
    }

    private void changeValidationToFail(EventDto eventDto) {
        validationRepository
                .findByOrderIdAndTransactionId(eventDto.getOrderId(),
                        eventDto.getTransactionId())
                .ifPresentOrElse(validation -> {
                    validation.setSucess(false);
                    validationRepository.save(validation);
                }, () -> createValidation(eventDto, false));
    }
}
