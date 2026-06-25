# 📞 Manual da REST API - Face Registry

A API REST do **Face Registry** é construída em Spring Boot. Ela permite gerenciar os cadastros de usuários e realizar buscas biométricas de forma transacional e concorrente.

Todos os endpoints que recebem fotos aceitam envios do tipo `multipart/form-data` e utilizam o prefixo padrão `/api/users`.

---

## Estruturas de Dados Comuns (DTOs em JSON)

### 1. `UserResponse`
Retornado em consultas e após cadastros/edições. A foto é trafegada como uma string codificada em Base64.
```json
{
  "id": "e30b62e4-e0c1-4b10-85f0-1d8be8d5dcf9",
  "cpf": "12345678909",
  "name": "João da Silva",
  "photoBase64": "/9j/4AAQSkZJRgABAQE...",
  "createdAt": "2026-06-25T14:30:00"
}
```

### 2. `VerificationResponse` (1:1)
Retornado ao testar se uma foto pertence a um usuário específico.
```json
{
  "match": true,
  "similarity": 0.841592,
  "threshold": 0.60
}
```

### 3. `IdentificationResponse` (1:N)
Retornado ao submeter uma imagem para identificar a pessoa na base.
```json
{
  "identified": true,
  "cpf": "12345678909",
  "name": "João da Silva",
  "photoBase64": "/9j/4AAQSkZJRgABAQE...",
  "similarity": 0.841592,
  "threshold": 0.60
}
```

### 4. `DuplicatePairResponse`
Retornado na listagem de auditoria de duplicidades biográficas no banco.
```json
{
  "user1": {
    "id": "e30b62e4-e0c1-4b10-85f0-1d8be8d5dcf9",
    "cpf": "12345678909",
    "name": "João da Silva",
    "photoBase64": null,
    "createdAt": "2026-06-25T14:30:00"
  },
  "user2": {
    "id": "f58c73d5-e2d2-4c22-96a0-2f9cf9e6eef0",
    "cpf": "98765432100",
    "name": "João Fake",
    "photoBase64": null,
    "createdAt": "2026-06-25T14:40:00"
  },
  "similarity": 0.985412
}
```

---

## Detalhes dos Endpoints

### 1. Listar Usuários
Retorna a listagem de usuários cadastrados (limitado aos 100 primeiros registros por questões de OOM em grandes volumes).
- **Método:** `GET`
- **Caminho:** `/api/users`
- **Respostas:**
  - `200 OK`: Lista de `UserResponse`.

### 2. Buscar por CPF
- **Método:** `GET`
- **Caminho:** `/api/users/{cpf}`
- **Respostas:**
  - `200 OK`: Retorna `UserResponse`.
  - `404 Not Found`: CPF não cadastrado.

### 3. Cadastro Individual (Com Validação de Duplicados)
Cadastra um usuário calculando o embedding biométrico e validando se o CPF ou a face já existem na base.
- **Método:** `POST`
- **Caminho:** `/api/users`
- **Content-Type:** `multipart/form-data`
- **Parâmetros:**
  - `cpf` (String): CPF contendo exatamente 11 dígitos numéricos.
  - `name` (String): Nome completo do usuário.
  - `photo` (Arquivo): Arquivo de imagem (JPG, JPEG, PNG, etc).
- **Respostas:**
  - `201 Created`: Cadastro realizado. Retorna `UserResponse`.
  - `400 Bad Request`: CPF inválido, imagem corrompida, nenhum ou múltiplos rostos detectados.
  - `409 Conflict`: CPF já cadastrado OU face biométrica correspondente a outro usuário já cadastrado.

### 4. Atualizar Cadastro
Atualiza dados cadastrais (nome) ou a foto associada. Utiliza Lock Pessimista de escrita no banco para evitar condições de corrida em edições simultâneas do mesmo registro.
- **Método:** `PUT`
- **Caminho:** `/api/users/{cpf}`
- **Content-Type:** `multipart/form-data`
- **Parâmetros:**
  - `name` (String, Opcional): Novo nome.
  - `photo` (Arquivo, Opcional): Nova foto.
- **Respostas:**
  - `200 OK`: Retorna `UserResponse` atualizado.
  - `404 Not Found`: Usuário não encontrado.
  - `409 Conflict`: A nova foto pertence ao rosto de outro usuário cadastrado.

### 5. Excluir Usuário
- **Método:** `DELETE`
- **Caminho:** `/api/users/{cpf}`
- **Respostas:**
  - `204 No Content`: Exclusão concluída e cache invalidado com sucesso.
  - `404 Not Found`: Usuário não cadastrado.

### 6. Cadastro Concorrente em Lote (Batch Upload)
Processa múltiplos cadastros simultaneamente utilizando Threads Virtuais do Java 21 para inferência de IA.
- **Método:** `POST`
- **Caminho:** `/api/users/batch`
- **Content-Type:** `multipart/form-data`
- **Parâmetros:**
  - `cpfs` (List<String>): Lista de CPFs.
  - `names` (List<String>): Lista de Nomes.
  - `photos` (List<MultipartFile>): Lista de arquivos de imagem na mesma ordem correspondente.
- **Garantia Atômica:** A operação ocorre sob uma transação de banco de dados única. Se houver falha de biometria ou validação em um único item do lote (ex: foto sem rosto ou CPF duplicado), a transação inteira sofre rollback de forma a manter a integridade da base.
- **Respostas:**
  - `201 Created`: Todos os cadastros realizados. Retorna lista de `UserResponse`.
  - `400 Bad Request / 409 Conflict`: Se houver CPFs ou biometrias duplicadas (tanto no banco quanto entre os elementos do próprio lote enviado).

### 7. Verificação Facial (1:1)
Compara uma foto enviada com o embedding em cache associado a um CPF específico.
- **Método:** `POST`
- **Caminho:** `/api/users/verify`
- **Content-Type:** `multipart/form-data`
- Parâmetros:
  - `cpf` (String): CPF do usuário de referência.
  - `photo` (Arquivo): Imagem com o rosto a ser validado.
- **Respostas:**
  - `200 OK`: Retorna `VerificationResponse`.
  - `404 Not Found`: Usuário alvo do CPF não existe.

### 8. Identificação Facial (1:N)
Compara a foto enviada com toda a base ativa do cache em memória e retorna o usuário com maior similaridade que supere o limiar configurado.
- **Método:** `POST`
- **Caminho:** `/api/users/identify`
- **Content-Type:** `multipart/form-data`
- **Parâmetros:**
  - `photo` (Arquivo): Imagem com o rosto a ser identificado.
- **Respostas:**
  - `200 OK`: Retorna `IdentificationResponse`.
    - Se identificado, `identified` será `true` e os dados do usuário virão preenchidos.
    - Se não for reconhecido, `identified` será `false` com os dados do usuário como `null`.

### 9. Relatório de Duplicidades (Auditoria)
Faz um cruzamento completo $O(N^2)$ em memória e exibe pares de usuários cadastrados com faces similares acima do limiar.
- **Método:** `GET`
- **Caminho:** `/api/users/duplicates`
- **Respostas:**
  - `200 OK`: Retorna lista de `DuplicatePairResponse` (a imagem base64 é omitida nas respostas para economizar banda).

---

## Tratamento de Erros e Payload Padrão

Sempre que a aplicação lança uma exceção, o [GlobalExceptionHandler](file:///o:/JavaProjects/face-registry/backend/src/main/java/com/batistell/faceregistry/exception/GlobalExceptionHandler.java) captura o erro e retorna uma resposta padronizada em JSON:

```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Esta face já está cadastrada no sistema sob o CPF: 123.456.789-09 (Nome: João da Silva).",
  "timestamp": "2026-06-25T14:40:00.123456"
}
```

### Códigos de Status Mapeados

| Status HTTP | Erro (Reason Phrase) | Exceção Lançada | Motivo Comum |
| :--- | :--- | :--- | :--- |
| **`400`** | `Bad Request` | `NoFaceDetectedException` | Nenhum rosto encontrado na foto. |
| **`400`** | `Bad Request` | `MultipleFacesDetectedException` | Mais de um rosto detectado na foto. |
| **`400`** | `Bad Request` | `InvalidImageException` | Imagem corrompida, formato inválido ou rosto muito distante/pequeno. |
| **`400`** | `Bad Request` | `InvalidCpfException` | CPF vazio, com tamanho incorreto ou com dígitos verificadores inválidos. |
| **`404`** | `Not Found` | `UserNotFoundException` | CPF informado não consta na base de dados. |
| **`409`** | `Conflict` | `DuplicateCpfException` | Tentativa de cadastrar um CPF já existente. |
| **`409`** | `Conflict` | `DuplicateFaceException` | Bloqueio de cadastro biométrico repetido. |
| **`503`** | `Service Unavailable` | `BiometricProcessingException` | Falha interna ao executar a inferência de IA nos modelos PyTorch do DJL. |
