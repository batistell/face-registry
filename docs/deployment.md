# 🚀 Implantação e DevOps - Face Registry

O projeto **Face Registry** está totalmente conteinerizado usando **Docker** e orquestrado via **Docker Compose**. O ambiente conta com certificação SSL para habilitar o uso da câmera no navegador, banco de dados persistente, caches persistidos para modelos de Inteligência Artificial e suporte a túneis seguros para publicação.

---

## 🏗️ Orquestração de Serviços (Docker Compose)

O arquivo [docker-compose.yml](file:///o:/JavaProjects/face-registry/docker-compose.yml) define quatro serviços que cooperam na mesma rede virtual:

```mermaid
graph TD
    db[(db - PostgreSQL 15)]
    backend[backend - Spring JRE 21]
    frontend[frontend - Nginx HTTPS]
    tunnel[tunnel - Cloudflared]

    frontend -->|Proxy: /api/*| backend
    backend -->|JDBC| db
    tunnel -->|Encaminha tráfego| frontend
```

### 1. Banco de Dados (`db`)
- **Imagem:** `postgres:15-alpine` (leve e segura).
- **Persistência:** Volume nomeado `pgdata` mapeado em `/var/lib/postgresql/data`.
- **Healthcheck:** Utiliza a ferramenta nativa `pg_isready` para garantir que o PostgreSQL está aceitando conexões antes de liberar a inicialização do back-end.

### 2. Backend (`backend`)
- **Build:** Compilado a partir de [backend/Dockerfile](file:///o:/JavaProjects/face-registry/backend/Dockerfile).
- **Variáveis de Ambiente Principais:**
  - `SPRING_DATASOURCE_URL`: Aponta para o container `db`.
  - `FACE_RECOGNITION_THRESHOLD`: Define o limiar mínimo de similaridade para aprovação de biometrias (Padrão: `0.60`).
  - `PYTORCH_FLAVOR`: Define a arquitetura do motor PyTorch (CPU por padrão).
- **Persistência do Cache DJL (`djl_cache`):** 
  Os arquivos de pesos dos modelos RetinaFace e FaceNet possuem cerca de 100MB+ e são baixados da internet na inicialização. O volume mapeia `/root/.djl.ai` para evitar que o download ocorra novamente caso o container seja recriado.

### 3. Frontend (`frontend`)
- **Build:** Compilado a partir de [frontend/Dockerfile](file:///o:/JavaProjects/face-registry/frontend/Dockerfile).
- **Geração de SSL/HTTPS:**
  Os navegadores modernos bloqueiam o acesso a APIs de mídia (câmera via webcam) se a página for servida em conexões HTTP inseguras (exceto em `localhost` literal). 
  Durante o build do container do frontend, o `openssl` gera automaticamente um certificado autoassinado de 365 dias para habilitar HTTPS seguro na porta `443` (mapeada para a porta `8000` do host).
- **Proxy Reverso:**
  O arquivo [nginx.conf](file:///o:/JavaProjects/face-registry/frontend/nginx.conf) configura o Nginx para servir os arquivos estáticos do Angular no prefixo `/face-registry/` e encaminhar requisições com prefixo `/api/*` diretamente para o container `backend:8080`.

### 4. Túnel Cloudflare (`tunnel`)
- **Imagem:** `cloudflare/cloudflared:latest`
- **Utilidade:** Permite expor a aplicação rodando localmente para a internet pública de forma segura, sem a necessidade de abrir portas no roteador ou configurar IPs públicos. O container conecta ao proxy do Cloudflare Zero Trust usando o token fornecido em `${TUNNEL_TOKEN}` no arquivo `.env`.

---

## ☁️ Implantação na Nuvem (AWS EC2)

Abaixo está o roteiro passo a passo para provisionar uma máquina virtual **Amazon EC2** e implantar os containers Docker da aplicação.

### 1. Provisionamento do EC2 (AWS Console)
*   **Sistema Operacional:** Ubuntu Server 22.04 LTS (x86_64).
*   **Dimensionamento (Instance Type):** Recomendado no mínimo `t3.medium` (2 vCPUs, 4 GB RAM) ou superior. O carregamento dos modelos RetinaFace e FaceNet pelo DJL exige cerca de 1.5 GB a 2 GB de memória Heap ativa na JVM. Instâncias menores (como `t3.micro`) podem sofrer de *Out Of Memory* (OOM) ou travamento de CPU na inicialização do PyTorch.
*   **Armazenamento:** Mínimo de 20 GB de SSD gp3 para acomodar os caches do PyTorch e as imagens Docker dos builds.
*   **Grupo de Segurança (Security Group):**
    *   **Inbound Rules (Regras de Entrada):**
        *   `TCP 22` (SSH) de IPs confiáveis.
        *   `TCP 80` (HTTP) liberado para o público.
        *   `TCP 8000` (HTTPS) liberado para tráfego do frontend do Nginx.

### 2. Instalação do Docker e Compose no EC2
Após se conectar via SSH (`ssh -i chave.pem ubuntu@ec2-ip-publico`), execute os comandos para instalar o motor Docker moderno:

```bash
# Atualiza os repositórios do sistema
sudo apt update && sudo apt upgrade -y

# Instala pré-requisitos
sudo apt install apt-transport-https ca-certificates curl gnupg lsb-release -y

# Instala motor Docker e docker-compose plugin
sudo apt install docker.io docker-compose-v2 -y

# Adiciona o usuário do Ubuntu ao grupo do Docker para não precisar usar sudo nos comandos docker
sudo usermod -aG docker ubuntu

# Reinicie a sessão SSH para aplicar as alterações de grupo
exit
```

### 3. Clone do Repositório e Configuração
Reconecte na máquina EC2 e prepare o repositório da aplicação:

```bash
# Clone o repositório do projeto
git clone <url-do-repositorio> face-registry
cd face-registry

# Copie o arquivo .env a partir do template e configure as variáveis
cp .env.example .env
nano .env
```

### 4. Deploy da Stack
Inicie a aplicação utilizando o Docker Compose:

```bash
# Constrói as imagens e inicia os containers em background
docker compose up --build -d

# Monitore o carregamento do backend e dos modelos de IA
docker compose logs -f backend
```

---

## 🐳 Dockerfiles de Build em Estágios Múltiplos (Multi-Stage)

Ambos os Dockerfiles utilizam builds em múltiplos estágios para garantir imagens de produção limpas e sem compiladores extras instalados.

### Dockerfile do Back-end
1. **Estágio 1 (Builder):** Usa imagem Maven com JDK 21 para instalar as dependências e empacotar a aplicação em um arquivo `.jar`.
2. **Estágio 2 (Runtime):** Usa imagem pura JRE 21 (Eclipse Temurin) para rodar o `.jar` final, mantendo a imagem compacta.

### Dockerfile do Front-end
1. **Estágio 1 (Builder):** Usa Node.js 20 para baixar dependências via `npm ci` e buildar a aplicação Angular otimizada para produção.
2. **Estágio 2 (Runtime):** Instala o servidor leve Nginx, gera a chave SSL autoassinada e copia os arquivos compilados da etapa anterior para a pasta pública do Nginx.

---

## 🛠️ Comandos de Operação Úteis

### 1. Subir toda a Stack localmente compilando do zero:
```bash
docker compose up --build -d
```

### 2. Verificar os logs em tempo real:
```bash
docker compose logs -f
```

### 3. Derrubar o ambiente mantendo os volumes persistentes:
```bash
docker compose down
```

### 4. Limpar banco de dados e caches de modelos:
```bash
docker compose down -v
```
*(Atenção: esse comando apagará todas as biometrias cadastradas e exigirá que o download dos modelos da internet ocorra novamente no próximo boot).*

---

## 🔁 Esteira de Integração e Entrega Contínua (CI/CD)

A aplicação conta com uma esteira de CI/CD configurada via **GitHub Actions** em [.github/workflows/ci-cd.yml](file:///o:/JavaProjects/face-registry/.github/workflows/ci-cd.yml). A esteira é disparada automaticamente a cada push na branch `master`.

### 🛠️ Configuração de Segredos (GitHub Secrets)

Para habilitar o deploy automático no seu EC2, você deve cadastrar as chaves e credenciais de acesso no painel do seu repositório no GitHub:

1. Acesse o seu repositório no GitHub.
2. Vá em **Settings** (Configurações) -> **Secrets and variables** -> **Actions**.
3. Clique no botão **New repository secret** (Novo segredo de repositório).
4. Adicione as 3 variáveis a seguir:

| Nome do Segredo | Descrição | Exemplo de Valor |
| :--- | :--- | :--- |
| `EC2_SSH_KEY` | Conteúdo do arquivo de chave privada `.pem` que dá acesso ao seu EC2. | `-----BEGIN RSA PRIVATE KEY----- \n ...` |
| `EC2_HOST` | Endereço IP público ou DNS da sua instância EC2. | `54.210.12.34` |
| `EC2_USER` | Usuário SSH para conexão no EC2 (padrão `ubuntu`). | `ubuntu` |

### ⚙️ Como funciona a pipeline:
1. **CI (Integração Contínua):** Roda os testes unitários do backend (`mvn test`) e realiza o build de produção do frontend Angular (`npm run build`).
2. **CD - Build & Push:** Constrói as imagens Docker de produção para o Backend e Frontend e as publica no **GitHub Container Registry (GHCR)** utilizando o próprio token temporário da pipeline (`secrets.GITHUB_TOKEN`).
3. **CD - Deploy no EC2:** 
   - Copia o arquivo `docker-compose.yml` local do repositório para a pasta `~/face-registry` no EC2 via SCP.
   - Acessa o EC2 via SSH, faz login no GHCR, baixa as novas imagens (`docker compose pull`), reinicia os containers com a nova versão (`docker compose up -d`) e limpa imagens antigas para economizar espaço em disco (`docker image prune -f`).

