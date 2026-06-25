# 👤 Prep de Entrevista - Face Registry (Decisões de Engenharia)

Este documento foi criado para preparar você para a entrevista técnica sobre o projeto **Face Registry**. Ele detalha as escolhas de tecnologia de ponta (cutting-edge) aplicadas no sistema e lista perguntas técnicas complexas que os avaliadores podem fazer, juntamente com respostas detalhadas e fundamentadas.

---

## 🚀 Decisões de Arquitetura de Ponta (Cutting-Edge)

Antes de entrar nas perguntas e respostas, aqui está o resumo das principais escolhas de arquitetura que tornam este projeto de nível **Sênior**:

1.  **IA Embarcada na JVM (DJL + PyTorch JNI):** Em vez de criar um microserviço em Python com FastAPI ou Flask, o processamento de IA roda dentro do próprio processo Java. Isso elimina a latência de chamadas HTTP/gRPC entre serviços, reduz o consumo de memória na infraestrutura e simplifica a orquestração de containers.
2.  **Virtual Threads (Java 21):** O processamento de lotes concorre usando as novas *Virtual Threads* leves do Java. Isso otimiza o uso de CPU e I/O, impedindo que o pool de threads do SO seja exaurido durante múltiplos uploads simultâneos.
3.  **Cache Biométrico Reativo com Locks Diferenciados:** Para evitar consultas pesadas de dados binários do tipo `BYTEA` e conversões repetidas no banco, as biometrias ficam cacheadas em memória. O cache é protegido por um `ReentrantReadWriteLock`, permitindo leituras paralelas ilimitadas e garantindo exclusividade absoluta apenas na escrita.
4.  **Otimização Matemática (Dot Product de Vetores L2):** A similaridade de cosseno exige o cálculo de raízes quadradas e divisões na CPU. Como normalizamos os vetores biométricos na escala L2 (comprimento unitário = 1.0) logo na extração, a similaridade de cosseno é resolvida puramente por **Produto Escalar** (multiplicações e somas lineares simples), que roda ordens de grandeza mais rápido.
5.  **Controle de Transações Atômicas:** O upload em lote é transacional (`@Transactional`). Se qualquer uma das fotos falhar na validação biométrica (ex: se a 9ª foto de um lote de 10 não tiver rosto ou for duplicada de outro usuário), a transação inteira sofre rollback automático no banco e os dados voltam ao estado original intacto.

---

## ❓ Perguntas Pertinentes de Entrevista & Respostas Técnicas

### Pergunta 1: Por que você escolheu rodar PyTorch na JVM usando DJL em vez de fazer uma API REST em Python (como Flask/FastAPI), que é o padrão da indústria para IA?
**Resposta:** 
> "A criação de um microserviço Python separado traria três grandes problemas para este ecossistema: latência de rede, sobrecarga de infraestrutura e complexidade de deploy. Ao trafegar imagens pesadas via REST/gRPC entre a JVM e o Python, adicionaríamos latência de serialização/deserialização e transporte. Além disso, teríamos que gerenciar mais um container em produção, dividindo recursos de CPU/RAM.
>
> Escolhi o **Deep Java Library (DJL)** da AWS com o motor PyTorch nativo via **JNI (Java Native Interface)** porque ele carrega as bibliotecas dinâmicas do **PyTorch** nativo de forma automática para o sistema operacional hospedado. A inferência ocorre in-process com desempenho idêntico ao C++ nativo e sem custos de rede. Isso reduz o custo de infraestrutura e simplifica a implantação, mantendo apenas um container para o backend."

---

### Pergunta 2: Como você tratou o risco de OOM (Out Of Memory) na inicialização da aplicação ao carregar milhares de usuários do banco para o cache em memória?
**Resposta:**
> "Para alimentar o cache biométrico em memória sem sobrecarregar a JVM, eu utilizei duas técnicas fundamentais:
> 1. **Projeção JPQL Otimizada (UserLightweight):** O banco de dados PostgreSQL armazena as fotos como `BYTEA` (que podem ter até 10MB por registro). Se usássemos o `findAll()` padrão do JPA, o Hibernate traria todos os arrays de bytes das imagens para a memória heap, causando um estouro de memória instantâneo com milhares de registros. Eu criei a projeção `UserLightweight` que seleciona apenas `id`, `cpf`, `name` e `embedding` do banco via query customizada, ignorando a coluna de imagem.
> 2. **Cache Otimizado:** O cache armazena apenas as referências de texto e o array de floats de 512 posições. O consumo de memória por usuário no cache é de aproximadamente 2.2 KB. Com isso, conseguimos manter mais de 100.000 usuários em memória gastando menos de 250 MB de heap."

---

### Pergunta 3: Qual é a diferença matemática e de performance entre a Similaridade de Cosseno tradicional e o Produto Escalar (Dot Product) que você implementou?
**Resposta:**
> "A similaridade de cosseno matemática entre dois vetores $A$ e $B$ é dada pela fórmula:
> $$\text{similaridade} = \frac{A \cdot B}{\|A\| \|B\|}$$
> Onde $\|A\|$ e $\|B\|$ são as normas Euclidiana (L2) dos vetores, que exigem calcular a soma dos quadrados de todas as 512 posições e extrair a raiz quadrada de cada vetor, seguido por uma divisão em ponto flutuante.
>
> No meu projeto, eu normalizo os embeddings gerados pelo FaceNet aplicando a normalização L2 logo após a extração, garantindo que a norma do vetor seja exatamente igual a 1 ($\|A\| = 1$ e $\|B\| = 1$). Quando os vetores são normalizados, o denominador da fórmula se torna $1 \cdot 1 = 1$. Portanto, a similaridade de cosseno passa a ser equivalente ao **Produto Escalar (Dot Product)**:
> $$\text{similaridade} = A \cdot B = \sum_{i=1}^{512} (A_i \cdot B_i)$$
> Isso elimina completamente as operações custosas de raiz quadrada (`Math.sqrt`) e divisão float no loop de busca 1:N. Fazemos apenas 512 multiplicações e somas na CPU, o que aumenta o throughput de busca biométrica drasticamente."

---

### Pergunta 4: Por que você usou um limite de 5.000 usuários para alternar entre busca linear e busca paralela (ForkJoin) na identificação 1:N?
**Resposta:**
> "A paralelização de tarefas usando o pool de threads do ForkJoin no Java (via Parallel Streams ou CompletableFuture) introduz um custo de overhead devido ao agendamento de threads, divisão do array (split) e coordenação de junção dos resultados (join). 
> Para bases de dados pequenas (abaixo de 5.000 usuários), a busca sequencial linear em uma única thread é mais rápida, pois o tempo de processamento dos cálculos é menor do que o overhead necessário para gerenciar múltiplas threads.
> Quando a base escala além desse limiar, o benefício de distribuir o processamento biométrico $O(N)$ nos núcleos da CPU supera o custo de overhead das threads. Por isso, dividimos a lista em pedaços dinâmicos baseados no número de processadores disponíveis (`Runtime.getRuntime().availableProcessors()`) e calculamos de forma paralela."

---

### Pergunta 5: Como você garantiu a consistência dos dados de biometria caso dois administradores tentem atualizar a foto do mesmo usuário simultaneamente?
**Resposta:**
> "Eu utilizei um mecanismo de **Lock Pessimista de Escrita (Pessimistic Write Lock)** na camada de banco de dados do JPA:
> ```java
> @Lock(LockModeType.PESSIMISTIC_WRITE)
> @Query("SELECT u FROM User u WHERE u.cpf = :cpf")
> Optional<User> findByCpfWithLock(@Param("cpf") String cpf);
> ```
> Quando a transação de atualização do usuário A inicia e busca o usuário com esse método, o banco de dados PostgreSQL bloqueia o registro usando uma cláusula `SELECT ... FOR UPDATE`. Se o usuário B tentar atualizar o mesmo CPF ao mesmo tempo, a sua thread ficará bloqueada aguardando a transação de A terminar (fazer commit ou rollback). Isso impede condições de corrida e garante que a biometria cadastrada seja consistente."

---

### Pergunta 6: Se o endpoint de cadastro em lote recebe 50 registros e o 49º falhar no processamento de IA (ex: sem rosto na foto), o que acontece com os 48 que foram processados com sucesso?
**Resposta:**
> "Toda a lógica do método `createUsersBatch` no `UserService` está sob a anotação `@Transactional` do Spring. 
> As imagens e embeddings são computados de forma concorrente em threads virtuais. Se qualquer uma das tarefas lançar uma exceção de validação (ex: `NoFaceDetectedException`), essa exceção é propagada e captura o fluxo. A transação do Spring intercepta o erro e realiza o **rollback completo** de todas as operações de banco de dados executadas no lote.
> Nenhuma linha é inserida no banco e o cache em memória não é atualizado, garantindo a **atomicidade total** do processo em lote (princípio do Tudo ou Nada)."

---

### Pergunta 7: Por que você implementou o mecanismo de corte (Zoom, Pan e Canvas) no frontend se você já tem o modelo RetinaFace de detecção no backend?
**Resposta:**
> "O modelo de detecção RetinaFace do backend é robusto, mas ele possui critérios técnicos rígidos para garantir a precisão biometria (FaceNet):
> 1. Exige exatamente um rosto.
> 2. O rosto deve ocupar uma área representativa da foto (ratio $\ge 0.15$).
>
> Se um usuário enviar uma foto de corpo inteiro ou com fundos complexos, o backend rejeitará o cadastro. Ao disponibilizar ferramentas de **Zoom e Pan** na interface Angular usando a API de Canvas HTML5, o usuário consegue fazer o reenquadramento da imagem antes do upload. O Canvas gera uma nova imagem recortada e focada apenas no rosto. Isso melhora drasticamente a experiência do usuário e garante que o backend processe a foto com sucesso."

---

### Pergunta 8: Qual o papel do `ReentrantReadWriteLock` no gerenciamento do cache de biometrias?
**Resposta:**
> "Um lock de exclusão mútua simples (como `synchronized` ou `ReentrantLock`) bloquearia consultas biométricas (leituras) sempre que outro usuário estivesse cadastrando ou sendo verificado, o que causaria um gargalo grave em produção.
> O `ReentrantReadWriteLock` permite que múltiplas operações de leitura (como verificação 1:1 e identificação 1:N) adquiram o `readLock()` concorrentemente sem se bloquearem. O bloqueio só ocorre de fato quando há uma escrita (cadastro, edição ou exclusão), que adquire o `writeLock()`. Durante a escrita, novas leituras e escritas ficam suspensas até que a gravação termine, garantindo a consistência total dos dados biométricos em memória sem penalizar o desempenho de leitura."

---

### Pergunta 9: Como o sistema se comporta caso o banco de dados caia temporariamente? As consultas biométricas continuam funcionando?
**Resposta:**
> "O cache biométrico armazena todas as chaves (CPFs) e assinaturas vetoriais (embeddings) em memória RAM. Para uma operação de **Verificação (1:1)**, o cálculo da similaridade ocorre inteiramente em memória sem tocar no banco de dados. No entanto, para a **Identificação (1:N)**, após descobrir o melhor candidato em memória, a aplicação precisa realizar uma consulta por chave (`findById`) no banco de dados para carregar os dados cadastrais (como a imagem original do usuário em Base64) para o DTO de retorno.
> Portanto, em caso de indisponibilidade de banco de dados:
> - O cálculo biométrico bruto e a decisão de correspondência continuam acessíveis no cache.
> - As chamadas da API que retornam informações completas dos usuários (ex: `/api/users/identify` ou consultas ao CRUD) falharão com uma exceção de conexão do banco de dados, sendo interceptadas pelo `GlobalExceptionHandler` que retornará um status HTTP 500."

---

### Pergunta 10: Qual é o custo de tempo (complexidade assintótica) da API de auditoria de duplicados (`/api/users/duplicates`), e como você otimizaria se a base escalasse para 1 milhão de usuários?
**Resposta:**
> "A lógica atual de varredura de duplicados compara cada usuário com todos os outros no cache biométrico para identificar similaridades acima do threshold. Isso representa um clássico algoritmo de comparação par-a-par, cuja complexidade assintótica é quadrática:
> $$\mathcal{O}(N^2)$$
> Se a base tiver 1.000 usuários, faremos cerca de 500.000 comparações. Se tiver 1 milhão, o número de comparações sobe para 500 bilhões, inviabilizando o tempo de resposta em tempo real.
> Para escalar essa auditoria para milhões de registros, implementaríamos algoritmos de **Busca por Vizinhos Mais Próximos Aproximados (ANN - Approximate Nearest Neighbors)** usando indexação vetorial espacial (como HNSW - Hierarchical Navigable Small World, ou Annoy). Isso reduziria a complexidade das buscas de $\mathcal{O}(N^2)$ para $\mathcal{O}(N \log N)$ ou próximo de $\mathcal{O}(N)$ em média."

---

### Pergunta 11: Em ambientes de produção atrás de Nginx Reverse Proxy, o backend pode receber o IP do proxy em vez do IP real do cliente. Como sua infraestrutura resolveu isso?
**Resposta:**
> "No arquivo [nginx.conf](file:///o:/JavaProjects/face-registry/frontend/nginx.conf), nós configuramos o proxy reverso para passar os cabeçalhos de identificação de rede reais para o Spring Boot:
> ```nginx
> proxy_set_header Host $host;
> proxy_set_header X-Real-IP $remote_addr;
> proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
> proxy_set_header X-Forwarded-Proto $scheme;
> ```
> No Spring Boot (backend), configuramos a propriedade `server.forward-headers-strategy=native` (ou framework). Isso garante que o Spring Boot capture automaticamente o IP real do cliente originador a partir do cabeçalho `X-Forwarded-For` para fins de auditoria, logs e prevenção de ataques (como rate-limiting), em vez de enxergar apenas o IP local do container Nginx."

---

### Pergunta 12: Qual o papel do volume `djl_cache` no Docker Compose? O que aconteceria se você não mapeasse esse diretório?
**Resposta:**
> "Os modelos de Deep Learning (RetinaFace para detecção e FaceNet para extração) pesam cerca de 100 MB cada. Na primeira vez em que a aplicação inicializa, o motor do DJL precisa baixar esses modelos do PyTorch Model Zoo público da AWS.
> Mapeando o volume nomeado `djl_cache` no diretório `/root/.djl.ai` do container backend, nós persistimos esses modelos de IA no disco rígido do host EC2. 
> Se esse volume não fosse mapeado, toda vez que o container do backend sofresse um reinício (`docker compose restart` ou uma atualização de CI/CD), os modelos seriam apagados. A aplicação teria que baixar novamente os 200 MB de modelos durante a inicialização, o que atrasaria o boot, geraria dependência da internet externa e poderia causar timeout do healthcheck."

---

### Pergunta 13: Por que a webcam no Angular exige HTTPS para funcionar, e como você lidou com isso no ambiente de Docker local?
**Resposta:**
> "Por razões de segurança e privacidade, os navegadores modernos (Chrome, Firefox, Safari) implementam a política de *Secure Contexts* para acesso a hardwares sensíveis (como câmera e microfone via `getUserMedia`). Essa política bloqueia a captura em conexões HTTP inseguras, permitindo o funcionamento apenas sob o protocolo HTTPS (ou em conexões locais mapeadas estritamente sob a string literal `http://localhost`).
> Para viabilizar os testes locais e em rede de desenvolvimento (como testar pelo celular acessando o IP do computador), o [Dockerfile do Frontend](file:///o:/JavaProjects/face-registry/frontend/Dockerfile) executa o comando `openssl` durante a fase de build para gerar uma chave RSA privada e um certificado SSL autoassinado de forma dinâmica. O servidor Nginx do container é configurado na porta `443` utilizando esse certificado para que o browser enxergue um contexto seguro e libere a câmera."

---

### Pergunta 14: Como o Angular 17 Standalone ajuda no carregamento da aplicação comparado à arquitetura tradicional com NgModules?
**Resposta:**
> "A arquitetura Standalone introduzida como padrão no Angular 17 elimina a necessidade do arquivo `app.module.ts`. Cada componente declara suas próprias importações diretamente nos metadados `@Component` (como `imports: [CommonModule, FormsModule]`).
> Isso traz dois grandes benefícios:
> 1. **Lazy Loading Nativo Avançado:** O empacotador (Webpack/Vite) consegue fazer uma segmentação de código (code-splitting) muito mais eficiente. Se a aplicação crescer e passar a ter múltiplos fluxos, o navegador baixa apenas o código do componente que o usuário está visualizando, melhorando o *First Contentful Paint* (FCP).
> 2. **Melhor legibilidade e manutenabilidade:** É mais fácil mapear a árvore de dependências do componente diretamente no seu arquivo `.ts`, sem a poluição de declarações globais no NgModule."

---

### Pergunta 15: Por que no controller backend foi aplicada a anotação `@CrossOrigin(origins = "*")`? Quais os riscos e como você trataria em produção?
**Resposta:**
> "A anotação `@CrossOrigin(origins = "*")` foi usada para desabilitar a política do CORS (Cross-Origin Resource Sharing) durante as etapas de desenvolvimento, permitindo que a aplicação Angular rodando localmente (normalmente em `http://localhost:4200`) fizesse chamadas de API para o backend (escutando na porta `http://localhost:8080`) sem bloqueio pelo navegador.
> No entanto, o uso de curinga (`*`) em produção é uma falha de segurança grave (permite que scripts maliciosos de qualquer site leiam dados da nossa API).
> Para produção, eu configuraria o CORS de duas formas:
> - **Ideal (Restrição por Config):** Substituir o `*` por uma variável injetada do ambiente que aponta estritamente para o domínio do frontend (ex: `origins = "${app.cors.allowed-origin}"`).
> - **Infraestrutura:** Se a aplicação estiver atrás do proxy reverso do Nginx (como é o nosso caso no Docker Compose), o Nginx pode responder a todas as requisições no mesmo domínio e porta (via subcaminho `/face-registry/api/`). Como o frontend e o backend compartilham a mesma origem sob a ótica do navegador, o CORS deixa de ser necessário e a anotação pode ser completamente removida."
