package com.batistell.faceregistry.service;

import com.batistell.faceregistry.dto.UserDTOs.*;
import com.batistell.faceregistry.exception.CustomExceptions.*;
import com.batistell.faceregistry.model.User;
import com.batistell.faceregistry.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final FaceBiometricsService faceBiometricsService;

    // Executor usando Virtual Threads para alta performance e concorrência no processamento de imagens
    private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Cadastra um novo usuário no banco com biometria calculada.
     */
    @Transactional
    public UserResponse createUser(String cpf, String name, byte[] photo, String filename) {
        String cleanCpf = cleanAndValidateCpf(cpf);
        if (userRepository.existsByCpf(cleanCpf)) {
            throw new DuplicateCpfException("CPF " + cleanCpf + " já cadastrado no sistema.");
        }

        float[] embedding = faceBiometricsService.extractFaceEmbedding(photo, filename);
        checkForDuplicateFace(embedding, null);

        User user = User.builder()
                .cpf(cleanCpf)
                .name(name)
                .photo(photo)
                .build();
        user.setEmbedding(embedding);

        User saved = userRepository.save(user);
        return toResponse(saved);
    }

    /**
     * Atualiza o cadastro de um usuário com trava pessimista para concorrência de escritas.
     */
    @Transactional
    public UserResponse updateUser(String cpf, String name, byte[] photo, String filename) {
        String cleanCpf = cleanAndValidateCpf(cpf);
        // Busca com bloqueio de escrita pessimista para evitar concorrência de fotos simultâneas
        User user = userRepository.findByCpfWithLock(cleanCpf)
                .orElseThrow(() -> new UserNotFoundException("Usuário com CPF " + cleanCpf + " não encontrado para atualização."));

        if (name != null && !name.trim().isEmpty()) {
            user.setName(name);
        }

        if (photo != null && photo.length > 0) {
            float[] embedding = faceBiometricsService.extractFaceEmbedding(photo, filename);
            checkForDuplicateFace(embedding, cleanCpf);
            user.setPhoto(photo);
            user.setEmbedding(embedding);
        }

        User saved = userRepository.save(user);
        return toResponse(saved);
    }

    /**
     * Deleta um usuário.
     */
    @Transactional
    public void deleteUser(String cpf) {
        String cleanCpf = cleanAndValidateCpf(cpf);
        User user = userRepository.findByCpf(cleanCpf)
                .orElseThrow(() -> new UserNotFoundException("Usuário com CPF " + cleanCpf + " não encontrado."));
        userRepository.delete(user);
    }

    /**
     * Busca um usuário pelo CPF.
     */
    @Transactional(readOnly = true)
    public UserResponse getUserByCpf(String cpf) {
        String cleanCpf = cleanAndValidateCpf(cpf);
        User user = userRepository.findByCpf(cleanCpf)
                .orElseThrow(() -> new UserNotFoundException("Usuário com CPF " + cleanCpf + " não encontrado."));
        return toResponse(user);
    }

    /**
     * Lista todos os usuários cadastrados.
     */
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Cadastro concorrente de lote (batch) de usuários com rollback total em caso de erros.
     */
    @Transactional
    public List<UserResponse> createUsersBatch(List<UserBatchRequest> requests) {
        log.info("Iniciando processamento em lote para {} usuários...", requests.size());

        // Processa as imagens e gera embeddings em paralelo usando Virtual Threads
        List<CompletableFuture<User>> futures = requests.stream()
                .map(req -> CompletableFuture.supplyAsync(() -> {
                    String cleanCpf = cleanAndValidateCpf(req.cpf());
                    log.info("[Thread: {}] Processando biometria do CPF {}", Thread.currentThread().getName(), cleanCpf);
                    
                    if (userRepository.existsByCpf(cleanCpf)) {
                        throw new DuplicateCpfException("CPF " + cleanCpf + " já cadastrado.");
                    }

                    float[] embedding = faceBiometricsService.extractFaceEmbedding(req.photo(), req.filename());
                    checkForDuplicateFace(embedding, cleanCpf);

                    User user = User.builder()
                            .cpf(cleanCpf)
                            .name(req.name())
                            .photo(req.photo())
                            .build();
                    user.setEmbedding(embedding);
                    return user;
                }, virtualThreadExecutor))
                .toList();

        // Aguarda todas as threads terminarem
        List<User> usersToSave;
        try {
            usersToSave = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        } catch (Exception e) {
            log.error("Falha ao processar lote biometria/validação: {}", e.getMessage());
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("Erro ao processar o lote de cadastro.", e);
        }

        // Verifica duplicidade facial dentro do próprio lote
        for (int i = 0; i < usersToSave.size(); i++) {
            User u1 = usersToSave.get(i);
            for (int j = i + 1; j < usersToSave.size(); j++) {
                User u2 = usersToSave.get(j);
                double similarity = faceBiometricsService.calculateCosineSimilarity(u1.getEmbedding(), u2.getEmbedding());
                if (faceBiometricsService.isMatch(similarity)) {
                    throw new DuplicateFaceException("Duplicidade detectada no lote de envio: As faces de '" 
                            + u1.getName() + "' (CPF: " + formatCpf(u1.getCpf()) + ") e '" 
                            + u2.getName() + "' (CPF: " + formatCpf(u2.getCpf()) + ") pertencem à mesma pessoa.");
                }
            }
        }

        // Salva todos no banco de forma atômica (se der erro em um, o Spring rola de volta a transação inteira)
        List<User> saved = userRepository.saveAll(usersToSave);
        log.info("Lote de {} cadastros persistido com sucesso.", saved.size());
        
        return saved.stream().map(this::toResponse).toList();
    }

    /**
     * Verificação facial 1:1.
     */
    @Transactional(readOnly = true)
    public VerificationResponse verifyFace(String cpf, byte[] photoBytes, String filename) {
        String cleanCpf = cleanAndValidateCpf(cpf);
        User user = userRepository.findByCpf(cleanCpf)
                .orElseThrow(() -> new UserNotFoundException("Usuário com CPF " + cleanCpf + " não cadastrado."));

        float[] targetEmbedding = faceBiometricsService.extractFaceEmbedding(photoBytes, filename);
        float[] storedEmbedding = user.getEmbedding();

        double similarity = faceBiometricsService.calculateCosineSimilarity(targetEmbedding, storedEmbedding);
        boolean match = faceBiometricsService.isMatch(similarity);

        return new VerificationResponse(match, similarity, faceBiometricsService.getThreshold());
    }

    /**
     * Identificação facial 1:n com processamento paralelo de similaridade.
     */
    @Transactional(readOnly = true)
    public IdentificationResponse identifyFace(byte[] photoBytes, String filename) {
        float[] targetEmbedding = faceBiometricsService.extractFaceEmbedding(photoBytes, filename);
        List<User> allUsers = userRepository.findAll();

        if (allUsers.isEmpty()) {
            return new IdentificationResponse(false, null, null, null, 0.0, faceBiometricsService.getThreshold());
        }

        // Compara em paralelo usando parallelStream
        MatchResult bestMatch = allUsers.parallelStream()
                .map(user -> {
                    double similarity = faceBiometricsService.calculateCosineSimilarity(targetEmbedding, user.getEmbedding());
                    return new MatchResult(user, similarity);
                })
                .max(Comparator.comparingDouble(MatchResult::similarity))
                .orElse(null);

        if (bestMatch != null && faceBiometricsService.isMatch(bestMatch.similarity())) {
            User matchedUser = bestMatch.user();
            String base64 = Base64.getEncoder().encodeToString(matchedUser.getPhoto());
            return new IdentificationResponse(
                    true,
                    matchedUser.getCpf(),
                    matchedUser.getName(),
                    base64,
                    bestMatch.similarity(),
                    faceBiometricsService.getThreshold()
            );
        }

        return new IdentificationResponse(
                false,
                null,
                null,
                null,
                bestMatch != null ? bestMatch.similarity() : 0.0,
                faceBiometricsService.getThreshold()
        );
    }

    // --- Helper Métodos ---

    private UserResponse toResponse(User user) {
        String base64 = Base64.getEncoder().encodeToString(user.getPhoto());
        return new UserResponse(
                user.getId(),
                user.getCpf(),
                user.getName(),
                base64,
                user.getCreatedAt()
        );
    }

    private String cleanAndValidateCpf(String cpf) {
        if (cpf == null || cpf.trim().isEmpty()) {
            throw new InvalidCpfException("O CPF é obrigatório.");
        }

        String cleanCpf = cpf.replaceAll("\\D", "");

        if (cleanCpf.length() != 11) {
            throw new InvalidCpfException("O CPF deve conter exatamente 11 dígitos numéricos.");
        }

        if (cleanCpf.matches("(\\d)\\1{10}")) {
            throw new InvalidCpfException("CPF inválido (dígitos repetidos).");
        }

        try {
            int sum = 0;
            for (int i = 0; i < 9; i++) {
                sum += (cleanCpf.charAt(i) - '0') * (10 - i);
            }
            int r1 = sum % 11;
            int d1 = (r1 < 2) ? 0 : (11 - r1);
            if (cleanCpf.charAt(9) - '0' != d1) {
                throw new InvalidCpfException("CPF inválido.");
            }

            sum = 0;
            for (int i = 0; i < 10; i++) {
                sum += (cleanCpf.charAt(i) - '0') * (11 - i);
            }
            int r2 = sum % 11;
            int d2 = (r2 < 2) ? 0 : (11 - r2);
            if (cleanCpf.charAt(10) - '0' != d2) {
                throw new InvalidCpfException("CPF inválido.");
            }
        } catch (Exception e) {
            throw new InvalidCpfException("CPF inválido.");
        }

        return cleanCpf;
    }

    @Transactional(readOnly = true)
    public List<DuplicatePairResponse> findDuplicateUsers() {
        List<User> allUsers = userRepository.findAll();
        List<DuplicatePairResponse> duplicates = new java.util.ArrayList<>();
        double threshold = faceBiometricsService.getThreshold();

        for (int i = 0; i < allUsers.size(); i++) {
            User u1 = allUsers.get(i);
            for (int j = i + 1; j < allUsers.size(); j++) {
                User u2 = allUsers.get(j);
                double similarity = faceBiometricsService.calculateCosineSimilarity(u1.getEmbedding(), u2.getEmbedding());
                if (similarity >= threshold) {
                    duplicates.add(new DuplicatePairResponse(toResponse(u1), toResponse(u2), similarity));
                }
            }
        }
        return duplicates;
    }

    private void checkForDuplicateFace(float[] embedding, String excludeCpf) {
        List<User> allUsers = userRepository.findAll();
        if (allUsers.isEmpty()) {
            return;
        }

        Optional<MatchResult> duplicateMatch = allUsers.parallelStream()
                .filter(user -> excludeCpf == null || !user.getCpf().equals(excludeCpf))
                .map(user -> {
                    double similarity = faceBiometricsService.calculateCosineSimilarity(embedding, user.getEmbedding());
                    return new MatchResult(user, similarity);
                })
                .filter(result -> faceBiometricsService.isMatch(result.similarity()))
                .max(Comparator.comparingDouble(MatchResult::similarity));

        if (duplicateMatch.isPresent()) {
            User matchedUser = duplicateMatch.get().user();
            throw new DuplicateFaceException("Esta face já está cadastrada no sistema sob o CPF: " 
                    + formatCpf(matchedUser.getCpf()) + " (Nome: " + matchedUser.getName() + ").");
        }
    }

    private String formatCpf(String cpf) {
        if (cpf == null || cpf.length() != 11) return cpf;
        return cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9, 11);
    }

    private record MatchResult(User user, double similarity) {}
}
