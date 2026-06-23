package com.batistell.faceregistry.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entidade JPA que representa um usuário cadastrado no sistema de biometria facial.
 * 
 * Cada usuário possui um CPF único (11 dígitos numéricos), um nome, uma foto binária
 * (armazenada como BYTEA no PostgreSQL) e um template facial (embedding de 512 dimensões)
 * pré-calculado no momento do cadastro para permitir comparações biométricas rápidas.
 * 
 * O embedding é serializado como String delimitada por ';' para garantir portabilidade
 * entre PostgreSQL (produção) e H2 (testes unitários).
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_cpf", columnList = "cpf", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @NotBlank(message = "O CPF é obrigatório")
    @Pattern(regexp = "\\d{11}", message = "O CPF deve conter exatamente 11 dígitos numéricos")
    @Column(nullable = false, unique = true, length = 11)
    private String cpf;

    @NotBlank(message = "O nome é obrigatório")
    @Column(nullable = false)
    private String name;

    @Column(name = "photo", nullable = false, length = 10485760)
    private byte[] photo;

    /**
     * Template facial serializado como String (formato: "float;float;...;float").
     * Contém 512 valores float L2-normalizados que representam as características
     * únicas do rosto do usuário, gerados pelo modelo FaceNet/ArcFace via DJL.
     * 
     * A serialização como VARCHAR garante compatibilidade entre PostgreSQL e H2.
     */
    @Column(name = "embedding", length = 8000, nullable = false)
    private String embeddingString;

    /** Timestamp de criação do registro, definido automaticamente via @PrePersist. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Define o timestamp de criação automaticamente antes do INSERT no banco. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Desserializa o embedding armazenado como String para um array de floats.
     * @return float[512] com o template facial, ou null se não houver embedding.
     */
    public float[] getEmbedding() {
        if (this.embeddingString == null || this.embeddingString.isEmpty()) {
            return null;
        }
        String[] parts = this.embeddingString.split(";");
        float[] embedding = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            embedding[i] = Float.parseFloat(parts[i]);
        }
        return embedding;
    }

    /**
     * Serializa o array de floats do embedding para String delimitada por ';'.
     * @param embedding float[512] com o template facial gerado pelo modelo de IA.
     */
    public void setEmbedding(float[] embedding) {
        if (embedding == null) {
            this.embeddingString = null;
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) {
                sb.append(";");
            }
        }
        this.embeddingString = sb.toString();
    }
}
