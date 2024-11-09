package br.com.microservices.orchestrated.productvalidationservice.core.repository;

import br.com.microservices.orchestrated.productvalidationservice.core.dto.Product;
import br.com.microservices.orchestrated.productvalidationservice.core.model.Validation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ValidationRepository extends JpaRepository<Product, Integer> {

    Boolean existsByIdAndTransactionId(String orderI, String transactionId);
    Optional<Validation> findByOrderIdAndTransactionId(String orderId, String transactionId);
}

