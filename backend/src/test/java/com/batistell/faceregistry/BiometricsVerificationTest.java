package com.batistell.faceregistry;

import com.batistell.faceregistry.model.User;
import com.batistell.faceregistry.repository.UserRepository;
import com.batistell.faceregistry.service.FaceBiometricsService;
import com.batistell.faceregistry.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "face.biometrics.mock=false", // Force loading real DJL models
    "face.recognition.threshold=0.60"
})
public class BiometricsVerificationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FaceBiometricsService faceBiometricsService;

    @BeforeEach
    public void setup() {
        userRepository.deleteAll();
        userService.initCache();
    }

    @Test
    public void testBiometricsMatchAcrossCustomExamples() throws Exception {
        // Carrega a primeira foto do sujeito de teste (Kana, CPF 62079744844)
        String registerPhotoPath = "examples/kana_62079744844_register.jpeg";
        byte[] registerBytes;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(registerPhotoPath)) {
            assertNotNull(is, "Registration photo not found in classpath: " + registerPhotoPath);
            registerBytes = is.readAllBytes();
        }

        // Cadastra o usuário no banco com a foto de registro
        String cpf = "92479121305";
        String name = "Test Subject";
        userService.createUser(cpf, name, registerBytes, "register.jpg");

        // Verifica que o usuário foi persistido e possui embedding de 512 dimensões
        Optional<User> registeredUserOpt = userRepository.findByCpf(cpf);
        assertTrue(registeredUserOpt.isPresent());
        User registeredUser = registeredUserOpt.get();
        float[] registeredEmbedding = registeredUser.getEmbedding();
        assertNotNull(registeredEmbedding);
        assertEquals(512, registeredEmbedding.length, "Embedding should have 512 dimensions");

        // Fotos alternativas da mesma pessoa para verificação cruzada (devem dar match)
        String[] testPhotos = {
            "examples/kana_62079744844_verify1.jpeg",
            "examples/kana_62079744844_verify2.jpeg",
            "examples/kana_62079744844_verify3.jpeg",
            "examples/kana_62079744844_verify4.jpeg"
        };

        for (String photoPath : testPhotos) {
            byte[] photoBytes;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(photoPath)) {
                assertNotNull(is, "Test photo not found in classpath: " + photoPath);
                photoBytes = is.readAllBytes();
            }

            // Extrai embedding da foto de teste usando o modelo real
            float[] testEmbedding = faceBiometricsService.extractFaceEmbedding(photoBytes, photoPath);
            assertNotNull(testEmbedding);
            assertEquals(512, testEmbedding.length);

            // Calcula similaridade e valida match
            double similarity = faceBiometricsService.calculateCosineSimilarity(registeredEmbedding, testEmbedding);
            boolean isMatch = faceBiometricsService.isMatch(similarity);

            System.out.println("Comparing registered face with " + photoPath + ": similarity = " + similarity + ", isMatch = " + isMatch);

            // Deve dar match — mesma pessoa em poses diferentes
            assertTrue(isMatch, "Photo " + photoPath + " should match the registered user (similarity: " + similarity + ", threshold: " + faceBiometricsService.getThreshold() + ")");
        }
    }

    @Test
    public void testBiometricsMatchFamousPeople() throws Exception {
        // Pares de fotos de celebridades: [nome, cpf, foto_registro, foto_verificação, threshold_esperado]
        Object[][] famousPairs = {
            {"Barack Obama", "64657075942", "examples/obama_64657075942_2.jpg", "examples/obama_64657075942_3.jpg", 0.60},
            {"Joe Biden", "21603180001", "examples/biden_21603180001_1.jpg", "examples/biden_21603180001_2.jpg", 0.60},
            {"Elon Musk", "92230775596", "examples/musk_92230775596_1.jpg", "examples/musk_92230775596_2.jpg", 0.60},
            {"Lionel Messi", "74394356644", "examples/messi_74394356644_1.jpg", "examples/messi_74394356644_2.jpg", 0.60},
            {"Kana", "62079744844", "examples/kana_62079744844_1.jpg", "examples/kana_62079744844_2.jpg", 0.50}
        };

        for (Object[] pair : famousPairs) {
            String name = (String) pair[0];
            String cpf = (String) pair[1];
            String photo1Path = (String) pair[2];
            String photo2Path = (String) pair[3];
            double expectedThreshold = (Double) pair[4];

            byte[] photo1Bytes;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(photo1Path)) {
                assertNotNull(is, "Photo 1 not found in classpath: " + photo1Path);
                photo1Bytes = is.readAllBytes();
            }

            byte[] photo2Bytes;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(photo2Path)) {
                assertNotNull(is, "Photo 2 not found in classpath: " + photo2Path);
                photo2Bytes = is.readAllBytes();
            }

            float[] emb1 = faceBiometricsService.extractFaceEmbedding(photo1Bytes, photo1Path);
            float[] emb2 = faceBiometricsService.extractFaceEmbedding(photo2Bytes, photo2Path);

            assertNotNull(emb1);
            assertNotNull(emb2);
            assertEquals(512, emb1.length);
            assertEquals(512, emb2.length);

            double similarity = faceBiometricsService.calculateCosineSimilarity(emb1, emb2);
            boolean isMatch = similarity >= expectedThreshold;

            System.out.println("Famous Person Match [" + name + "]: similarity = " + similarity + ", expectedThreshold = " + expectedThreshold + ", isMatch = " + isMatch);

            assertTrue(isMatch, "Photos for " + name + " should match (similarity: " + similarity + ", expected threshold: " + expectedThreshold + ")");
        }
    }

    @Test
    public void testIdentifyFacePerformanceOneMillionUsers() throws Exception {
        UserService targetService = org.springframework.test.util.AopTestUtils.getTargetObject(userService);

        // Encontra o campo privado de cache em UserService
        java.lang.reflect.Field cacheField = UserService.class.getDeclaredField("userBiometricCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> cache = (List<Object>) cacheField.get(targetService);

        // Encontra o campo privado de lock em UserService
        java.lang.reflect.Field lockField = UserService.class.getDeclaredField("cacheLock");
        lockField.setAccessible(true);
        java.util.concurrent.locks.ReentrantReadWriteLock lock = (java.util.concurrent.locks.ReentrantReadWriteLock) lockField.get(targetService);

        // Encontra a classe/record interna UserCacheItem
        Class<?> cacheItemClass = Class.forName("com.batistell.faceregistry.service.UserService$UserCacheItem");
        java.lang.reflect.Constructor<?> constructor = cacheItemClass.getDeclaredConstructor(
            java.util.UUID.class, String.class, String.class, float[].class
        );
        constructor.setAccessible(true);

        System.out.println("Gerando 1 milhão de usuários fictícios em memória para o benchmark...");
        
        lock.writeLock().lock();
        try {
            cache.clear();
            for (int i = 0; i < 1_000_000; i++) {
                float[] emb = new float[512];
                emb[i % 512] = 1.0f; // Vetor unitário L2-normalizado super rápido de gerar
                Object item = constructor.newInstance(
                    java.util.UUID.randomUUID(),
                    String.format("%011d", i),
                    "Fictitious User " + i,
                    emb
                );
                cache.add(item);
            }
        } finally {
            lock.writeLock().unlock();
        }
        
        System.out.println("Cache populado com " + cache.size() + " usuários.");

        // Prepara uma foto real de teste
        String testPhotoPath = "examples/obama_64657075942_2.jpg";
        byte[] photoBytes;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(testPhotoPath)) {
            assertNotNull(is, "Test photo not found: " + testPhotoPath);
            photoBytes = is.readAllBytes();
        }

        // Extrai o embedding usando o serviço
        float[] targetEmbedding = faceBiometricsService.extractFaceEmbedding(photoBytes, "obama.jpg");

        // Aquecimento do JIT Compiler
        System.out.println("Aquecendo a JVM (JIT compiler)...");
        for (int w = 0; w < 15; w++) {
            userService.identifyFaceWithEmbedding(targetEmbedding);
        }

        // Mede o tempo da busca (1:1.000.000) no cache em memória
        long startTime = System.nanoTime();
        com.batistell.faceregistry.dto.UserDTOs.IdentificationResponse response = 
            userService.identifyFaceWithEmbedding(targetEmbedding);
        long durationNs = System.nanoTime() - startTime;
        double durationMs = durationNs / 1_000_000.0;

        System.out.println("Tempo de busca biométrica 1:1.000.000 (excluindo extração de IA): " + durationMs + " ms");
        System.out.println("Resultado da identificação: " + (response.identified() ? "Identificado" : "Não Identificado"));

        // Limpa o cache após o teste
        lock.writeLock().lock();
        try {
            cache.clear();
        } finally {
            lock.writeLock().unlock();
        }

        // Valida que o tempo de busca no cache é aceitável (menor que 200ms na CPU)
        assertTrue(durationMs < 200.0, "Tempo de busca em memória deve ser menor que 200ms para 1 milhão de usuários (obtido: " + durationMs + "ms)");
    }
}
