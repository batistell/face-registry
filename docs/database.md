# 💾 Persistência de Dados - Face Registry

O sistema **Face Registry** utiliza o banco de dados relacional **PostgreSQL** para armazenar as informações cadastrais e biométricas. A camada de persistência é gerenciada com **Spring Data JPA** e **Hibernate ORM**.

---

## 🗄️ Esquema Físico do Banco de Dados

A base de dados possui uma única tabela centralizada chamada `users`. Abaixo está a descrição detalhada de suas colunas e restrições:

| Coluna Banco | Tipo de Dados SQL | Campo na Entidade Java | Descrição e Restrições |
| :--- | :--- | :--- | :--- |
| **`id`** | `UUID` (PK) | `id` | Chave primária gerada automaticamente pelo Hibernate (`GenerationType.AUTO`). |
| **`cpf`** | `VARCHAR(11)` (Unique) | `cpf` | CPF do usuário. Contém apenas os 11 caracteres numéricos limpos. Não nulo. |
| **`name`** | `VARCHAR(255)` | `name` | Nome completo do usuário cadastrado. Não nulo. |
| **`photo`** | `BYTEA` | `photo` | Conteúdo binário da foto original de perfil. Limite de tamanho definido em 10 MB. Não nulo. |
| **`embedding`** | `VARCHAR(8000)` | `embeddingString` | String serializada que armazena os 512 valores floats da assinatura biométrica. Não nulo. |
| **`created_at`** | `TIMESTAMP` | `createdAt` | Data e hora de criação do registro biométrico. Gerado na aplicação via `@PrePersist`. Não nulo. |

### Índices Criados

Para garantir que as buscas pontuais por CPF (muito frequentes nas validações individuais e operações de CRUD) ocorram em tempo constante $O(1)$ no banco de dados, foi projetado um índice explícito:
- **Nome:** `idx_users_cpf`
- **Coluna associada:** `cpf`
- **Tipo:** B-Tree (Unique)

---

## 🧠 Serialização de Embeddings (512-D)

Diferente de sistemas que utilizam extensões vetoriais complexas (como `pgvector`) que poderiam introduzir dependências adicionais e limitar a portabilidade de testes automatizados, o **Face Registry** serializa o vetor tridimensional em texto simples separado por ponto e vírgula (`;`).

Exemplo do dado persistido na coluna `embedding`:
`0.041834;-0.009412;0.092104;...;-0.012903`

### Lógica na Entidade [User.java](file:///o:/JavaProjects/face-registry/backend/src/main/java/com/batistell/faceregistry/model/User.java)

A conversão ocorre de forma transparente na camada do Java através de métodos auxiliares na própria entidade:

```java
// String -> float[] (usado ao ler do banco para popular o cache)
public float[] getEmbedding() {
    if (this.embeddingString == null || this.embeddingString.isEmpty()) return null;
    String[] parts = this.embeddingString.split(";");
    float[] embedding = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
        embedding[i] = Float.parseFloat(parts[i]);
    }
    return embedding;
}

// float[] -> String (usado ao salvar o usuário com biometria extraída pela IA)
public void setEmbedding(float[] embedding) {
    if (embedding == null) {
        this.embeddingString = null;
        return;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < embedding.length; i++) {
        sb.append(embedding[i]);
        if (i < embedding.length - 1) sb.append(";");
    }
    this.embeddingString = sb.toString();
}
```

---

## ⚡ Otimização de Inicialização de Cache com DTO Projeção

Carregar fotos de milhares de usuários em memória durante a inicialização do cache biométrico inviabilizaria a aplicação (causando lentidão de tráfego de rede e estouro de memória `OutOfMemoryError`). 

Para resolver isso, o [UserRepository](file:///o:/JavaProjects/face-registry/backend/src/main/java/com/batistell/faceregistry/repository/UserRepository.java) utiliza um construtor de projeção leve do JPQL:

```java
@Query("SELECT new com.batistell.faceregistry.dto.UserLightweight(u.id, u.cpf, u.name, u.embeddingString) FROM User u")
List<UserLightweight> findAllLightweight();
```

Essa consulta instrui o Hibernate a recuperar **apenas** o `id`, `cpf`, `name` e `embedding` da tabela `users`, ignorando completamente a coluna de imagem binária pesada (`photo`). Isso possibilita carregar a biometria de 100.000 usuários em apenas alguns segundos consumindo poucos megabytes de heap na JVM.
