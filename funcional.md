# ⚙️ Funcional — Funcionamento Interno do Face Registry

Este documento descreve em detalhes o **funcionamento interno completo** do sistema Face Registry. Cada fluxo, componente e mecanismo é explicado passo a passo com diagramas, trechos de código e referências aos arquivos-fonte.

---

## Índice

1. [Visão Geral da Arquitetura](#1-visão-geral-da-arquitetura)
2. [Ciclo de Vida da Aplicação (Startup)](#2-ciclo-de-vida-da-aplicação-startup)
3. [Fluxo de Cadastro Individual](#3-fluxo-de-cadastro-individual)
4. [Fluxo de Atualização de Usuário](#4-fluxo-de-atualização-de-usuário)
5. [Fluxo de Exclusão de Usuário](#5-fluxo-de-exclusão-de-usuário)
6. [Fluxo de Listagem e Busca](#6-fluxo-de-listagem-e-busca)
7. [Fluxo de Cadastro em Lote (Batch)](#7-fluxo-de-cadastro-em-lote-batch)
8. [Pipeline de Processamento Biométrico](#8-pipeline-de-processamento-biométrico)
9. [Verificação Facial 1:1](#9-verificação-facial-11)
10. [Identificação Facial 1:N](#10-identificação-facial-1n)
11. [Prevenção de Duplicados Biométricos](#11-prevenção-de-duplicados-biométricos)
12. [Controle de Concorrência](#12-controle-de-concorrência)
13. [Sistema de Tratamento de Erros](#13-sistema-de-tratamento-de-erros)
14. [Modo Mock (Simulação)](#14-modo-mock-simulação)
15. [Frontend Angular — Arquitetura Interna](#15-frontend-angular--arquitetura-interna)
16. [Infraestrutura Docker & Rede](#16-infraestrutura-docker--rede)

---

## 1. Visão Geral da Arquitetura

O sistema é composto por **4 serviços** containerizados que se comunicam via rede interna do Docker:

```
┌─────────────────────────────────────────────────────────────────┐
│                        Docker Network                          │
│                                                                 │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────┐           │
│  │ Navegador │───►│  Nginx:443   │───►│ Angular SPA  │           │
│  │ (Usuário) │    │ (SSL Proxy)  │    │ (Estático)   │           │
│  └──────────┘    └──────┬───────┘    └──────────────┘           │
│                         │ /api/*                                │
│                         ▼                                       │
│                  ┌──────────────┐    ┌──────────────┐           │
│                  │ Spring Boot  │───►│ PostgreSQL   │           │
│                  │ :8080 (API)  │    │ :5432 (DB)   │           │
│                  └──────┬───────┘    └──────────────┘           │
│                         │                                       │
│                  ┌──────▼───────┐                                │
│                  │ DJL PyTorch  │                                │
│                  │ (JNI/C++)    │                                │
│                  └──────────────┘                                │
└─────────────────────────────────────────────────────────────────┘
```

**Fluxo de uma requisição típica:**
1. Navegador → Nginx (porta 443/HTTPS ou 80/HTTP)
2. Nginx roteia `/api/*` → Spring Boot (porta 8080)
3. Spring Boot processa lógica de negócio, invoca DJL para IA
4. DJL executa inferência via PyTorch nativo (JNI C++)
5. Spring Boot persiste resultado no PostgreSQL via JPA
6. Resposta retorna ao navegador como JSON

---

## 2. Ciclo de Vida da Aplicação (Startup)

### 2.1 Sequência de inicialização

```
docker compose up
    │
    ├── PostgreSQL inicia
    │   └── Healthcheck: pg_isready -U postgres -d faceregistry
    │       └── Retorna "ready" → backend pode iniciar
    │
    ├── Spring Boot inicia (depende de db:service_healthy)
    │   ├── Hibernate: DDL auto-update → cria/atualiza tabela "users"
    │   ├── @PostConstruct FaceBiometricsService.init()
    │   │   ├── Baixa retinaface.zip (~30MB) do repositório DJL
    │   │   ├── Baixa face_feature.zip (~90MB) do repositório DJL
    │   │   ├── Carrega ambos os modelos na memória via PyTorch JNI
    │   │   └── Log: "Motor biométrico com Modelos Reais DJL carregados com sucesso!"
    │   └── Tomcat embedded inicia na porta 8080
    │
    └── Nginx inicia (depende de backend)
        ├── Gera certificado SSL self-signed
        ├── Serve Angular estático em / e /face-registry/
        └── Proxy reverso /api/* → backend:8080
```

### 2.2 Inicialização dos modelos DJL

O `FaceBiometricsService` é um bean Spring singleton. No `@PostConstruct`, ele:

1. **Verifica a flag `face.biometrics.mock`:** Se `true` (profile `local`), pula o carregamento e entra em mock mode.
2. **Cria os Translators customizados:**
   - `FaceDetectionTranslator`: Define pré/pós-processamento para RetinaFace.
   - `FaceFeatureTranslator`: Define pré/pós-processamento para FaceNet.
3. **Carrega os modelos via `ModelZoo.loadModel()`:**
   - Os modelos são baixados da URL do repositório DJL na primeira execução.
   - São armazenados em cache em `~/.djl.ai/` para execuções subsequentes.
4. **Se o carregamento falhar:** O sistema entra em mock mode e loga um warning.

### 2.3 DDL Hibernate

A tabela `users` é criada/atualizada automaticamente pelo Hibernate com `ddl-auto: update`:

```sql
CREATE TABLE users (
    id          UUID PRIMARY KEY,
    cpf         VARCHAR(11) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    photo       BYTEA NOT NULL,              -- foto original (até 10MB)
    embedding   VARCHAR(8000) NOT NULL,       -- 512 floats serializados
    created_at  TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_users_cpf ON users(cpf);
```

---

## 3. Fluxo de Cadastro Individual

**Endpoint:** `POST /api/users` (`multipart/form-data`)

**Parâmetros:**
- `cpf` (String) — CPF do usuário
- `name` (String) — Nome completo
- `photo` (File) — Arquivo de imagem JPG/JPEG/PNG

### Diagrama de sequência:

```
Frontend                    Controller              Service               Biometrics              Repository
   │                           │                       │                      │                       │
   │── POST /api/users ───────►│                       │                      │                       │
   │   (cpf, name, photo)      │                       │                      │                       │
   │                           │── validateFileFormat()│                      │                       │
   │                           │   (extensão + MIME)   │                      │                       │
   │                           │                       │                      │                       │
   │                           │── createUser() ──────►│                      │                       │
   │                           │                       │── cleanAndValidate() │                       │
   │                           │                       │   CPF (11 dígitos)   │                       │
   │                           │                       │                      │                       │
   │                           │                       │── existsByCpf() ────────────────────────────►│
   │                           │                       │   (verifica duplicata)│                      │
   │                           │                       │                      │                       │
   │                           │                       │── extractFaceEmbedding() ──►│                │
   │                           │                       │   (foto em bytes)    │     │                 │
   │                           │                       │                      │     │── validateImage │
   │                           │                       │                      │     │── detect faces  │
   │                           │                       │                      │     │── crop face     │
   │                           │                       │                      │     │── extract 512-D │
   │                           │                       │◄── float[512] ───────│◄────│                 │
   │                           │                       │                      │                       │
   │                           │                       │── checkForDuplicateFace() ─────────────────►│
   │                           │                       │   (compara com TODOS)│                      │
   │                           │                       │                      │                       │
   │                           │                       │── save(User) ───────────────────────────────►│
   │                           │                       │                      │                       │
   │◄── 201 Created ──────────│◄── UserResponse ──────│                      │                       │
   │   (id, cpf, name, photo64)│                       │                      │                       │
```

### Passos detalhados:

1. **Validação do arquivo** (`UserController.validateFileFormat()`):
   - Verifica extensão (`.jpg`, `.jpeg`, `.png`).
   - Verifica Content-Type MIME (`image/jpeg`, `image/png`).
   - Se inválido: `400 Bad Request` com mensagem específica.

2. **Limpeza e validação do CPF** (`UserService.cleanAndValidateCpf()`):
   - Remove caracteres não numéricos.
   - Valida 11 dígitos, rejeita dígitos repetidos, calcula verificadores.
   - Se inválido: `400 Bad Request`.

3. **Verificação de CPF duplicado:**
   - `userRepository.existsByCpf(cpf)` → Se já existe: `409 Conflict`.

4. **Extração do embedding facial** (detalhado na [Seção 8](#8-pipeline-de-processamento-biométrico)).

5. **Verificação de face duplicada** (detalhado na [Seção 11](#11-prevenção-de-duplicados-biométricos)).

6. **Persistência:**
   - Cria a entidade `User` com Builder pattern.
   - Serializa o `float[512]` → `String` via `user.setEmbedding()`.
   - `userRepository.save()` persiste no banco.
   - `@Transactional` garante que tudo é atômico.

7. **Resposta:**
   - Retorna `201 Created` com `UserResponse` (foto convertida para Base64).

---

## 4. Fluxo de Atualização de Usuário

**Endpoint:** `PUT /api/users/{cpf}` (`multipart/form-data`)

**Parâmetros opcionais:**
- `name` (String) — Novo nome
- `photo` (File) — Nova foto

### Passos:

1. **Busca com lock pessimista:**
   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   Optional<User> findByCpfWithLock(String cpf);
   ```
   O `SELECT ... FOR UPDATE` bloqueia a linha no banco, impedindo updates concorrentes.

2. **Atualização condicional:**
   - Se `name` foi fornecido: atualiza.
   - Se `photo` foi fornecida: gera novo embedding, verifica duplicata facial (excluindo o próprio CPF), atualiza foto e embedding.

3. **Persistência:** `userRepository.save(user)` dentro de `@Transactional`.

4. **Resposta:** `200 OK` com dados atualizados.

---

## 5. Fluxo de Exclusão de Usuário

**Endpoint:** `DELETE /api/users/{cpf}`

1. Busca o usuário por CPF.
2. Se não encontrado: `404 Not Found`.
3. `userRepository.delete(user)` remove do banco.
4. Resposta: `204 No Content`.

---

## 6. Fluxo de Listagem e Busca

### Backend

**Endpoint:** `GET /api/users` → Retorna `List<UserResponse>` com todos os usuários.

**Endpoint:** `GET /api/users/{cpf}` → Retorna detalhe de um usuário.

Ambos usam `@Transactional(readOnly = true)` para otimização de leitura.

### Frontend

A listagem no Angular funciona assim:

1. **Ao carregar a aba "Listar":** `loadUsers()` faz `GET /api/users`.
2. **Busca local em tempo real:** O getter `filteredUsers` filtra `this.users` pela variável `searchTerm` (nome ou CPF), **sem chamada ao backend**.
3. **Grid de cards:** Cada card exibe a foto (Base64 → `<img>`), nome, CPF formatado e data.
4. **Modal de detalhes:** Ao clicar num card, `selectUser(user)` abre um modal com UUID, embedding badge e botão de edição.

---

## 7. Fluxo de Cadastro em Lote (Batch)

**Endpoint:** `POST /api/users/batch` (`multipart/form-data`)

**Parâmetros (arrays paralelos):**
- `cpfs[]` — Lista de CPFs
- `names[]` — Lista de nomes
- `photos[]` — Lista de arquivos

### Diagrama de execução paralela:

```
Requisição com N usuários
         │
         ▼
┌────────────────────────────┐
│  Validação básica          │  Thread principal
│  (qtd cpfs == names == fotos)
└────────────┬───────────────┘
             │
             ▼
┌────────────────────────────┐
│  Para cada item do lote:   │  N Virtual Threads em paralelo
│  ┌─────────────────┐       │
│  │ Virtual Thread 1 │       │  ┌── cleanAndValidateCpf()
│  │ CPF: 111...      │──────│──┤── existsByCpf() → DB check
│  │ Nome: Alice      │       │  ├── extractFaceEmbedding() → DJL (CPU-bound)
│  │ Foto: alice.jpg  │       │  └── checkForDuplicateFace() → DB scan
│  └─────────────────┘       │
│  ┌─────────────────┐       │
│  │ Virtual Thread 2 │       │  (mesmo pipeline)
│  │ CPF: 222...      │       │
│  └─────────────────┘       │
│  ...                        │
└────────────┬───────────────┘
             │ CompletableFuture.join() (aguarda TODAS)
             ▼
┌────────────────────────────┐
│  Verificação cruzada       │  Thread principal
│  dentro do lote (N×N/2)    │
│  Se faces iguais → 409     │
└────────────┬───────────────┘
             │
             ▼
┌────────────────────────────┐
│  saveAll(usersToSave)      │  Thread principal (@Transactional)
│  Persistência atômica      │
│  Tudo ou nada              │
└────────────┬───────────────┘
             │
             ▼
         201 Created
```

### Garantias de atomicidade:
- Se **qualquer** item do lote falhar (CPF inválido, face duplicada, imagem sem rosto), toda a transação é revertida.
- A exceção é propagada do `CompletableFuture.join()` via `CompletionException`, desempacotada e relançada.

---

## 8. Pipeline de Processamento Biométrico

O coração do sistema é o método `FaceBiometricsService.extractFaceEmbedding()`. Ele recebe os bytes brutos de uma imagem e retorna um `float[512]`.

### 8.1 Validação da imagem

```java
private void validateImage(byte[] bytes) {
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
    if (img == null) throw new InvalidImageException("Arquivo corrompido...");
}
```

- Tenta decodificar a imagem com `javax.imageio.ImageIO`.
- Se os bytes não formam uma imagem válida → `400 Bad Request`.

### 8.2 Detecção facial (RetinaFace)

**Modelo:** `retinaface.zip` (PyTorch)

**Pré-processamento** (`FaceDetectionTranslator.processInput()`):
```
Imagem original
    │
    ├── toNDArray(FLAG.COLOR) → tensor HWC RGB
    ├── transpose(2,0,1)     → tensor CHW RGB
    ├── flip(0)              → tensor CHW BGR (RetinaFace espera BGR)
    ├── toFloat32()
    └── sub(mean[104,117,123]) → normalização ImageNet
```

**Pós-processamento** (`FaceDetectionTranslator.processOutput()`):
```
Saída do modelo (3 tensores)
    │
    ├── tensor[0]: Bounding boxes codificadas (offsets relativos a anchor boxes)
    ├── tensor[1]: Probabilidades (confiança de ser face)
    └── tensor[2]: Landmarks faciais (5 pontos: olhos, nariz, boca)
        │
        ├── Decodificação de anchor boxes (boxRecover)
        ├── Filtragem por confThresh > 0.85
        ├── NMS (Non-Maximum Suppression) com IoU > 0.45
        └── DetectedObjects(names, probs, boundingBoxes)
```

**Validações pós-detecção:**
| Condição | Resultado |
|---|---|
| 0 rostos detectados | `NoFaceDetectedException` (400) |
| 2+ rostos detectados | `MultipleFacesDetectedException` (400) |
| Rosto < 15% da imagem | `InvalidImageException` — "rosto muito pequeno" |
| Rosto encostando na borda (< 2% de margem) | `InvalidImageException` — "rosto cortado" |

### 8.3 Recorte do rosto

```java
int x = (int)(rect.getX() * image.getWidth());
int y = (int)(rect.getY() * image.getHeight());
int w = (int)(rect.getWidth() * image.getWidth());
int h = (int)(rect.getHeight() * image.getHeight());
Image croppedFace = image.getSubImage(x, y, w, h);
```

O rosto é recortado do bounding box detectado, isolando apenas a região facial.

### 8.4 Extração de features (FaceNet/ArcFace)

**Modelo:** `face_feature.zip` (PyTorch, MobileFaceNet)

**Pré-processamento** (`FaceFeatureTranslator.processInput()`):
```
Rosto recortado
    │
    ├── resize(112, 112)     → tamanho padrão ArcFace
    ├── transpose(2,0,1)     → CHW
    ├── toFloat32()
    ├── div(255.0)           → escala para [0.0, 1.0]
    ├── sub(mean=0.5)        → centraliza em zero
    └── div(std=0.5019608)   → normaliza variância
```

**Saída:** Tensor 1D de 512 floats → `list.get(0).toFloatArray()`.

### 8.5 Resultado

O `float[512]` retornado é o **template facial** (embedding) que representa matematicamente as características únicas daquele rosto. É este vetor que será armazenado no banco e usado para todas as comparações futuras.

---

## 9. Verificação Facial 1:1

**Endpoint:** `POST /api/users/verify` (`multipart/form-data`)

**Parâmetros:**
- `cpf` — CPF do usuário alvo
- `photo` — Foto para verificar

### Fluxo interno:

```
Foto enviada        Foto cadastrada (do banco)
     │                      │
     ▼                      ▼
 extractFaceEmbedding()  user.getEmbedding()
     │                      │
     ▼                      ▼
   float[512]            float[512]
     │                      │
     └──────┬───────────────┘
            │
            ▼
    calculateCosineSimilarity(A, B)
            │
            ▼
    similarity = dot(A,B) / (||A|| × ||B||)
            │
            ▼
    isMatch = similarity >= threshold (0.60)
            │
            ▼
    VerificationResponse {
        match: true/false,
        similarity: 0.87,
        threshold: 0.60
    }
```

### Fórmula da Similaridade de Cosseno:

```
                    Σ(Ai × Bi)
cos(A, B) = ─────────────────────────
             √(Σ Ai²) × √(Σ Bi²)
```

Onde `A` e `B` são vetores de 512 dimensões. O resultado está no intervalo [-1, 1]:
- **1.0** = vetores idênticos (mesma pessoa)
- **0.0** = vetores ortogonais (sem relação)
- **< 0** = vetores opostos (muito improvável em faces)

---

## 10. Identificação Facial 1:N

**Endpoint:** `POST /api/users/identify` (`multipart/form-data`)

**Parâmetro:**
- `photo` — Foto do rosto a identificar (sem CPF)

### Fluxo interno:

```
Foto enviada
     │
     ▼
extractFaceEmbedding() → float[512] (target)
     │
     ▼
userRepository.findAll() → List<User> (N usuários)
     │
     ▼
allUsers.parallelStream()  ◄── Comparação em paralelo
     │
     ├── Thread 1: cos(target, user1.embedding) → 0.23
     ├── Thread 2: cos(target, user2.embedding) → 0.91 ◄── melhor
     ├── Thread 3: cos(target, user3.embedding) → 0.12
     └── Thread N: cos(target, userN.embedding) → 0.45
     │
     ▼
max(similarity) → MatchResult(user2, 0.91)
     │
     ▼
0.91 >= 0.60 (threshold)?
     │
     ├── SIM → IdentificationResponse { identified: true, user2, similarity: 0.91 }
     └── NÃO → IdentificationResponse { identified: false, bestSimilarity: X }
```

### Performance:
- **`parallelStream()`** distribui as comparações entre os cores disponíveis da CPU usando o ForkJoinPool.
- Cada comparação individual é O(512) — uma multiplicação escalar de 512 floats.
- A complexidade total é O(N × 512), onde N é o número de usuários no banco.
- Os embeddings já estão pré-calculados no momento do cadastro, então não há processamento de imagem durante a identificação (apenas para a foto enviada).

---

## 11. Prevenção de Duplicados Biométricos

### Quando é executada:
- **Cadastro individual** (`createUser`) — compara contra todos.
- **Atualização com foto** (`updateUser`) — compara contra todos **exceto o próprio CPF**.
- **Batch** — compara contra o banco E entre os itens do lote.

### Método `checkForDuplicateFace()`:

```java
private void checkForDuplicateFace(float[] embedding, String excludeCpf) {
    List<User> allUsers = userRepository.findAll();

    Optional<MatchResult> duplicateMatch = allUsers.parallelStream()
            .filter(user -> excludeCpf == null || !user.getCpf().equals(excludeCpf))
            .map(user -> {
                double similarity = calculateCosineSimilarity(embedding, user.getEmbedding());
                return new MatchResult(user, similarity);
            })
            .filter(result -> isMatch(result.similarity()))
            .max(Comparator.comparingDouble(MatchResult::similarity));

    if (duplicateMatch.isPresent()) {
        throw new DuplicateFaceException("Esta face já está cadastrada...");
    }
}
```

- **`excludeCpf`**: No update, exclui o próprio usuário da comparação (senão a foto atualizada "daria match" consigo mesma).
- **`parallelStream()`**: Acelera a busca usando múltiplos cores.
- **Exceção rica**: A mensagem inclui o CPF e nome do usuário duplicado encontrado.

### Verificação cruzada no batch:

Após o processamento paralelo das imagens, o lote inteiro é verificado internamente:

```java
for (int i = 0; i < usersToSave.size(); i++) {
    for (int j = i + 1; j < usersToSave.size(); j++) {
        double similarity = calculateCosineSimilarity(u1.getEmbedding(), u2.getEmbedding());
        if (isMatch(similarity)) {
            throw new DuplicateFaceException("Duplicidade detectada no lote...");
        }
    }
}
```

Isso garante que o mesmo lote não contenha a mesma pessoa com CPFs diferentes.

---

## 12. Controle de Concorrência

### 12.1 Lock pessimista no update

```sql
SELECT * FROM users WHERE cpf = ? FOR UPDATE
```

Este lock garante que se duas requisições tentarem atualizar o mesmo usuário simultaneamente:
1. A primeira adquire o lock e processa normalmente.
2. A segunda **espera** até o lock ser liberado (fim da transação da primeira).
3. Não há risco de lost update ou inconsistência.

### 12.2 Virtual Threads no batch

```java
private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
```

Cada item do batch é processado numa Virtual Thread separada. Diferenças em relação a threads tradicionais:

| Aspecto | Platform Thread | Virtual Thread |
|---|---|---|
| Custo de criação | ~1MB stack | ~poucos KB |
| Pool fixo necessário? | Sim | Não |
| Bloqueio em I/O | Bloqueia thread OS | Libera carrier thread |
| Escalabilidade | Centenas | Milhões |

### 12.3 Constraint única no banco

Mesmo que a validação `existsByCpf()` no service não detecte duplicata (race condition), o índice único `idx_users_cpf` no PostgreSQL garante que o `INSERT` falhará com `DataIntegrityViolationException`, que é tratada como erro de duplicata.

---

## 13. Sistema de Tratamento de Erros

### Fluxo de uma exceção:

```
Exceção lançada no Service/Biometrics
         │
         ▼
GlobalExceptionHandler (@RestControllerAdvice)
         │
         ├── Identifica o tipo da exceção
         ├── Mapeia para HttpStatus apropriado
         └── Constrói ErrorPayload:
              {
                "status": 400,
                "error": "Bad Request",
                "message": "Nenhum rosto foi detectado na imagem...",
                "timestamp": "2026-06-23T01:15:00"
              }
         │
         ▼
ResponseEntity<ErrorPayload> retorna ao cliente
         │
         ▼
Frontend Angular recebe err.error.message
         │
         ▼
showError(err.error?.message || 'Erro genérico')
         │
         ▼
Toast de erro vermelho exibido na interface
```

### Hierarquia de exceções:

```
RuntimeException
├── InvalidCpfException         → 400
├── InvalidImageException       → 400
├── NoFaceDetectedException     → 400
├── MultipleFacesDetectedException → 400
├── UserNotFoundException       → 404
├── DuplicateCpfException       → 409
├── DuplicateFaceException      → 409
├── BiometricProcessingException → 503
└── Exception (catch-all)       → 500
```

---

## 14. Modo Mock (Simulação)

### Quando é ativado:
- **Configuração explícita:** `face.biometrics.mock=true` no `application.yml` (profile `local`).
- **Falha no carregamento dos modelos DJL** na inicialização (`@PostConstruct`).

### Como gera embeddings:

```java
private float[] generateDeterministicMockEmbedding(byte[] bytes) {
    // 1. Hash SHA-256 dos bytes da imagem
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);

    // 2. Seed estável derivada dos primeiros 8 bytes do hash
    long seed = 0;
    for (int i = 0; i < 8; i++) {
        seed = (seed << 8) | (hash[i] & 0xFF);
    }

    // 3. Random com seed fixa → mesmo arquivo = mesmo embedding
    Random rand = new Random(seed);
    float[] embedding = new float[512];
    for (int i = 0; i < 512; i++) {
        embedding[i] = (float) rand.nextGaussian();
    }

    // 4. L2-normalização → similaridade de cosseno funciona corretamente
    double norm = Math.sqrt(sumSquare);
    for (int i = 0; i < 512; i++) {
        embedding[i] /= norm;
    }
    return embedding;
}
```

**Propriedades do mock:**
- **Determinístico:** Mesmos bytes → mesmo embedding → similaridade 1.0.
- **Diferenciável:** Bytes diferentes → embeddings diferentes → similaridade ~0.0.
- **Normalizado:** A L2-normalização garante que `cos(A,B) ∈ [-1, 1]`.

### Simulação de erros biométricos:

O mock usa o **nome do arquivo** para simular cenários de erro:

| Nome contém | Exceção lançada |
|---|---|
| `noface`, `sem_rosto`, `sem-rosto` | `NoFaceDetectedException` |
| `multiple`, `multiplos`, `grupo` | `MultipleFacesDetectedException` |

---

## 15. Frontend Angular — Arquitetura Interna

### 15.1 Detecção de URL da API

```typescript
private apiUrl = window.location.hostname === 'localhost' && window.location.port === '4200'
    ? 'http://localhost:8080/api/users'   // ng serve (dev)
    : 'api/users';                         // Nginx (prod)
```

### 15.2 Gerenciamento de estado

Não há biblioteca de estado (NgRx, etc.). O estado é gerenciado por **propriedades do componente**:

| Propriedade | Tipo | Função |
|---|---|---|
| `users` | `UserResponse[]` | Cache local da lista de usuários |
| `selectedUser` | `UserResponse \| null` | Usuário selecionado no modal de detalhes |
| `activeTab` | `string` | Controla qual seção é exibida |
| `isLoading` | `boolean` | Spinner de carregamento global |
| `isActionLoading` | `boolean` | Spinner de ação específica (submit) |
| `searchTerm` | `string` | Filtro de busca (nome/CPF) |
| `batchList` | `BatchItem[]` | Items do formulário de batch |
| `verifyResult` | `VerificationResponse \| null` | Resultado da verificação 1:1 |
| `identifyResult` | `IdentificationResponse \| null` | Resultado da identificação 1:n |

### 15.3 Sistema de notificações (Toast)

```typescript
showSuccess(msg: string) {
    this.successMsg = msg;
    this.errorMsg = '';
    setTimeout(() => this.successMsg = '', 6000);  // Auto-dismiss após 6s
}

showError(msg: string) {
    this.errorMsg = msg;
    this.successMsg = '';
    // Erro NÃO fecha automaticamente — requer clique do usuário
}
```

### 15.4 Webcam nativa

O fluxo de captura via webcam:

```
startWebcam(target)
    │
    ├── navigator.mediaDevices.getUserMedia({ video: 640×480 })
    ├── stream → <video> element
    └── Exibe modal com vídeo ao vivo
         │
         ▼
captureFrame()
    │
    ├── canvas.drawImage(video)      → copia frame do vídeo
    ├── canvas.toDataURL('image/jpeg') → converte para Base64
    ├── fetch(dataUrl).blob()          → converte para Blob
    ├── new File(blob, 'foto_captura.jpg') → cria arquivo virtual
    ├── processSelectedFile(file, target)  → seta no formulário correto
    └── stopWebcam()                   → libera câmera
```

O `target` pode ser `'form'`, `'verify'`, `'identify'` ou um índice numérico (batch), permitindo que a mesma lógica de webcam atenda todos os formulários.

### 15.5 Validação no frontend

Antes de enviar qualquer formulário:
- Nome não pode ser vazio.
- CPF é validado com o algoritmo completo de dígitos verificadores.
- Foto é obrigatória no cadastro (opcional no update).
- Extensão do arquivo é validada (apenas `.jpg`, `.jpeg`, `.png`).

Erros de validação frontend aparecem como toast de erro **sem** chamada ao backend.

---

## 16. Infraestrutura Docker & Rede

### 16.1 Rede interna

Todos os serviços estão na rede default do Docker Compose. A comunicação interna usa os nomes dos serviços como hostname:

| De | Para | Endereço |
|---|---|---|
| Nginx | Backend | `http://backend:8080` |
| Backend | PostgreSQL | `jdbc:postgresql://db:5432/faceregistry` |

### 16.2 Portas expostas

| Serviço | Porta interna | Porta host | Protocolo |
|---|---|---|---|
| PostgreSQL | 5432 | 5432 | TCP |
| Backend | 8080 | 8080 | HTTP |
| Frontend (Nginx) | 80 | 4200 | HTTP |
| Frontend (Nginx) | 443 | 8000 | HTTPS |

### 16.3 Variáveis de ambiente

| Variável | Serviço | Valor padrão | Função |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Backend | `prod` | Ativa profile de produção |
| `SPRING_DATASOURCE_URL` | Backend | `jdbc:postgresql://db:5432/faceregistry` | URL do banco |
| `SPRING_DATASOURCE_USERNAME` | Backend | `postgres` | Usuário do banco |
| `SPRING_DATASOURCE_PASSWORD` | Backend | `postgres` | Senha do banco |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Backend | `update` | Estratégia de DDL |
| `FACE_RECOGNITION_THRESHOLD` | Backend | `0.60` | Limiar de similaridade |
| `POSTGRES_USER` | PostgreSQL | `postgres` | Usuário admin |
| `POSTGRES_PASSWORD` | PostgreSQL | `postgres` | Senha admin |
| `POSTGRES_DB` | PostgreSQL | `faceregistry` | Nome do banco |

### 16.4 Volume persistente

```yaml
volumes:
  pgdata:
```

Os dados do PostgreSQL são persistidos no volume Docker `pgdata`, sobrevivendo a restarts do container. Apenas `docker compose down -v` remove os dados.

### 16.5 Healthcheck

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U postgres -d faceregistry"]
  interval: 5s
  timeout: 5s
  retries: 5
```

O backend só inicia após 5 tentativas bem-sucedidas de `pg_isready`, garantindo que o banco está pronto para conexões antes da aplicação Spring tentar conectar.
