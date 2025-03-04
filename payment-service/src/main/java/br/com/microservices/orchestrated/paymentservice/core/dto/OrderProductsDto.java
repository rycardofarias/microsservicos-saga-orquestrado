package br.com.microservices.orchestrated.paymentservice.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderProductsDto {

    private ProductDto product;
    private int quantity;
}
