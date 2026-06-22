package com.batistell.faceregistry;

import com.batistell.faceregistry.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    private byte[] tinyPngBytes;

    @BeforeEach
    public void setup() throws IOException {
        userRepository.deleteAll();
        
        // Cria uma imagem PNG de 1x1 pixel válida em memória para passar na decodificação de ImageIO
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        tinyPngBytes = baos.toByteArray();
    }

    @Test
    public void testCreateUserSuccess() throws Exception {
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "face_photo.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "12345678901")
                .param("name", "Alice Test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cpf", is("12345678901")))
                .andExpect(jsonPath("$.name", is("Alice Test")))
                .andExpect(jsonPath("$.photoBase64", notNullValue()));
    }

    @Test
    public void testCreateUserDuplicateCpfFailure() throws Exception {
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "face_photo.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        // Primeiro cadastro
        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "11122233344")
                .param("name", "Bob"))
                .andExpect(status().isCreated());

        // Segundo cadastro com mesmo CPF
        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "11122233344")
                .param("name", "Duplicate Bob"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("já cadastrado")));
    }

    @Test
    public void testCreateUserNoFaceFailure() throws Exception {
        // Mock name of file has "noface" to trigger simulation exception
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "noface_photo.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "99988877766")
                .param("name", "No Face Person"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("rosto detectado")));
    }

    @Test
    public void testCreateUserMultipleFacesFailure() throws Exception {
        // Mock name of file has "multiple" to trigger simulation exception
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "multiple_faces.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "77766655544")
                .param("name", "Group Person"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Múltiplos rostos detectados")));
    }

    @Test
    public void testGetUserByCpfSuccess() throws Exception {
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "face_photo.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "98765432100")
                .param("name", "John Doe"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/users/98765432100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf", is("98765432100")))
                .andExpect(jsonPath("$.name", is("John Doe")));
    }

    @Test
    public void testGetUserByCpfNotFound() throws Exception {
        mockMvc.perform(get("/api/users/00000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("não encontrado")));
    }

    @Test
    public void testUpdateUserSuccess() throws Exception {
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "face_photo.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "55555555555")
                .param("name", "Initial Name"))
                .andExpect(status().isCreated());

        // Update name
        mockMvc.perform(multipart("/api/users/55555555555")
                .param("name", "Updated Name")
                .with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Updated Name")));
    }

    @Test
    public void testDeleteUserSuccess() throws Exception {
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "face.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "44444444444")
                .param("name", "Delete Me"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/users/44444444444"))
                .andExpect(status().isNoContent());

        // Confirm it's gone
        mockMvc.perform(get("/api/users/44444444444"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testVerifyFaceSuccess() throws Exception {
        // Cadastra um usuário com a foto 'A'
        MockMultipartFile photoPartA = new MockMultipartFile(
                "photo",
                "photo_a.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPartA)
                .param("cpf", "33333333333")
                .param("name", "Charlie"))
                .andExpect(status().isCreated());

        // Faz verificação 1:1 com exatamente a mesma foto (deve ter similaridade 1.0)
        mockMvc.perform(multipart("/api/users/verify")
                .file(photoPartA)
                .param("cpf", "33333333333"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match", is(true)))
                .andExpect(jsonPath("$.similarity", greaterThanOrEqualTo(0.99)));
    }

    @Test
    public void testIdentifyFaceSuccess() throws Exception {
        // Cadastra um usuário
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "john.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "22222222222")
                .param("name", "John Identification"))
                .andExpect(status().isCreated());

        // Identifica usando a mesma foto (1:n)
        mockMvc.perform(multipart("/api/users/identify")
                .file(photoPart))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identified", is(true)))
                .andExpect(jsonPath("$.cpf", is("22222222222")))
                .andExpect(jsonPath("$.name", is("John Identification")));
    }

    @Test
    public void testBatchUploadSuccess() throws Exception {
        MockMultipartFile photo1 = new MockMultipartFile("photos", "p1.png", MediaType.IMAGE_PNG_VALUE, tinyPngBytes);
        MockMultipartFile photo2 = new MockMultipartFile("photos", "p2.png", MediaType.IMAGE_PNG_VALUE, tinyPngBytes);

        mockMvc.perform(multipart("/api/users/batch")
                .file(photo1)
                .file(photo2)
                .param("cpfs", "88888888801", "88888888802")
                .param("names", "Batch User 1", "Batch User 2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cpf", is("88888888801")))
                .andExpect(jsonPath("$[1].cpf", is("88888888802")));
    }
}
