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
import java.util.Set;

/**
 * Controlador REST para gerenciamento de usuários e biometria facial.
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Habilita chamadas do Angular (geralmente localhost:4200) sem bloqueio CORS
public class UserController {

    private final UserService userService;

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

    @DeleteMapping("/{cpf}")
    public ResponseEntity<Void> deleteUser(@PathVariable("cpf") String cpf) {
        log.info("Recebida requisição para excluir usuário CPF: {}", cpf);
        userService.deleteUser(cpf);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{cpf}")
    public ResponseEntity<UserResponse> getUserByCpf(@PathVariable("cpf") String cpf) {
        log.info("Recebida busca por CPF: {}", cpf);
        UserResponse response = userService.getUserByCpf(cpf);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {
        log.info("Recebida listagem de todos os usuários com filtros. Search: {}, StartDate: {}, EndDate: {}", search, startDate, endDate);
        List<UserResponse> response = userService.searchUsers(search, startDate, endDate);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/duplicates")
    public ResponseEntity<List<DuplicatePairResponse>> getDuplicateUsers() {
        log.info("Recebida busca por rostos duplicados no banco.");
        List<DuplicatePairResponse> response = userService.findDuplicateUsers();
        return ResponseEntity.ok(response);
    }

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

    // Valida formato de arquivo de imagem (extensão e Content-Type MIME)
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".tiff", ".tif", ".avif"
    );

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp",
            "image/bmp", "image/gif", "image/tiff", "image/avif"
    );

    private void validateFileFormat(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new InvalidImageException("Nome do arquivo inválido.");
        }
        String lowerFilename = filename.toLowerCase();
        boolean validExtension = ALLOWED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
        if (!validExtension) {
            throw new InvalidImageException("Tipo de arquivo não permitido (" + filename + "). Formatos aceitos: JPG, JPEG, PNG, WebP, BMP, GIF, TIFF, AVIF.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidImageException("Tipo de conteúdo de imagem inválido (" + contentType + "). Formatos aceitos: JPG, JPEG, PNG, WebP, BMP, GIF, TIFF, AVIF.");
        }
    }
}
