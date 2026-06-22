package com.batistell.faceregistry.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

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

    @Column(name = "photo", nullable = false)
    private byte[] photo;

    // Guardaremos o vetor de características (embedding) formatado como String (float;float;...)
    // para ser 100% compatível entre H2 (Testes) e PostgreSQL (Prod).
    @Column(name = "embedding", length = 8000, nullable = false)
    private String embeddingString;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Métodos utilitários de embedding
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
