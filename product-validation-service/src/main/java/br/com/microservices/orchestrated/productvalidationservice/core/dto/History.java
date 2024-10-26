package br.com.microservices.orchestrated.productvalidationservice.core.dto;

import br.com.microservices.orchestrated.productvalidationservice.core.enums.ESagaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class History {

    private String source;
    private ESagaStatus status;
    private String message;
    private LocalDate createdAt;


}
