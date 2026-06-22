package com.batistell.faceregistry.controller;

import com.batistell.faceregistry.dto.UserDTOs.*;
import com.batistell.faceregistry.exception.CustomExceptions.*;
import com.batistell.faceregistry.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Habilita chamadas do Angular (geralmente localhost:4200) sem bloqueio CORS
public class UserController {

    private final UserService userService;

    /**
     * Cadastro individual.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> createUser(
            @RequestParam("cpf") String cpf,
            @RequestParam("name") String name,
            @RequestParam("photo") MultipartFile photoFile) throws IOException {
        
        log.info("Recebida requisição para cadastrar usuário. CPF: {}, Nome: {}", cpf, name);
        if (photoFile == null || photoFile.isEmpty()) {
            throw new InvalidImageException("A foto de perfil é obrigatória no cadastro.");
        }
        validateFileFormat(photoFile);

        UserResponse response = userService.createUser(
                cpf, name, photoFile.getBytes(), photoFile.getOriginalFilename()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Atualização de cadastro (nome e/ou foto).
     */
    @PutMapping(value = "/{cpf}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable("cpf") String cpf,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "photo", required = false) MultipartFile photoFile) throws IOException {
        
        log.info("Recebida requisição para atualizar usuário CPF: {}", cpf);
        validateFileFormat(photoFile);
        byte[] photoBytes = (photoFile != null && !photoFile.isEmpty()) ? photoFile.getBytes() : null;
        String filename = photoFile != null ? photoFile.getOriginalFilename() : null;

        UserResponse response = userService.updateUser(cpf, name, photoBytes, filename);
        return ResponseEntity.ok(response);
    }

    /**
     * Exclusão de usuário.
     */
    @DeleteMapping("/{cpf}")
    public ResponseEntity<Void> deleteUser(@PathVariable("cpf") String cpf) {
        log.info("Recebida requisição para excluir usuário CPF: {}", cpf);
        userService.deleteUser(cpf);
        return ResponseEntity.noContent().build();
    }

    /**
     * Busca de detalhes de um usuário.
     */
    @GetMapping("/{cpf}")
    public ResponseEntity<UserResponse> getUserByCpf(@PathVariable("cpf") String cpf) {
        log.info("Recebida busca por CPF: {}", cpf);
        UserResponse response = userService.getUserByCpf(cpf);
        return ResponseEntity.ok(response);
    }

    /**
     * Listagem de todos os usuários.
     */
    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        log.info("Recebida listagem de todos os usuários.");
        List<UserResponse> response = userService.getAllUsers();
        return ResponseEntity.ok(response);
    }

    /**
     * Cadastro concorrente em lote (Batch Upload).
     */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<UserResponse>> createUsersBatch(
            @RequestParam("cpfs") List<String> cpfs,
            @RequestParam("names") List<String> names,
            @RequestParam("photos") List<MultipartFile> photoFiles) {
        
        log.info("Recebido lote para processamento concorrente. Quantidade: {}", cpfs.size());

        if (cpfs.size() != names.size() || cpfs.size() != photoFiles.size()) {
            throw new IllegalArgumentException("A quantidade de CPFs, Nomes e Fotos deve ser idêntica.");
        }

        for (MultipartFile file : photoFiles) {
            validateFileFormat(file);
        }

        List<UserBatchRequest> requests = new ArrayList<>();
        for (int i = 0; i < cpfs.size(); i++) {
            MultipartFile file = photoFiles.get(i);
            try {
                requests.add(new UserBatchRequest(
                        cpfs.get(i),
                        names.get(i),
                        file.getBytes(),
                        file.getOriginalFilename()
                ));
            } catch (IOException e) {
                throw new InvalidImageException("Erro ao ler conteúdo do arquivo da foto do usuário no índice " + i);
            }
        }

        List<UserResponse> response = userService.createUsersBatch(requests);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Verificação biometria facial (1:1).
     */
    @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VerificationResponse> verifyFace(
            @RequestParam("cpf") String cpf,
            @RequestParam("photo") MultipartFile photoFile) throws IOException {
        
        log.info("Recebida verificação facial (1:1) para o CPF: {}", cpf);
        if (photoFile == null || photoFile.isEmpty()) {
            throw new InvalidImageException("A foto a ser verificada é obrigatória.");
        }
        validateFileFormat(photoFile);

        VerificationResponse response = userService.verifyFace(
                cpf, photoFile.getBytes(), photoFile.getOriginalFilename()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Identificação biometria facial (1:n).
     */
    @PostMapping(value = "/identify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IdentificationResponse> identifyFace(
            @RequestParam("photo") MultipartFile photoFile) throws IOException {
        
        log.info("Recebida identificação facial (1:n) a partir de nova foto.");
        if (photoFile == null || photoFile.isEmpty()) {
            throw new InvalidImageException("A foto a ser identificada é obrigatória.");
        }
        validateFileFormat(photoFile);

        IdentificationResponse response = userService.identifyFace(
                photoFile.getBytes(), photoFile.getOriginalFilename()
        );
        return ResponseEntity.ok(response);
    }

    private void validateFileFormat(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new InvalidImageException("Nome do arquivo inválido.");
        }
        String lowerFilename = filename.toLowerCase();
        if (!lowerFilename.endsWith(".jpg") && !lowerFilename.endsWith(".jpeg") && !lowerFilename.endsWith(".png")) {
            throw new InvalidImageException("Tipo de arquivo não permitido (" + filename + "). Apenas imagens JPG, JPEG e PNG são aceitas.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/jpg")) {
            throw new InvalidImageException("Tipo de conteúdo de imagem inválido (" + contentType + "). Apenas JPG, JPEG e PNG são aceitas.");
        }
    }
}
