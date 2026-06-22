package com.batistell.faceregistry.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_cpf", columnList = "cpf", unique = true)
})
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

    @Lob
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

    // Construtor padrão
    public User() {}

    // Construtor completo
    public User(UUID id, String cpf, String name, byte[] photo, String embeddingString, LocalDateTime createdAt) {
        this.id = id;
        this.cpf = cpf;
        this.name = name;
        this.photo = photo;
        this.embeddingString = embeddingString;
        this.createdAt = createdAt;
    }

    // Getters e Setters manuais
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCpf() { return cpf; }
    public void setCpf(String cpf) { this.cpf = cpf; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public byte[] getPhoto() { return photo; }
    public void setPhoto(byte[] photo) { this.photo = photo; }

    public String getEmbeddingString() { return embeddingString; }
    public void setEmbeddingString(String embeddingString) { this.embeddingString = embeddingString; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

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

    // Manual Builder Pattern
    public static UserBuilder builder() {
        return new UserBuilder();
    }

    public static class UserBuilder {
        private UUID id;
        private String cpf;
        private String name;
        private byte[] photo;
        private String embeddingString;
        private LocalDateTime createdAt;

        public UserBuilder id(UUID id) { this.id = id; return this; }
        public UserBuilder cpf(String cpf) { this.cpf = cpf; return this; }
        public UserBuilder name(String name) { this.name = name; return this; }
        public UserBuilder photo(byte[] photo) { this.photo = photo; return this; }
        public UserBuilder embeddingString(String embeddingString) { this.embeddingString = embeddingString; return this; }
        public UserBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public User build() {
            User user = new User(id, cpf, name, photo, embeddingString, createdAt);
            if (this.embeddingString != null) {
                user.setEmbeddingString(this.embeddingString);
            }
            return user;
        }
    }
}
