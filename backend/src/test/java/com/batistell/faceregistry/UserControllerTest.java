package com.batistell.faceregistry;

import com.batistell.faceregistry.repository.UserRepository;
import com.batistell.faceregistry.service.UserService;
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

    @Autowired
    private UserService userService;

    private byte[] tinyPngBytes;
    private byte[] tinyPngBytes2;

    @BeforeEach
    public void setup() throws IOException {
        userRepository.deleteAll();
        userService.initCache();
        
        // Cria uma imagem PNG de 1x1 pixel válida em memória para passar na decodificação de ImageIO
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        tinyPngBytes = baos.toByteArray();

        // Cria uma imagem PNG de 2x2 pixels válida para representar uma imagem diferente no batch upload
        BufferedImage img2 = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        ImageIO.write(img2, "png", baos2);
        tinyPngBytes2 = baos2.toByteArray();
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
                .param("cpf", "92479121305")
                .param("name", "Alice Test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cpf", is("92479121305")))
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
                .param("cpf", "64657075942")
                .param("name", "Bob"))
                .andExpect(status().isCreated());

        // Segundo cadastro com mesmo CPF
        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "64657075942")
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
                .param("cpf", "21603180001")
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
                .param("cpf", "92230775596")
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
                .param("cpf", "74394356644")
                .param("name", "John Doe"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/users/74394356644"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf", is("74394356644")))
                .andExpect(jsonPath("$.name", is("John Doe")));
    }

    @Test
    public void testGetUserByCpfNotFound() throws Exception {
        mockMvc.perform(get("/api/users/62079744844"))
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
                .param("cpf", "83992409821")
                .param("name", "Initial Name"))
                .andExpect(status().isCreated());

        // Update name
        mockMvc.perform(multipart("/api/users/83992409821")
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
                .param("cpf", "13859287605")
                .param("name", "Delete Me"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/users/13859287605"))
                .andExpect(status().isNoContent());

        // Confirm it's gone
        mockMvc.perform(get("/api/users/13859287605"))
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
                .param("cpf", "53141746001")
                .param("name", "Charlie"))
                .andExpect(status().isCreated());

        // Faz verificação 1:1 com exatamente a mesma foto (deve ter similaridade 1.0)
        mockMvc.perform(multipart("/api/users/verify")
                .file(photoPartA)
                .param("cpf", "53141746001"))
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
                .param("cpf", "10200172956")
                .param("name", "John Identification"))
                .andExpect(status().isCreated());

        // Identifica usando a mesma foto (1:n)
        mockMvc.perform(multipart("/api/users/identify")
                .file(photoPart))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identified", is(true)))
                .andExpect(jsonPath("$.cpf", is("10200172956")))
                .andExpect(jsonPath("$.name", is("John Identification")));
    }

    @Test
    public void testBatchUploadSuccess() throws Exception {
        MockMultipartFile photo1 = new MockMultipartFile("photos", "p1.png", MediaType.IMAGE_PNG_VALUE, tinyPngBytes);
        MockMultipartFile photo2 = new MockMultipartFile("photos", "p2.png", MediaType.IMAGE_PNG_VALUE, tinyPngBytes2);

        mockMvc.perform(multipart("/api/users/batch")
                .file(photo1)
                .file(photo2)
                .param("cpfs", "64428618484", "42463341890")
                .param("names", "Batch User 1", "Batch User 2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cpf", is("64428618484")))
                .andExpect(jsonPath("$[1].cpf", is("42463341890")));
    }

    @Test
    public void testCreateUserInvalidCpfFailure() throws Exception {
        MockMultipartFile photoPart = new MockMultipartFile(
                "photo",
                "face_photo.png",
                MediaType.IMAGE_PNG_VALUE,
                tinyPngBytes
        );

        mockMvc.perform(multipart("/api/users")
                .file(photoPart)
                .param("cpf", "11111111111")
                .param("name", "Invalid CPF Person"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CPF inválido")));
    }
}
