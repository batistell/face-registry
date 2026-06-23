package com.batistell.faceregistry;

import com.batistell.faceregistry.model.User;
import com.batistell.faceregistry.repository.UserRepository;
import com.batistell.faceregistry.service.FaceBiometricsService;
import com.batistell.faceregistry.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Teste de integração que popula o banco de dados com todas as imagens do diretório examples/,
 * valida a correspondência biométrica das fotos secundárias dos famosos e remove arquivos inválidos.
 *
 * <p><b>ATENÇÃO:</b> Este teste utiliza modelos DJL reais (mock=false) e pode EXCLUIR
 * arquivos inválidos do sistema de arquivos. Execute com consciência.</p>
 *
 * <p>Ordem de execução:</p>
 * <ol>
 *   <li>Limpa o banco de dados (H2 em memória)</li>
 *   <li>Cadastra as fotos primárias dos famosos (_1.jpg)</li>
 *   <li>Valida fotos secundárias dos famosos contra o embedding cadastrado — exclui arquivos que não correspondem</li>
 *   <li>Cadastra todos os randomusers com nomes brasileiros gerados</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {"face.biometrics.mock=false"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DatabasePopulationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FaceBiometricsService faceBiometricsService;

    /** Caminho absoluto para o diretório examples/ na raiz do projeto. */
    private Path examplesDir;

    /** Contadores para o resumo final do teste. */
    private int registeredCount = 0;
    private int validatedCount = 0;
    private int deletedCount = 0;

    // ==================================================================================
    // Dados dos famosos: [nome, cpf, prefixo_do_arquivo]
    // O arquivo primário para cadastro é sempre o sufixo "_1" (ex: obama_64657075942_1.jpg)
    // ==================================================================================
    private static final String[][] FAMOUS_PEOPLE = {
        {"Barack Obama",   "64657075942", "obama"},
        {"Joe Biden",      "21603180001", "biden"},
        {"Elon Musk",      "92230775596", "musk"},
        {"Lionel Messi",   "74394356644", "messi"},
        {"Kana",           "62079744844", "kana"},
        {"Lena",           "83992409821", "lena"},
    };

    // ==================================================================================
    // Nomes brasileiros para os randomusers (50 masculinos + 50 femininos)
    // ==================================================================================
    private static final String[] MALE_NAMES = {
        "João Silva", "Pedro Santos", "Lucas Oliveira", "Gabriel Souza", "Matheus Costa",
        "Rafael Pereira", "Bruno Ferreira", "André Almeida", "Thiago Rodrigues", "Felipe Lima",
        "Diego Martins", "Gustavo Nascimento", "Marcos Araújo", "Rodrigo Ribeiro", "Henrique Carvalho",
        "Daniel Gomes", "Leonardo Barbosa", "Vinícius Monteiro", "Eduardo Correia", "Carlos Cardoso",
        "Renato Teixeira", "Fábio Vieira", "Ricardo Mendes", "Paulo Moreira", "Roberto Castro",
        "Alexandre Pinto", "Fernando Rocha", "Marcelo Dias", "Leandro Campos", "Caio Nunes",
        "Hugo Machado", "Arthur Marques", "Samuel Freitas", "Otávio Lopes", "Victor Duarte",
        "Renan Moura", "William Cavalcanti", "Júlio Ramos", "Luciano Cunha", "Adriano Borges",
        "Bernardo Pires", "Matias Fonseca", "Nelson Melo", "Sérgio Rezende", "Maurício Fernandes",
        "Emerson Azevedo", "Douglas Braga", "Cristiano Xavier", "Igor Batista", "Cauan Nogueira"
    };

    private static final String[] FEMALE_NAMES = {
        "Ana Clara", "Maria Eduarda", "Juliana Santos", "Camila Oliveira", "Fernanda Costa",
        "Beatriz Souza", "Larissa Lima", "Patrícia Ferreira", "Amanda Almeida", "Carolina Rodrigues",
        "Isabela Martins", "Letícia Nascimento", "Mariana Araújo", "Gabriela Ribeiro", "Rafaela Carvalho",
        "Bruna Gomes", "Natália Barbosa", "Vanessa Monteiro", "Tatiana Correia", "Daniela Cardoso",
        "Priscila Teixeira", "Renata Vieira", "Aline Mendes", "Bianca Moreira", "Cristina Castro",
        "Débora Pinto", "Elaine Rocha", "Flávia Dias", "Helena Campos", "Ingrid Nunes",
        "Jéssica Machado", "Karen Marques", "Laura Andrade", "Mônica Freitas", "Nathalie Lopes",
        "Olívia Duarte", "Paula Moura", "Raquel Cavalcanti", "Sandra Ramos", "Simone Cunha",
        "Talita Borges", "Úrsula Pires", "Valéria Fonseca", "Yasmin Melo", "Zélia Rezende",
        "Adriana Fernandes", "Bárbara Azevedo", "Carla Braga", "Diana Xavier", "Érika Batista"
    };

    /** Extensões de imagem aceitas pelo sistema. */
    private static final Set<String> VALID_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");

    // ==================================================================================
    // Setup
    // ==================================================================================

    @BeforeAll
    void setup() {
        // Resolve o diretório examples/ a partir do diretório de trabalho (backend/)
        examplesDir = Paths.get(System.getProperty("user.dir")).getParent().resolve("examples").normalize();
        Assumptions.assumeTrue(Files.isDirectory(examplesDir),
                "Diretório examples/ não encontrado em: " + examplesDir);

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  DATABASE POPULATION TEST");
        System.out.println("  Diretório de exemplos: " + examplesDir);
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    // ==================================================================================
    // Teste 1: Limpar banco de dados
    // ==================================================================================

    @Test
    @Order(1)
    @DisplayName("1. Limpa o banco de dados para cadastro limpo")
    void cleanDatabase() {
        long before = userRepository.count();
        userRepository.deleteAll();
        userService.initCache();
        long after = userRepository.count();
        System.out.println("\n🗑️  Banco limpo: " + before + " registros removidos. Total atual: " + after);
        Assertions.assertEquals(0, after);
    }

    // ==================================================================================
    // Teste 2: Cadastrar fotos primárias dos famosos
    // ==================================================================================

    @Test
    @Order(2)
    @DisplayName("2. Cadastra fotos primárias dos famosos no banco")
    void registerFamousPeople() throws IOException {
        System.out.println("\n📸 Cadastrando famosos...");

        for (String[] person : FAMOUS_PEOPLE) {
            String name = person[0];
            String cpf = person[1];
            String prefix = person[2];

            // Busca o arquivo primário (_1.jpg ou _1.png)
            Path primaryPhoto = findPrimaryPhoto(prefix, cpf);
            Assertions.assertNotNull(primaryPhoto,
                    "Foto primária não encontrada para " + name + " (CPF " + cpf + ")");

            byte[] photoBytes = Files.readAllBytes(primaryPhoto);
            String filename = primaryPhoto.getFileName().toString();

            userService.createUser(cpf, name, photoBytes, filename);
            registeredCount++;

            System.out.println("  ✅ " + name + " (CPF " + cpf + ") cadastrado com " + filename);
        }

        Assertions.assertEquals(FAMOUS_PEOPLE.length, registeredCount,
                "Todos os famosos devem ser cadastrados");
    }

    // ==================================================================================
    // Teste 3: Validar fotos secundárias dos famosos e excluir inválidas
    // ==================================================================================

    @Test
    @Order(3)
    @DisplayName("3. Valida fotos secundárias dos famosos e exclui inválidas")
    void validateAndCleanSecondaryPhotos() throws IOException {
        System.out.println("\n🔍 Validando fotos secundárias dos famosos...");

        for (String[] person : FAMOUS_PEOPLE) {
            String name = person[0];
            String cpf = person[1];
            String prefix = person[2];

            // Recupera o embedding cadastrado no banco
            Optional<User> userOpt = userRepository.findByCpf(cpf);
            Assertions.assertTrue(userOpt.isPresent(), name + " deveria estar cadastrado");
            float[] registeredEmbedding = userOpt.get().getEmbedding();

            // Busca todas as fotos secundárias (tudo que não é _1)
            List<Path> secondaryPhotos = findSecondaryPhotos(prefix, cpf);
            System.out.println("\n  👤 " + name + " — " + secondaryPhotos.size() + " fotos secundárias encontradas");

            for (Path photo : secondaryPhotos) {
                String photoName = photo.getFileName().toString();
                String extension = getExtension(photoName).toLowerCase();

                // Verifica se a extensão é suportada pelo sistema
                if (!VALID_EXTENSIONS.contains(extension)) {
                    System.out.println("    ❌ " + photoName + " — extensão não suportada (" + extension + ") → EXCLUÍDO");
                    Files.deleteIfExists(photo);
                    deletedCount++;
                    continue;
                }

                try {
                    byte[] photoBytes = Files.readAllBytes(photo);
                    float[] testEmbedding = faceBiometricsService.extractFaceEmbedding(photoBytes, photoName);
                    double similarity = faceBiometricsService.calculateCosineSimilarity(registeredEmbedding, testEmbedding);
                    boolean isMatch = faceBiometricsService.isMatch(similarity);

                    if (isMatch) {
                        System.out.printf("    ✅ %s — similaridade: %.4f ✓%n", photoName, similarity);
                        validatedCount++;
                    } else {
                        System.out.printf("    ❌ %s — similaridade: %.4f (abaixo do threshold) → EXCLUÍDO%n",
                                photoName, similarity);
                        Files.deleteIfExists(photo);
                        deletedCount++;
                    }
                } catch (Exception e) {
                    // Qualquer erro de processamento (sem rosto, múltiplos rostos, imagem corrompida, etc.)
                    System.out.println("    ❌ " + photoName + " — ERRO: " + e.getMessage() + " → EXCLUÍDO");
                    Files.deleteIfExists(photo);
                    deletedCount++;
                }
            }
        }

        System.out.println("\n📊 Resultado da validação: " + validatedCount + " válidas, " + deletedCount + " excluídas");
    }

    // ==================================================================================
    // Teste 4: Cadastrar todos os randomusers com nomes brasileiros
    // ==================================================================================

        @Test
    @Order(4)
    @DisplayName("4. Cadastra todos os randomusers e novos usuários de fotos")
    void registerRandomUsers() throws IOException {
        System.out.println("\n👥 Cadastrando randomusers e novos usuários...");

        // Regex para extrair identificador, CPF e número do nome do arquivo
        // Padrão: [prefixo]_[CPF]_[Index].jpg
        Pattern pattern = Pattern.compile("([a-zA-Z0-9_\\-]+)_(\\d{11})_(\\d+)\\.jpg");

        // Coleta todos os arquivos que representam usuários de teste
        List<Path> userFiles = new ArrayList<>();

        try (Stream<Path> files = Files.list(examplesDir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> {
                     String filename = p.getFileName().toString();
                     // Deve casar com a regex geral de usuários de teste e ser .jpg
                     if (!filename.matches("[a-zA-Z0-9_\\-]+_\\d{11}_\\d+\\.jpg")) {
                         return false;
                     }
                     // Ignora famosos
                     for (String[] person : FAMOUS_PEOPLE) {
                         String famousPrefix = person[2] + "_" + person[1] + "_";
                         if (filename.toLowerCase().startsWith(famousPrefix.toLowerCase())) {
                             return false;
                         }
                     }
                     return true;
                 })
                 .sorted(Comparator.comparing(p -> {
                     // Ordena pelo número do arquivo (sufixo _N) para atribuição consistente de nomes
                     Matcher m = pattern.matcher(p.getFileName().toString());
                     if (m.matches()) return Integer.parseInt(m.group(3));
                     return 0;
                 }))
                 .forEach(userFiles::add);
        }

        int count = 0;

        for (Path photo : userFiles) {
            Matcher m = pattern.matcher(photo.getFileName().toString());
            if (!m.matches()) continue;

            String prefix = m.group(1);
            String cpf = m.group(2);
            int index = Integer.parseInt(m.group(3));
            String name;

            if (prefix.equals("randomuser_men")) {
                name = (index - 1 < MALE_NAMES.length)
                        ? MALE_NAMES[index - 1]
                        : "Usuário Masculino " + index;
            } else if (prefix.equals("randomuser_women")) {
                name = (index - 1 < FEMALE_NAMES.length)
                        ? FEMALE_NAMES[index - 1]
                        : "Usuária Feminina " + index;
            } else {
                // Formato customizado: extrai o nome a partir do nome do arquivo
                name = formatNameFromFilename(prefix);
            }

            try {
                byte[] photoBytes = Files.readAllBytes(photo);
                userService.createUser(cpf, name, photoBytes, photo.getFileName().toString());
                count++;
                registeredCount++;
                System.out.println("  ✅ " + name + " (CPF " + cpf + ") cadastrado com " + photo.getFileName().toString());
            } catch (Exception e) {
                System.out.println("  ⚠️ " + name + " (CPF " + cpf + ") — ERRO: " + e.getMessage());
            }
        }

        System.out.println("\n📊 Total de usuários de teste/random cadastrados: " + count);
    }

    /**
     * Formata um nome de exibição amigável a partir do prefixo do arquivo (ex: adam-djili -> Adam Djili).
     */
    private String formatNameFromFilename(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "Usuário Sem Nome";
        }
        String clean = prefix.replace("-", " ").replace("_", " ");
        return java.util.Arrays.stream(clean.split("\\s+"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    // ==================================================================================
    // Teste 5: Resumo final
    // ==================================================================================

    @Test
    @Order(5)
    @DisplayName("5. Exibe resumo final e valida integridade do banco")
    void printSummary() {
        long totalInDb = userRepository.count();

        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.println("  RESUMO FINAL");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  Total cadastrado neste teste: " + registeredCount);
        System.out.println("  Fotos secundárias validadas:  " + validatedCount);
        System.out.println("  Arquivos inválidos excluídos: " + deletedCount);
        System.out.println("  Total de registros no banco:  " + totalInDb);
        System.out.println("═══════════════════════════════════════════════════════════");

        // Valida que temos pelo menos os 6 famosos + alguns randomusers
        Assertions.assertTrue(totalInDb >= FAMOUS_PEOPLE.length,
                "O banco deve conter pelo menos os " + FAMOUS_PEOPLE.length + " famosos cadastrados");

        // Lista todos os registros para conferência
        System.out.println("\n📋 Registros no banco:");
        userRepository.findAll().forEach(user -> {
            float[] emb = user.getEmbedding();
            String embStatus = (emb != null && emb.length == 512) ? "✅ 512-D" : "❌ inválido";
            System.out.printf("  • %-25s CPF %-11s  Embedding: %s%n",
                    user.getName(), user.getCpf(), embStatus);
        });
    }

    // ==================================================================================
    // Métodos auxiliares
    // ==================================================================================

    /**
     * Busca o arquivo de foto primária (_1) de um famoso no diretório examples/.
     * Procura por padrões como: prefix_cpf_1.jpg, prefix_cpf_1.jpeg, prefix_cpf_1.png
     */
    private Path findPrimaryPhoto(String prefix, String cpf) throws IOException {
        String basePattern = prefix + "_" + cpf + "_1";
        try (Stream<Path> files = Files.list(examplesDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.startsWith(basePattern.toLowerCase()) &&
                               (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"));
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Busca todas as fotos secundárias de um famoso (tudo que não é _1).
     * Inclui sufixos como _2, _3, _alt, _register, _verify1, etc.
     */
    private List<Path> findSecondaryPhotos(String prefix, String cpf) throws IOException {
        String primaryBase = (prefix + "_" + cpf + "_1.").toLowerCase();
        String filePrefix = (prefix + "_" + cpf + "_").toLowerCase();

        try (Stream<Path> files = Files.list(examplesDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        // Pertence a este famoso mas NÃO é o arquivo primário (_1)
                        return name.startsWith(filePrefix) && !name.startsWith(primaryBase);
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * Retorna a extensão do arquivo incluindo o ponto (ex: ".jpg", ".png").
     */
    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return (lastDot >= 0) ? filename.substring(lastDot) : "";
    }
}
