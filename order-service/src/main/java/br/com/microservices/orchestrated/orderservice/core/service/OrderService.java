package br.com.microservices.orchestrated.orderservice.core.service;

import br.com.microservices.orchestrated.orderservice.core.document.Event;
import br.com.microservices.orchestrated.orderservice.core.document.Order;
import br.com.microservices.orchestrated.orderservice.core.dto.OrderRequest;
import br.com.microservices.orchestrated.orderservice.core.producer.SagaProducer;
import br.com.microservices.orchestrated.orderservice.core.repository.OrderRepository;
import br.com.microservices.orchestrated.orderservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@AllArgsConstructor
public class OrderService {

    private static final String TRANSACTION_ID_PATTERN = "%s-%s";

    private final EventService eventService;
    private final SagaProducer producer;
    private final JsonUtil jsonUtil;
    private final OrderRepository orderRepository;

    public Order createOrder(OrderRequest orderRequest) {
        var order = Order
                .builder()
                .products(orderRequest.getProducts())
                .createdAt(LocalDateTime.now())
                .transactionId(
                        String.format(
                                TRANSACTION_ID_PATTERN,
                                Instant.now().toEpochMilli(),
                                UUID.randomUUID()
                                )
                ).build();
        orderRepository.save(order);
        producer.sendEvent(jsonUtil.toJson(createPlayload(order)));
        return order;
    }

    private Event createPlayload(Order order) {
        var event = Event
                .builder()
                .orderId(order.getId())
                .payload(order)
                .transactionId(order.getTransactionId())
                .createdAt(LocalDateTime.now())
                .build();

        return eventService.save(event);
    }
}
