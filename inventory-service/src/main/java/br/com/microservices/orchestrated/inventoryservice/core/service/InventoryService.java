package br.com.microservices.orchestrated.inventoryservice.core.service;

import br.com.microservices.orchestrated.inventoryservice.config.exception.ValidationException;
import br.com.microservices.orchestrated.inventoryservice.core.dto.EventDto;
import br.com.microservices.orchestrated.inventoryservice.core.dto.HistoryDto;
import br.com.microservices.orchestrated.inventoryservice.core.dto.OrderDto;
import br.com.microservices.orchestrated.inventoryservice.core.dto.OrderProductsDto;
import br.com.microservices.orchestrated.inventoryservice.core.model.Inventory;
import br.com.microservices.orchestrated.inventoryservice.core.model.OrderInventory;
import br.com.microservices.orchestrated.inventoryservice.core.producer.KafkaProducer;
import br.com.microservices.orchestrated.inventoryservice.core.repository.InventoryRepository;
import br.com.microservices.orchestrated.inventoryservice.core.repository.OrderInventoryRepository;
import br.com.microservices.orchestrated.inventoryservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static br.com.microservices.orchestrated.inventoryservice.core.enums.ESagaStatus.*;

@Slf4j
@Service
@AllArgsConstructor
public class InventoryService {

    private static final String CURRENT_SOURCE = "INVENTORY_SERVICE";

    private final JsonUtil jsonUtil;
    private final KafkaProducer producer;
    private final InventoryRepository inventoryRepository;
    private final OrderInventoryRepository orderInventoryRepository;

    public void updateInventory(EventDto eventDto) {
        try {
            checkCurrentValidation(eventDto);
            createOrderInventory(eventDto);
            updateInventory(eventDto.getPayload());
            handleSuccess(eventDto);
        } catch (Exception ex) {
            log.error("Error trying to update inventory: ", ex);
            handleFailCurrentNotExecuted(eventDto, ex.getMessage());
        }
        producer.sendEvent(jsonUtil.toJson(eventDto));
    }

    private void checkCurrentValidation(EventDto eventDto) {
        if (orderInventoryRepository.existsByOrderIdAndTransactionId(
                eventDto.getPayload().getId(), eventDto.getTransactionId())) {
            throw new ValidationException("There's another transactionId for this validation.");
        }
    }

    private void createOrderInventory(EventDto eventDto) {
        eventDto
                .getPayload()
                .getProducts()
                .forEach(product -> {
                    var inventory = findInventoryByProductCode(product.getProduct().getCode());
                    var orderInventory = createOrderInventory(eventDto, product, inventory);
                    orderInventoryRepository.save(orderInventory);
                });
    }

    private OrderInventory createOrderInventory(EventDto eventDto,
                                                OrderProductsDto product,
                                                Inventory inventory) {
        return OrderInventory
                .builder()
                .inventory(inventory)
                .oldQuantity(inventory.getAvailable())
                .orderQuantity(product.getQuantity())
                .newQuantity(inventory.getAvailable() - product.getQuantity())
                .orderId(eventDto.getPayload().getId())
                .transactionId(eventDto.getTransactionId())
                .build();
    }

    private void updateInventory(OrderDto order) {
        order.getProducts().forEach(product -> {
            var inventory = findInventoryByProductCode(product.getProduct().getCode());
            checkInventory(inventory.getAvailable(), product.getQuantity());
            inventory.setAvailable(inventory.getAvailable() - product.getQuantity());
            inventoryRepository.save(inventory);
        });
    }

    private void checkInventory(int available, int orderQuantity) {
        if (orderQuantity > available) {
            throw new ValidationException("Product is out of stock!");
        }
    }

    private void handleSuccess(EventDto eventDto) {
        eventDto.setStatus(SUCCESS);
        eventDto.setSource(CURRENT_SOURCE);
        addHistory(eventDto, "Inventory updated successfully!");
    }

    private void addHistory(EventDto eventDto, String message) {
        var history = HistoryDto
                .builder()
                .source(eventDto.getSource())
                .status(eventDto.getStatus())
                .message(message)
                .createdAt(LocalDateTime.now())
                .build();
        eventDto.addHistory(history);
    }

    private void handleFailCurrentNotExecuted(EventDto eventDto, String message) {
        eventDto.setStatus(ROLLBACK_PENDInG);
        eventDto.setSource(CURRENT_SOURCE);
        addHistory(eventDto, "Fail to update inventory: ".concat(message));
    }

    public void rollbackInventory(EventDto eventDto) {
        eventDto.setStatus(FAIL);
        eventDto.setSource(CURRENT_SOURCE);
        try {
            returnInventoryToPreviousValues(eventDto);
            addHistory(eventDto, "Rollback executed for inventory!");
        } catch (Exception ex) {
            addHistory(eventDto, "Rollback not executed for inventory: ".concat(ex.getMessage()));
        }
        producer.sendEvent(jsonUtil.toJson(eventDto));
    }

    private void returnInventoryToPreviousValues(EventDto eventDto) {
        orderInventoryRepository
                .findByOrderIdAndTransactionId(eventDto.getPayload().getId(), eventDto.getTransactionId())
                .forEach(orderInventory -> {
                    var inventory = orderInventory.getInventory();
                    inventory.setAvailable(orderInventory.getOldQuantity());
                    inventoryRepository.save(inventory);
                    log.info("Restored inventory for order {}: from {} to {}",
                            eventDto.getPayload().getId(), orderInventory.getNewQuantity(), inventory.getAvailable());
                });
    }

    private Inventory findInventoryByProductCode(String productCode) {
        return inventoryRepository
                .findByProductCode(productCode)
                .orElseThrow(() -> new ValidationException("Inventory not found by informed product."));
    }
}