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
    }

    @Test
    public void testBiometricsMatchAcrossCustomExamples() throws Exception {
        // Load the first photo of the subject
        String registerPhotoPath = "examples/WhatsApp Image 2026-06-22 at 15.20.20.jpeg";
        byte[] registerBytes;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(registerPhotoPath)) {
            assertNotNull(is, "Registration photo not found in classpath: " + registerPhotoPath);
            registerBytes = is.readAllBytes();
        }

        // Register the user
        String cpf = "12345678901";
        String name = "Test Subject";
        userService.createUser(cpf, name, registerBytes, "register.jpg");

        // Verify the user exists and has a generated embedding
        Optional<User> registeredUserOpt = userRepository.findByCpf(cpf);
        assertTrue(registeredUserOpt.isPresent());
        User registeredUser = registeredUserOpt.get();
        float[] registeredEmbedding = registeredUser.getEmbedding();
        assertNotNull(registeredEmbedding);
        assertEquals(512, registeredEmbedding.length, "Embedding should have 512 dimensions");

        // List of other photos to verify against the registered embedding
        String[] testPhotos = {
            "examples/WhatsApp Image 2026-06-22 at 15.20.20 (1).jpeg",
            "examples/WhatsApp Image 2026-06-22 at 15.20.21.jpeg",
            "examples/WhatsApp Image 2026-06-22 at 15.20.21 (1).jpeg",
            "examples/WhatsApp Image 2026-06-22 at 15.20.21 (2).jpeg"
        };

        for (String photoPath : testPhotos) {
            byte[] photoBytes;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(photoPath)) {
                assertNotNull(is, "Test photo not found in classpath: " + photoPath);
                photoBytes = is.readAllBytes();
            }

            // Extract face embedding for the test photo using the real model
            float[] testEmbedding = faceBiometricsService.extractFaceEmbedding(photoBytes, photoPath);
            assertNotNull(testEmbedding);
            assertEquals(512, testEmbedding.length);

            // Compute similarity
            double similarity = faceBiometricsService.calculateCosineSimilarity(registeredEmbedding, testEmbedding);
            boolean isMatch = faceBiometricsService.isMatch(similarity);

            System.out.println("Comparing registered face with " + photoPath + ": similarity = " + similarity + ", isMatch = " + isMatch);

            // Assert that they match!
            assertTrue(isMatch, "Photo " + photoPath + " should match the registered user (similarity: " + similarity + ", threshold: " + faceBiometricsService.getThreshold() + ")");
        }
    }

    @Test
    public void testBiometricsMatchFamousPeople() throws Exception {
        Object[][] famousPairs = {
            {"Barack Obama", "22222222222", "examples/obama1.jpg", "examples/obama2.jpg", 0.60},
            {"Joe Biden", "33333333333", "examples/biden.jpg", "examples/biden1.jpg", 0.60},
            {"Elon Musk", "55555555555", "examples/musk1.jpg", "examples/musk2.jpg", 0.60},
            {"Lionel Messi", "66666666666", "examples/messi1.jpg", "examples/messi2.jpg", 0.60},
            {"Kana", "11111111111", "examples/kana1.jpg", "examples/kana2.jpg", 0.50}
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
}
