package com.batistell.faceregistry.dto;

import java.util.UUID;

// DTO leve para inicialização do cache biométrico
public record UserLightweight(
        UUID id,
        String cpf,
        String name,
        String embeddingString
) {}
