package com.batistell.faceregistry;

import com.batistell.faceregistry.dto.UserDTOs.UserResponse;
import com.batistell.faceregistry.exception.CustomExceptions.DuplicateCpfException;
import com.batistell.faceregistry.model.User;
import com.batistell.faceregistry.repository.UserRepository;
import com.batistell.faceregistry.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
public class UserConcurrencyTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    private byte[] tinyPngBytes;

    @BeforeEach
    public void setup() throws IOException {
        userRepository.deleteAll();
        
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        tinyPngBytes = baos.toByteArray();
    }

    @Test
    public void testConcurrentUpdatesOnSameUser() throws InterruptedException, ExecutionException {
        // First register the user
        String cpf = "83992409821";
        userService.createUser(cpf, "Original Name", tinyPngBytes, "face.png");

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Callable<UserResponse>> tasks = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            tasks.add(() -> userService.updateUser(cpf, "Updated Name " + index, null, null));
        }

        // Run updates concurrently
        List<Future<UserResponse>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // All updates should complete successfully (no deadlock or unique constraint violation)
        for (Future<UserResponse> future : futures) {
            assertNotNull(future.get());
        }

        // Verify user exists and name matches one of the updates
        User updatedUser = userRepository.findByCpf(cpf).orElseThrow();
        assertTrue(updatedUser.getName().startsWith("Updated Name "));
    }

    @Test
    public void testConcurrentRegistrationDuplicateCpf() throws InterruptedException {
        String cpf = "13859287605";
        int numThreads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                latch.await(); // wait for signal to start concurrently
                userService.createUser(cpf, "Name " + index, tinyPngBytes, "face.png");
                return null;
            }));
        }

        latch.countDown(); // trigger concurrency
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // One and only one registration should succeed, the others must throw DuplicateCpfException/DataIntegrityViolationException
        int successCount = 0;
        int failureCount = 0;
        for (Future<Void> future : futures) {
            try {
                future.get();
                successCount++;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                assertTrue(cause instanceof DuplicateCpfException || cause instanceof org.springframework.dao.DataIntegrityViolationException);
                failureCount++;
            }
        }

        assertEquals(1, successCount, "Somente um cadastro de CPF duplicado deve ser bem-sucedido");
        assertEquals(numThreads - 1, failureCount);
    }
}
