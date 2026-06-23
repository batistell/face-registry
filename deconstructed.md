# 🧩 Deconstructed — Decisões de Projeto do Face Registry

Este documento detalha **cada decisão técnica e arquitetural** tomada durante o desenvolvimento do sistema **Face Registry**, explicando o **porquê** de cada escolha, as **alternativas consideradas** e os **trade-offs** aceitos.

---

## 1. Linguagem & Framework — Java 21 + Spring Boot 3.2.4

### Decisão
Utilizar **Java 21 LTS** com **Spring Boot 3.2.4** como base do back-end.

### Por quê?
- **Java 21** é a versão LTS mais recente com suporte a **Virtual Threads** (Project Loom), que são fundamentais para o processamento concorrente de imagens no endpoint de batch.
- **Spring Boot 3.2.4** é a versão estável do ecossistema Spring que suporta nativamente Jakarta EE 10 e Java 21, oferecendo auto-configuração de JPA, Web MVC, validação e gerenciamento de transações sem boilerplate.
- O framework é exigido pelo desafio.

### Alternativas descartadas
| Alternativa | Motivo da rejeição |
|---|---|
| Java 17 | Não oferece Virtual Threads nativamente |
| Quarkus / Micronaut | Não solicitado pelo desafio; ecossistema menor para DJL |
| Spring WebFlux (reativo) | Complexidade desnecessária; o modelo bloqueante com Virtual Threads atende a demanda de concorrência do desafio |

---

## 2. Gerenciamento de Dependências — Apache Maven

### Decisão
Utilizar o **Maven** com `pom.xml` centralizado.

### Por quê?
- O desafio cita explicitamente que é desejável o uso do Maven.
- O BOM (Bill of Materials) do DJL (`ai.djl:bom`) é gerenciado via `<dependencyManagement>`, garantindo compatibilidade entre as versões de `api`, `pytorch-engine`, `pytorch-model-zoo` e `mxnet-engine`.
- O plugin `spring-boot-maven-plugin` gera o `.jar` executável (fat JAR) automaticamente.

---

## 3. Banco de Dados — PostgreSQL 15 (Produção) + H2 (Testes)

### Decisão
PostgreSQL 15 para produção (via Docker) e H2 em memória para testes locais (profile `local`).

### Por quê?
- **PostgreSQL** é exigido pelo desafio. A versão 15 é estável e amplamente suportada.
- **H2** permite executar testes unitários e de integração **sem dependência de Docker ou infraestrutura externa**. Os testes rodam instantaneamente em memória com `@ActiveProfiles("local")`.
- A separação é feita via **Spring Profiles** no `application.yml`, com configurações distintas para cada ambiente.

### Trade-off aceito
O H2 não suporta todos os tipos nativos do PostgreSQL (como `REAL[]`), o que motivou a decisão de armazenar os embeddings como `VARCHAR` serializado em vez de usar um tipo de array nativo. Essa escolha sacrifica performance marginal em favor de **portabilidade total entre os dois bancos** sem necessidade de queries nativas ou conversores específicos por dialeto.

---

## 4. Armazenamento de Embeddings — VARCHAR serializado

### Decisão
Armazenar o vetor de 512 floats como uma **String delimitada por ponto-e-vírgula** (`float;float;...`) em uma coluna `VARCHAR(8000)`.

### Por quê?
- **Compatibilidade:** A mesma coluna funciona identicamente no PostgreSQL e no H2, sem adapters, converters ou SQL nativo.
- **Simplicidade:** A serialização/desserialização é feita por métodos utilitários diretamente na entidade JPA (`getEmbedding()` / `setEmbedding()`), sem dependência de bibliotecas externas.
- **Tamanho:** 512 floats com 6 dígitos de precisão + separadores ocupam ~3.5KB, bem dentro do limite do VARCHAR.

### Alternativas descartadas
| Alternativa | Motivo da rejeição |
|---|---|
| `REAL[]` nativo do PostgreSQL | Não portável para H2; exigiria `@JdbcTypeCode(SqlTypes.ARRAY)` ou converter específico |
| `BLOB` / `BYTEA` serializado | Legibilidade zero; debugging impossível; sem ganho real de performance |
| Tabela separada (normalização) | Overhead de JOIN sem benefício; embedding é 1:1 com o usuário |
| pgvector extension | Dependência externa de extensão; complexidade desnecessária para busca linear |

---

## 5. Modelo de Entidade JPA — Classe User

### Decisão
Uma única entidade `User` com os campos `id` (UUID), `cpf`, `name`, `photo` (byte[]), `embeddingString` e `createdAt`.

### Escolhas específicas:

#### 5.1 UUID como chave primária
- **Por quê?** IDs sequenciais expõem informações sobre o volume de dados e são previsíveis. UUIDs são universalmente únicos e não revelam sequência.
- **Geração:** `GenerationType.AUTO` delega ao provider JPA a melhor estratégia por banco.

#### 5.2 CPF como índice único
- O `cpf` tem `@Column(unique = true)` com índice explícito `idx_users_cpf`, garantindo que a constraint de unicidade é aplicada **pelo banco**, independente da validação de negócio.
- CPF é armazenado **apenas como 11 dígitos numéricos** (sem formatação), normalizado no service layer via `cleanAndValidateCpf()`.

#### 5.3 Foto como byte[] (BYTEA)
- **Decisão:** Armazenar a foto diretamente no banco como coluna binária.
- **Por quê?** Simplifica a arquitetura (sem S3, sem filesystem). O desafio não exige escala massiva; fotos de rosto comprimidas raramente excedem 500KB.
- **Trade-off:** Aumenta o tamanho da tabela, mas elimina a complexidade de gerenciar referências externas.

#### 5.4 `@PrePersist` para `createdAt`
- O timestamp de criação é definido automaticamente pela JPA antes do INSERT, sem dependência de trigger do banco.

---

## 6. Validação de CPF — Algoritmo com dígitos verificadores

### Decisão
Implementar a validação completa do CPF (módulo 11) tanto no **backend** (`UserService.cleanAndValidateCpf()`) quanto no **frontend** (`AppComponent.isValidCpf()`).

### Por quê?
- **Backend:** É a camada de confiança. Mesmo que o frontend seja bypassado (ex: via Postman), o backend rejeita CPFs inválidos com `400 Bad Request`.
- **Frontend:** Validação antecipada evita chamadas desnecessárias à API e melhora a experiência do usuário com feedback imediato.

### Validações aplicadas:
1. Remoção de caracteres não numéricos (`replaceAll("\\D", "")`).
2. Verificação de 11 dígitos exatos.
3. Rejeição de CPFs com todos os dígitos iguais (ex: `11111111111`).
4. Cálculo dos dois dígitos verificadores (módulo 11).

---

## 7. Motor de IA — DJL com PyTorch (RetinaFace + FaceNet)

### Decisão
Utilizar a **Deep Java Library (DJL)** da AWS com motor **PyTorch** para detecção e extração de features faciais, rodando **dentro da mesma JVM**.

### Por quê?
- **Eliminação de serviço externo:** Uma API Python (Flask/FastAPI) adicionaria latência de rede, complexidade de deploy (mais um container), e pontos de falha.
- **JNI nativo:** O DJL executa inferência via bibliotecas C++ do PyTorch através de JNI, com performance próxima a código Python puro.
- **Sugestão do desafio:** O DJL é listado explicitamente nos links úteis do challenge.md.

### Pipeline de 4 etapas:
```
Imagem → (1) Detectar Rosto → (2) Recortar/Alinhar → (3) Extrair Embedding 512-D → (4) Comparar por Cosseno
```

### Modelos escolhidos:

#### 7.1 RetinaFace (Detecção)
- **Modelo:** `retinaface.zip` do repositório DJL (PyTorch).
- **Por quê?** Alta precisão em detecção de rostos frontais, perfil e parcialmente oclusos. Retorna bounding boxes e 5 landmarks faciais.
- **Translator customizado:** `FaceDetectionTranslator` implementa o pré-processamento (HWC→CHW, BGR, subtração de média) e pós-processamento (decodificação de anchor boxes, NMS) manualmente, pois o modelo DJL de detecção não possui translator padrão.
- **Hiperparâmetros:**
  - `confThresh = 0.85`: Alta confiança para evitar falsos positivos.
  - `nmsThresh = 0.45`: Non-Maximum Suppression padrão para eliminar boxes sobrepostos.
  - `topK = 5000`: Limite de candidatos antes do NMS.

#### 7.2 FaceNet/ArcFace (Extração de Features)
- **Modelo:** `face_feature.zip` do repositório DJL (PyTorch).
- **Por quê?** Gera vetores de 512 dimensões L2-normalizados, padrão da indústria para reconhecimento facial.
- **Translator customizado:** `FaceFeatureTranslator` redimensiona para 112×112, normaliza pixels para [0,1], subtrai média (0.5) e divide por std (0.5019608), compatível com MobileFaceNet/ArcFace.

---

## 8. Mock Mode — Simulação determinística para testes

### Decisão
Implementar um **modo mock** ativável por configuração (`face.biometrics.mock=true`) que gera embeddings determinísticos baseados no hash SHA-256 dos bytes da imagem.

### Por quê?
- **Testes sem GPU/modelo:** Permite executar todos os testes unitários e de integração do CRUD sem precisar baixar ~200MB de modelos PyTorch.
- **Determinismo:** O mesmo arquivo de imagem sempre gera o mesmo embedding (baseado no hash), permitindo assertions confiáveis em testes (`similarity == 1.0` para mesma imagem).
- **Simulação de erros:** Nomes de arquivo contendo `noface`, `multiple` ou `grupo` disparam exceções simuladas, permitindo testar os fluxos de erro sem imagens reais.

### L2-Normalização do mock:
Mesmo os embeddings mock são L2-normalizados, garantindo que a similaridade de cosseno se comporta na mesma faixa [0, 1] que os embeddings reais.

---

## 9. Similaridade de Cosseno — Métrica de comparação

### Decisão
Usar **similaridade de cosseno** como métrica de comparação entre embeddings faciais.

### Por quê?
- É a métrica padrão para vetores L2-normalizados produzidos por modelos ArcFace/FaceNet.
- Para vetores normalizados, `cos(A, B) = dot(A, B)`, mas mantivemos a fórmula completa (`dot / (norm_a * norm_b)`) para robustez contra vetores potencialmente não-normalizados.
- O resultado é um valor entre -1.0 e 1.0, onde 1.0 significa rostos idênticos e 0.0 significa nenhuma semelhança.

### Limiar (Threshold) = 0.60
- **Configurável** via `application.yml` (`face.recognition.threshold`) e variável de ambiente (`FACE_RECOGNITION_THRESHOLD`).
- **0.60** foi escolhido empiricamente como equilíbrio entre taxa de falsos positivos e falsos negativos nos testes com fotos de celebridades.
- O limiar é exposto nas respostas de verificação e identificação para transparência.

---

## 10. Controle de Concorrência — Locks e Transações

### Decisão
Combinar **lock pessimista** (banco) com **transações Spring** e **Virtual Threads** (Java 21).

### 10.1 Update com `PESSIMISTIC_WRITE`
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT u FROM User u WHERE u.cpf = :cpf")
Optional<User> findByCpfWithLock(@Param("cpf") String cpf);
```
- **Por quê?** Evita que duas atualizações concorrentes da foto do mesmo usuário causem inconsistência. A primeira thread adquire o lock; as demais esperam.
- **Alternativa descartada:** Optimistic locking (`@Version`) — exigiria tratamento de `OptimisticLockException` e retry, aumentando complexidade.

### 10.2 Batch com Virtual Threads
- O processamento de imagens (CPU-bound) é paralelizado via `CompletableFuture.supplyAsync()` com `Executors.newVirtualThreadPerTaskExecutor()`.
- **Virtual Threads** foram escolhidas sobre thread pools fixas porque escalam automaticamente sem configuração de pool size.
- A persistência final é feita com `saveAll()` dentro de `@Transactional`, garantindo atomicidade (tudo ou nada).

### 10.3 Prevenção de duplicata facial
- Antes de cada cadastro/atualização, o sistema compara o novo embedding contra **todos** os embeddings existentes via `parallelStream()`.
- Se alguma similaridade ultrapassar o threshold, o cadastro é bloqueado com `409 Conflict` e a mensagem identifica o usuário duplicado.
- No batch, a verificação é feita em duas fases: (1) contra o banco e (2) **internamente entre os itens do lote**.

---

## 11. Tratamento de Erros — Exceções customizadas + GlobalExceptionHandler

### Decisão
Criar exceções semânticas específicas para cada tipo de erro, mapeadas para códigos HTTP apropriados via `@RestControllerAdvice`.

### Mapeamento:
| Exceção | HTTP | Quando |
|---|:---:|---|
| `InvalidCpfException` | 400 | CPF inválido (formato ou dígitos verificadores) |
| `InvalidImageException` | 400 | Formato de arquivo inválido, imagem corrompida, rosto muito pequeno ou cortado |
| `NoFaceDetectedException` | 400 | Nenhum rosto detectado na imagem |
| `MultipleFacesDetectedException` | 400 | Mais de 1 rosto na imagem |
| `UserNotFoundException` | 404 | CPF não encontrado no banco |
| `DuplicateCpfException` | 409 | CPF já cadastrado |
| `DuplicateFaceException` | 409 | Rosto já cadastrado (outra pessoa) |
| `BiometricProcessingException` | 503 | Falha no motor de IA (DJL/PyTorch) |
| `Exception` (genérica) | 500 | Qualquer erro não tratado |

### Por quê customizar?
- A mensagem do erro é exibida diretamente no frontend. Mensagens genéricas do Spring (ex: "Internal Server Error") não ajudam o usuário.
- O payload padrão (`ErrorPayload`) inclui `status`, `error`, `message` e `timestamp`, permitindo debugging fácil via Postman.

---

## 12. API REST — Design dos Endpoints

### Decisão
API RESTful no padrão `/api/users` com operações CRUD + biometria.

| Método | Endpoint | Responsabilidade |
|---|---|---|
| `POST` | `/api/users` | Cadastro individual |
| `PUT` | `/api/users/{cpf}` | Atualização (nome e/ou foto) |
| `DELETE` | `/api/users/{cpf}` | Exclusão |
| `GET` | `/api/users/{cpf}` | Detalhe de um usuário |
| `GET` | `/api/users` | Listagem de todos |
| `POST` | `/api/users/batch` | Cadastro em lote concorrente |
| `POST` | `/api/users/verify` | Verificação facial 1:1 |
| `POST` | `/api/users/identify` | Identificação facial 1:n |
| `GET` | `/api/users/duplicates` | Auditoria de duplicados |

### Escolhas específicas:
- **CPF na URL (PUT/DELETE/GET):** O CPF é o identificador de negócio do usuário. Usar UUID na URL seria menos intuitivo para o avaliador.
- **`consumes = MULTIPART_FORM_DATA`:** Todos os endpoints que recebem foto usam multipart, que é o padrão para upload de arquivos em HTTP.
- **CORS `*`:** Aberto para qualquer origem, facilitando o desenvolvimento e avaliação. Em produção real seria restrito.
- **Swagger/OpenAPI:** Integrado via SpringDoc (`springdoc-openapi-starter-webmvc-ui`), acessível em `/swagger-ui.html`.

---

## 13. Frontend — Angular 17 Standalone + Single Component

### Decisão
Toda a interface Angular reside em um **único componente** (`AppComponent`) com um arquivo `.ts`, `.html` e `.css`.

### Por quê?
- O desafio declara explicitamente: *"Não é necessário dedicar muito tempo para o design da interface"*.
- Um único componente permite ao avaliador ler **todo o código frontend em 3 arquivos** sem navegar entre dezenas de módulos.
- O padrão **standalone** do Angular 17 elimina a necessidade de `NgModule`, simplificando a configuração.

### Navegação por abas:
Em vez de rotas Angular (`RouterModule`), a navegação é feita por uma variável `activeTab` que alterna entre 5 seções via `*ngIf`:
1. **Listagem** — Grid de cards com busca e filtro
2. **Cadastro/Edição** — Formulário com upload ou câmera
3. **Batch** — Tabela dinâmica para envio em lote
4. **Verificação 1:1** — CPF + foto → resultado
5. **Identificação 1:n** — Foto → busca no banco

### Webcam nativa:
Utiliza `navigator.mediaDevices.getUserMedia()` da API de mídia do navegador, sem dependência de bibliotecas externas. O frame é capturado via `<canvas>` e convertido para `File` para envio como multipart.

---

## 14. Detecção automática de URL da API

### Decisão
```typescript
private apiUrl = window.location.hostname === 'localhost' && window.location.port === '4200'
  ? 'http://localhost:8080/api/users'
  : 'api/users';
```

### Por quê?
- **Desenvolvimento local (`ng serve`):** O Angular roda na porta 4200 e o backend na 8080 — são origens diferentes, então usa URL absoluta.
- **Produção (Docker/Nginx):** O Nginx faz proxy reverso de `/api/*` para o backend. A URL relativa `api/users` funciona porque a mesma origem serve tanto o frontend quanto a API.
- Isso elimina a necessidade de variáveis de ambiente no build do frontend ou de configuração de proxy Angular.

---

## 15. Infraestrutura Docker — Multi-stage builds

### Decisão
Utilizar **multi-stage Dockerfiles** para ambos os serviços e orquestrar via **Docker Compose**.

### Backend Dockerfile:
```
Stage 1 (builder): maven:3.9.6-eclipse-temurin-21 → compila o JAR
Stage 2 (runtime): eclipse-temurin:21-jre → roda apenas o JAR
```
- **Por quê multi-stage?** A imagem do Maven tem ~800MB; a imagem JRE final tem ~250MB. O build é descartado.

### Frontend Dockerfile:
```
Stage 1 (builder): node:20-alpine → npm ci + ng build
Stage 2 (runtime): nginx:alpine → serve os arquivos estáticos + proxy reverso
```
- O Nginx gera um **certificado SSL self-signed** na build para permitir HTTPS local (necessário para `getUserMedia` no Chrome).
- Os artefatos Angular são copiados para **dois paths** (`/` e `/face-registry/`) para compatibilidade entre acesso local e produção com subpath.

### Docker Compose:
- **Healthcheck no PostgreSQL:** O backend só inicia após o `pg_isready` confirmar que o banco está pronto (`condition: service_healthy`).
- **Imagens `.tar` exportadas:** Permitem que o avaliador rode o sistema com `docker load` + `docker compose up` **sem compilar nada**.

---

## 16. Nginx — Reverse Proxy + SSL + SPA Routing

### Decisão
Configurar o Nginx com dupla responsabilidade: servidor de arquivos estáticos + reverse proxy para a API.

### Rotas configuradas:
| Location | Função |
|---|---|
| `/face-registry/` | Serve o Angular com `try_files` → SPA routing |
| `/` | Fallback local para desenvolvimento |
| `/face-registry/api/` | Proxy para `http://backend:8080/api/` |
| `/api/` | Proxy local para desenvolvimento |

### SSL:
- Certificado self-signed gerado automaticamente no Dockerfile com `openssl req -x509`.
- Necessário porque a API `getUserMedia()` (webcam) **só funciona em HTTPS** ou `localhost` nos navegadores modernos.

---

## 17. Testes Automatizados — 3 camadas

### Decisão
Três classes de teste com escopos distintos:

| Classe | Escopo | Profile | Mock? |
|---|---|---|---|
| `UserControllerTest` | Endpoints HTTP (MockMvc) | `local` | Sim |
| `UserConcurrencyTest` | Race conditions (threads) | `local` | Sim |
| `BiometricsVerificationTest` | IA real com fotos | `local` + `mock=false` | Não |

### Por quê separar?
- **Testes rápidos (mock):** Validam toda a lógica de negócio (CRUD, validações, duplicatas, concorrência) em segundos, sem baixar modelos.
- **Testes de biometria (real):** Validam que os modelos DJL produzem embeddings coerentes para fotos de pessoas reais. São lentos (download de modelos na primeira execução) mas provam que a integração funciona.

---

## 18. Prevenção de Duplicados Biométricos — Decisão de design

### Decisão
Bloquear cadastro se a face já existir no banco, mesmo com CPF diferente.

### Por quê?
- Embora o desafio não exija explicitamente, é uma **medida de segurança biométrica** que demonstra maturidade na implementação.
- Impede que a mesma pessoa se cadastre com CPFs diferentes (fraude).
- A mensagem de erro é rica: informa o nome e CPF do usuário já existente com aquele rosto.

### Impacto no batch:
- No envio em lote, a duplicidade é verificada em **duas dimensões**: contra o banco e internamente entre os itens do lote.
- Se qualquer duplicata for detectada, **o lote inteiro é rejeitado** (atomicidade transacional).
