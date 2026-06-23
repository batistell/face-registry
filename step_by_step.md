# 🗺️ Roteiro Passo a Passo (Step-by-Step) - Face Registry Challenge

Este documento detalha o plano de execução passo a passo para o desenvolvimento do sistema **Face Registry**, que envolve cadastro de biometrias faciais, processamento concorrente e reconhecimento facial (Verificação 1:1 e Identificação 1:n).

---

## 🏗️ Fase 1: Configuração do Ambiente e Banco de Dados

### 1.1 Docker Compose para o Banco de Dados
- Criar um arquivo `docker-compose.yml` na raiz do projeto para subir um container do **PostgreSQL 15+**.
- Configurar credenciais seguras, nome do banco de dados (`faceregistry`) e mapeamento de porta (`5432:5432`).

### 1.2 Estrutura de Diretórios
Organizar o repositório em duas pastas principais:
- `/backend`: Projeto Java Spring Boot.
- `/frontend`: Projeto Angular.

### 1.3 Modelagem do Banco de Dados
Configurar a tabela `users` para armazenar:
- `id` (UUID, Chave Primária)
- `cpf` (VARCHAR(11), Único, Indexado)
- `name` (VARCHAR(255), Obrigatório)
- `photo` (BYTEA, armazenamento binário da foto original)
- `embedding` (VARCHAR(8000), vetor de 512 floats serializado como string separada por `;`)
- `created_at` (TIMESTAMP)

---

## 🧠 Fase 2: Backend - Integração com DJL (Deep Java Library)

### 2.1 Configuração do `pom.xml` (Spring Boot 3.2.4 + Java 21/26)
- Configurar o compilador Java no Maven para compatibilidade com Java 21+.
- Adicionar dependências do Spring Boot: JPA/Hibernate, Web, Validation, PostgreSQL Driver, e Lombok.
- Importar o **DJL BOM (Bill of Materials)** para gerenciar as versões das bibliotecas de IA de forma limpa.
- Adicionar as dependências de IA: `ai.djl:api`, `ai.djl.pytorch:pytorch-engine` e `ai.djl.pytorch:pytorch-native-auto` para baixar os binários adequados do PyTorch em tempo de execução de forma automática.

### 2.2 Serviço de Biometria Facial (`FaceBiometricsService`)
Implementar o fluxo de processamento de imagem em 4 etapas:
1. **Detecção Facial:** Utilizar um modelo pré-treinado do DJL Model Zoo (ex: RetinaFace ou Ultra-Light) para detectar se há rostos na imagem.
   - *Validação:* Caso não encontre nenhum rosto ou encontre mais de um rosto, disparar exceções específicas (`NoFaceDetectedException`, `MultipleFacesDetectedException`).
2. **Alinhamento e Recorte (Crop):** Isolar a área do rosto detectado na imagem original.
3. **Extração de Embedding (Template Facial):** Passar o recorte do rosto pelo modelo **FaceNet/ArcFace** para gerar um vetor numérico (`float[]` de 512 posições).
4. **Cálculo de Similaridade (Cosine Similarity):**
   - Implementar a fórmula da similaridade de cosseno para calcular a proximidade entre os embeddings:
     $$\text{similaridade} = \frac{A \cdot B}{\|A\| \|B\|}$$
   - Configurar o limiar de aceitação (Threshold) de forma dinâmica via `application.yml` (ex: `face.recognition.threshold=0.60`).

---

## ⚡ Fase 3: Backend - APIs REST e Concorrência

### 3.1 Tratamento de Concorrência no Cadastro e Atualização
- Implementar um endpoint para cadastro individual e cadastro em lote (batch): `POST /api/users/batch`.
- Utilizar programação assíncrona com `CompletableFuture` ou `StructuredTaskScope` (Java 21 Virtual Threads) para processar cada cadastro/atualização de foto em uma thread separada.
- Aplicar travas de concorrência ou travas otimistas/pessimistas no banco de dados para evitar que cadastros simultâneos corrompam as imagens do mesmo usuário.
- Garantir transações ACID: o cadastro do lote só será efetivado se todos os registros forem processados com sucesso.

### 3.2 Endpoints da API REST
1. **CRUD de Usuários:**
   - `POST /api/users` (Multipart com foto, nome, CPF) - Salva dados e o embedding pré-calculado.
   - `POST /api/users/batch` - Cadastro concorrente de múltiplos usuários.
   - `PUT /api/users/{cpf}` - Atualiza nome e/ou foto (com recálculo de embedding).
   - `GET /api/users` - Lista todos os usuários cadastrados.
   - `GET /api/users/{cpf}` - Detalhes de um usuário específico.
   - `DELETE /api/users/{cpf}` - Exclui o usuário do banco.
2. **Operações Biométricas:**
   - `POST /api/users/verify` (Input: CPF e nova foto) - Comparação 1:1 (similaridade do embedding da foto enviada com o embedding salvo).
   - `POST /api/users/identify` (Input: nova foto) - Comparação 1:n. Executa buscas concorrentes no banco para comparar o embedding enviado com todos os cadastrados em paralelo utilizando Java Streams. Retorna o usuário com maior score acima do threshold ou indica "Não Identificado".

### 3.3 Tratamento Global de Erros (`GlobalExceptionHandler`)
- Mapear erros para códigos HTTP apropriados:
  - `400 Bad Request` para formatos inválidos, arquivos corrompidos, ausência de rosto ou múltiplos rostos.
  - `404 Not Found` para usuário inexistente na verificação 1:1.
  - `409 Conflict` para CPFs duplicados.

---

## 🎨 Fase 4: Frontend - Interface do Usuário (Angular 17+)

### 4.1 Estrutura do App Angular
- Criar componentes modulares e reutilizáveis.
- Implementar chamadas HTTP usando `HttpClient` integradas à API REST.

### 4.2 Telas do CRUD
- **Dashboard / Listagem de Usuários:** Exibir cartões ou tabela com nome, CPF e um avatar com a foto cadastrada. Botão de deletar e botão de visualizar detalhes.
- **Formulário de Cadastro/Edição:** Inputs validados para CPF e Nome, campo para upload/captura de foto com preview em tempo real.
- **Upload em Lote (Batch):** Interface para selecionar múltiplos usuários/arquivos simultaneamente para testar o processamento concorrente do backend.

### 4.3 Telas de Biometria Facial
- **Verificação (1:1):** Campo para inserir o CPF e enviar uma foto (via arquivo ou webcam). Exibir resultado visual claro: "Compatível" (verde) ou "Incompatível" (vermelho), junto com o score de similaridade percentual obtido.
- **Identificação (1:n):** Envio de foto (arquivo ou webcam). O sistema processa e exibe o cartão do usuário identificado (Nome, CPF, Foto) e a similaridade, ou uma mensagem clara de "Usuário Não Identificado".

### 4.4 Estática e UX (Design Premium)
- Layout moderno com cores harmônicas (paleta HSL / Dark Mode opcional).
- Micro-animações ao carregar fotos ou realizar transições de tela.
- Estados de carregamento (Skeleton loaders / Spinners) enquanto o backend faz a inferência pesada de IA.

---

## 🧪 Fase 5: Validação, Testes e Dockerização

### 5.1 Massa de Testes e Postman
- Criar uma coleção do Postman contendo todas as requisições (CRUD, Batch, Verify, Identify).
- Incluir fotos de teste (mesma pessoa com expressões diferentes, pessoas diferentes, imagens sem rostos e imagens com múltiplos rostos).

### 5.2 Testes Automatizados e Concorrência
- Escrever testes de integração no Spring Boot (`@SpringBootTest`).
- Testar concorrência simulando múltiplas requisições de cadastro concorrentes para garantir que o pool de threads e a persistência funcionem sem condições de corrida (Race Conditions).

### 5.3 Dockerização Completa (Opcional & Recomendado)
- Configurar um `Dockerfile` multi-stage para compilar e empacotar o backend Java.
- Configurar um `Dockerfile` para gerar o build de produção do Angular e servir via Nginx.
- Integrar tudo no `docker-compose.yml` contendo:
  - Banco de Dados (`postgres`)
  - API REST (`backend`)
  - Web App (`frontend`)
