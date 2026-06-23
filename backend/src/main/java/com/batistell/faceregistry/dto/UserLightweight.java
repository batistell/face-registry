package com.batistell.faceregistry.dto;

import java.util.UUID;

/**
 * DTO leve para recuperar as biometrias cadastradas no banco sem carregar
 * a foto binária pesada (BYTEA). Utilizado na inicialização do cache em memória.
 */
public record UserLightweight(
        UUID id,
        String cpf,
        String name,
        String embeddingString
) {}
