package com.batistell.faceregistry.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entidade JPA para usuários cadastrados com assinatura facial.
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

    // Embedding serializado (float;float;...;float) de 512 dimensões
    @Column(name = "embedding", length = 8000, nullable = false)
    private String embeddingString;

    // Timestamp de criação do registro
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Desserializa a string em array de floats
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

    // Serializa o array de floats em string delimitada
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
