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
}
