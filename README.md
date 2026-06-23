# 👤 Face Registry - Sistema de Cadastro e Reconhecimento Facial

Este repositório contém a implementação completa do sistema **Face Registry**, um desafio para a vaga de **Desenvolvedor Full Stack Java e Angular**. O sistema gerencia o cadastro de usuários e suas fotos, realizando a **Verificação Facial (1:1)**, a **Identificação Facial (1:n)** de forma concorrente e a **Prevenção de Cadastros Duplicados** com alta performance e precisão biométrica.

O projeto está totalmente funcional em ambiente de produção sob o domínio público [https://jarvis.xyz.br/face-registry/](https://jarvis.xyz.br/face-registry/).

---

## 🏗️ Arquitetura do Sistema

O ecossistema foi projetado de forma modular e conteinerizada, garantindo independência de serviços e facilidade de deploy:

1. **Front-end (Angular 17+):**
   - Roda em um servidor **Nginx** configurado com **SSL/HTTPS**.
   - Interface premium e responsiva (estética glassmorphic, micro-animações, loaders e feedbacks em tempo real).
   - Suporte a captura via webcam nativa da API de mídia do navegador ou upload de arquivos locais.
   - Roteamento configurado sob o subcaminho base `/face-registry/`.

2. **Back-end (Spring Boot 3.2.4 + Java 21/26):**
   - Engine transacional REST API responsável pela lógica de negócios, controle de concorrência, transações ACID e orquestração de IA.
   - Processamento assíncrono e concorrente utilizando **Virtual Threads (Java 21)** e pool de threads otimizados para operações pesadas de I/O e processamento de imagem em lote.
   - Executa inferência nativa de Deep Learning via interface JNI.

3. **Banco de Dados (PostgreSQL 15+):**
   - Armazena dados cadastrais clássicos (Nome, CPF formatado), a foto original em formato binário (`BYTEA`) e os **Templates Faciais (Embeddings)** pré-calculados de 512 dimensões serializados como `VARCHAR` (formato `float;float;...`).
   - Os embeddings são desserializados em memória pela camada JPA para cálculo de similaridade, garantindo compatibilidade total entre PostgreSQL (produção) e H2 (testes).

```mermaid
graph TD
    A[Usuário / Navegador] -->|HTTPS / WSS| B[Nginx Reverse Proxy / SSL Port 8000]
    B -->|Base: /face-registry/| C[Frontend Angular 17 Container]
    B -->|API: /api/*| D[Backend Spring Boot Container]
    D -->|JPA / Hibernate ORM| E[PostgreSQL Container]
    D -->|JNI C++ Inference| F[DJL Engine - PyTorch native]
    F -->|RetinaFace + FaceNet| D
```

---

## 🛠️ Stack Tecnológica & Escolhas de Design

### Back-end
- **Linguagem & Framework:** **Java 21** e **Spring Boot 3.2.4** com Spring Data JPA, Hibernate e Apache Maven.
- **Deep Java Library (DJL) com Motor PyTorch:**
  - Escolha para IA: Em vez de expor uma API em Python externa (Flask/FastAPI) que adicionaria latência de rede e complexidade de infraestrutura, utilizamos o **DJL** da AWS com motor nativo do **PyTorch** via JNI (Java Native Interface). Isso possibilita que a inferência pesada ocorra dentro da mesma JVM de forma otimizada usando bibliotecas nativas C++.
  - **Detecção Facial (RetinaFace):** Valida se há **exatamente 1 rosto** na imagem enviada. Imagens sem rostos ou com múltiplos rostos são rejeitadas de imediato com `400 Bad Request`.
  - **Extração de Vetores (FaceNet/ArcFace):** Normaliza o rosto alinhado e gera um vetor numérico de **512 floats** (`512-D`) que resume as características únicas do rosto.
- **Concorrência Otimizada:**
  - O endpoint de upload em lote (`POST /api/users/batch`) distribui o processamento de imagens e o cálculo de embeddings concorrentemente através de threads virtuais (Java 21 Virtual Threads) para maximizar o uso de CPU/GPU.

### Front-end
- **Framework:** **Angular 17+** com padrão standalone, HttpClient e RxJS para gerenciar o fluxo reativo de dados.
- **Estética & Visual:**
  - Utilização de **CSS Vanilla estruturado** para controle de layout com componentes glassmorphic.
  - Paleta HSL moderna com tons escuros elegantes (Dark theme premium), efeitos de hover ativos e transições suaves.
  - Adicionado um ícone de "Checkmark" dourado (<i class="fa-solid fa-check"></i>) ao lado do badge **512-D** nos cartões dos usuários para atestar visualmente que a assinatura biométrica facial foi gerada com sucesso.

### Banco de Dados
- **PostgreSQL 15+:** Os embeddings de 512 dimensões são armazenados como `VARCHAR` serializado (separador `;`), garantindo portabilidade entre PostgreSQL (produção) e H2 (testes unitários). A desserialização `String → float[]` ocorre via métodos utilitários da entidade JPA, e a busca 1:n é resolvida em memória pela aplicação com similaridade de cosseno em paralelo (`parallelStream`).

---

## 🔒 Regras de Segurança Biométrica & Prevenção de Duplicados

Uma das escolhas cruciais no design do sistema foi a **Prevenção de Duplicados Biométricos**:

1. **Cálculo de Similaridade por Cosseno:**
   A similaridade entre a face de entrada ($A$) e a face cadastrada ($B$) é dada pela fórmula matemática:
   $$\text{similaridade} = \frac{A \cdot B}{\|A\| \|B\|}$$
   O limiar de aceitação (threshold) padrão é configurado em **0.60** (Customizável via `application.yml`).

2. **Prevenção Concorrente no Cadastro e Edição:**
   - **Cadastro Individual:** Ao tentar cadastrar uma foto, a API calcula o embedding e realiza um rastreamento completo sobre a tabela de usuários. Se a similaridade for superior ao threshold, o cadastro é bloqueado com `409 Conflict`, exibindo uma mensagem personalizada informando o nome e o CPF do usuário já registrado com aquele rosto.
   - **Edição de Usuário:** O mesmo rastreamento é aplicado ao atualizar uma foto, permitindo que a pessoa permaneça com a própria face atualizada (excluindo seu próprio CPF do rastreio), mas impedindo-a de usar a foto de outro usuário cadastrado.
   - **Upload em Lote:** O fluxo do batch valida de forma inteligente a duplicidade tanto contra o banco de dados quanto internamente entre as fotos do próprio lote enviado, bloqueando o lote inteiro de forma atômica se houver faces repetidas.

3. **API de Auditoria de Duplicados:**
   - Foi exposto um endpoint de auditoria `GET /api/users/duplicates` que executa uma verificação cruzada $O(N^2)$ em toda a base de dados para listar e exportar possíveis pares duplicados que possuam similaridade biométrica acima do limite tolerado.

---

## 📂 Diretório de Imagens para Testes 1:1

Para facilitar a verificação e homologação da biometria 1:1 na aba **"Verificar Biometria"** da interface, a pasta [examples/to-compare](file:///o:/JavaProjects/face-registry/examples/to-compare/) foi populada com fotos alternativas (retratos e poses diferentes obtidos da Wikimedia Commons sob licenças livres) das celebridades cadastradas no banco de dados.

Os arquivos estão nomeados contendo o nome e o CPF cadastrado da respectiva celebridade, facilitando a cópia e colagem na interface:
- **Barack Obama (CPF 64657075942)**: `obama_alt2.jpg`, `obama2.jpg`, etc.
- **Joe Biden (CPF 21603180001)**: `biden_alt2.jpg`, `biden1.jpg`.
- **Elon Musk (CPF 92230775596)**: `musk_alt2.jpg`, `musk2.jpg`.
- **Lionel Messi (CPF 74394356644)**: `messi_alt2.jpg`, `messi2.jpg`.
- **Kana (CPF 62079744844)**: `kana_alt2.jpg`, `kana2.jpg`, etc.
- **Lena (CPF 83992409821)**: `lena_alt2.png`, `lena.jpg`.

---

## 🚀 Como Executar o Projeto Localmente

### Requisitos Prévios
- Docker e Docker Compose instalados.

### Opção A: Executar usando os arquivos pré-compilados (`.tar`) [Recomendado]
Se você possui os arquivos `.tar` exportados na raiz do projeto, você pode importar as imagens diretamente para o seu daemon do Docker local (evitando o tempo de compilação do Angular/Maven):

1. **Importar as imagens salvadas**:
   ```bash
   docker load -i face-registry-backend.tar
   docker load -i face-registry-frontend.tar
   ```

2. **Iniciar a stack do Docker Compose**:
   ```bash
   docker compose up -d
   ```

---

### Opção B: Compilar e Executar a partir do Código Fonte
Caso queira compilar a aplicação localmente e gerar novas imagens Docker a partir dos fontes:

1. **Buildar e rodar o ambiente**:
   ```bash
   docker compose up --build -d
   ```

---

### 🌐 Acesso à Aplicação
Após a inicialização (seja via Opção A ou B):
- **Painel Frontend (Nginx/HTTPS):** Disponível em [https://localhost:8000/face-registry/](https://localhost:8000/face-registry/)
- **Documentação REST API (Swagger UI):** Disponível em [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **API REST Backend:** Rodando na porta `8080` (ex: [http://localhost:8080/api/users](http://localhost:8080/api/users)).