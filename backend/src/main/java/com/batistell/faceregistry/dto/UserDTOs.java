package com.batistell.faceregistry.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public class UserDTOs {

    public record UserResponse(
            UUID id,
            String cpf,
            String name,
            String photoBase64,
            LocalDateTime createdAt
    ) {}

    public record VerificationResponse(
            boolean match,
            double similarity,
            double threshold
    ) {}

    public record IdentificationResponse(
            boolean identified,
            String cpf,
            String name,
            String photoBase64,
            double similarity,
            double threshold
    ) {}

    // DTO auxiliar para tráfego em lote no service
    public record UserBatchRequest(
            String cpf,
            String name,
            byte[] photo,
            String filename
    ) {}

    public record DuplicatePairResponse(
            UserResponse user1,
            UserResponse user2,
            double similarity
    ) {}
}
