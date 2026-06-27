package com.batistell.faceregistry.service;

import com.batistell.faceregistry.dto.UserDTOs.*;
import com.batistell.faceregistry.exception.CustomExceptions.*;
import com.batistell.faceregistry.model.User;
import com.batistell.faceregistry.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final FaceBiometricsService faceBiometricsService;

    // Executor para Virtual Threads
    private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Cache de assinaturas biométricas em memória
    private final List<UserCacheItem> userBiometricCache = new ArrayList<>();
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

    private record UserCacheItem(java.util.UUID id, String cpf, String name, float[] embedding) {}

    @jakarta.annotation.PostConstruct
    public void initCache() {
        log.info("Inicializando cache biométrico em memória...");
        List<com.batistell.faceregistry.dto.UserLightweight> lightUsers = userRepository.findAllLightweight();
        cacheLock.writeLock().lock();
        try {
            userBiometricCache.clear();
            for (com.batistell.faceregistry.dto.UserLightweight lu : lightUsers) {
                float[] embedding = parseEmbedding(lu.embeddingString());
                embedding = faceBiometricsService.normalize(embedding);
                userBiometricCache.add(new UserCacheItem(lu.id(), lu.cpf(), lu.name(), embedding));
            }
            log.info("Cache biométrico inicializado com {} usuários.", userBiometricCache.size());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private float[] parseEmbedding(String embStr) {
        if (embStr == null || embStr.isEmpty()) return null;
        String[] parts = embStr.split(";");
        float[] emb = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            emb[i] = Float.parseFloat(parts[i]);
        }
        return emb;
    }

    // Cadastra novo usuário com validação de duplicados
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

        // Atualiza cache
        cacheLock.writeLock().lock();
        try {
            userBiometricCache.add(new UserCacheItem(saved.getId(), saved.getCpf(), saved.getName(), saved.getEmbedding()));
        } finally {
            cacheLock.writeLock().unlock();
        }

        return toResponse(saved);
    }

    // Atualiza dados e foto com lock pessimista
    @Transactional
    public UserResponse updateUser(String cpf, String name, byte[] photo, String filename) {
        String cleanCpf = cleanAndValidateCpf(cpf);
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

        // Atualiza cache
        cacheLock.writeLock().lock();
        try {
            userBiometricCache.removeIf(item -> item.cpf().equals(cleanCpf));
            userBiometricCache.add(new UserCacheItem(saved.getId(), saved.getCpf(), saved.getName(), saved.getEmbedding()));
        } finally {
            cacheLock.writeLock().unlock();
        }

        return toResponse(saved);
    }

    // Deleta usuário e remove do cache
    @Transactional
    public void deleteUser(String cpf) {
        String cleanCpf = cleanAndValidateCpf(cpf);
        User user = userRepository.findByCpf(cleanCpf)
                .orElseThrow(() -> new UserNotFoundException("Usuário com CPF " + cleanCpf + " não encontrado."));
        userRepository.delete(user);

        // Atualiza cache
        cacheLock.writeLock().lock();
        try {
            userBiometricCache.removeIf(item -> item.cpf().equals(cleanCpf));
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    @Transactional(readOnly = true)
    public UserResponse getUserByCpf(String cpf) {
        String cleanCpf = cleanAndValidateCpf(cpf);
        User user = userRepository.findByCpf(cleanCpf)
                .orElseThrow(() -> new UserNotFoundException("Usuário com CPF " + cleanCpf + " não encontrado."));
        return toResponse(user);
    }

    // Lista os usuários cadastrados limitando o resultado (mantendo compatibilidade)
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return searchUsers(null, null, null, 0, 100);
    }

    // Busca de usuários com filtros de pesquisa e data de criação ordenados por data decrescente
    @Transactional(readOnly = true)
    public List<UserResponse> searchUsers(String searchTerm, String startDateStr, String endDateStr, int page, int size) {
        LocalDateTime startDate = null;
        LocalDateTime endDate = null;

        if (startDateStr != null && !startDateStr.trim().isEmpty()) {
            try {
                startDate = java.time.LocalDate.parse(startDateStr).atStartOfDay();
            } catch (Exception e) {
                log.warn("Falha ao converter data inicial ({}): {}", startDateStr, e.getMessage());
            }
        }

        if (endDateStr != null && !endDateStr.trim().isEmpty()) {
            try {
                endDate = java.time.LocalDate.parse(endDateStr).atTime(23, 59, 59, 999999999);
            } catch (Exception e) {
                log.warn("Falha ao converter data final ({}): {}", endDateStr, e.getMessage());
            }
        }

        String processedSearch = (searchTerm != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);

        return userRepository.searchUsers(processedSearch, startDate, endDate, pageable).stream()
                .map(this::toResponse)
                .toList();
    }

    // Cadastro concorrente em lote
    @Transactional
    public List<UserResponse> createUsersBatch(List<UserBatchRequest> requests) {
        log.info("Iniciando processamento em lote para {} usuários...", requests.size());

        // Processa biometrias em paralelo via virtual threads
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

        // Valida duplicados no lote
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

        // Salva lote de forma atômica
        List<User> saved = userRepository.saveAll(usersToSave);
        log.info("Lote de {} cadastros persistido com sucesso.", saved.size());

        // Atualiza cache
        cacheLock.writeLock().lock();
        try {
            for (User u : saved) {
                userBiometricCache.removeIf(item -> item.cpf().equals(u.getCpf()));
                userBiometricCache.add(new UserCacheItem(u.getId(), u.getCpf(), u.getName(), u.getEmbedding()));
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
        
        return saved.stream().map(this::toResponse).toList();
    }

    // Verificação 1:1 via cache
    @Transactional(readOnly = true)
    public VerificationResponse verifyFace(String cpf, byte[] photoBytes, String filename) {
        String cleanCpf = cleanAndValidateCpf(cpf);
        float[] targetEmbedding = faceBiometricsService.extractFaceEmbedding(photoBytes, filename);
        
        UserCacheItem cachedUser = null;
        cacheLock.readLock().lock();
        try {
            for (UserCacheItem item : userBiometricCache) {
                if (item.cpf().equals(cleanCpf)) {
                    cachedUser = item;
                    break;
                }
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        if (cachedUser == null) {
            throw new UserNotFoundException("Usuário com CPF " + cleanCpf + " não cadastrado.");
        }

        double similarity = faceBiometricsService.calculateDotProduct(targetEmbedding, cachedUser.embedding());
        boolean match = faceBiometricsService.isMatch(similarity);

        return new VerificationResponse(match, similarity, faceBiometricsService.getThreshold());
    }

    // Identificação 1:N
    @Transactional(readOnly = true)
    public IdentificationResponse identifyFace(byte[] photoBytes, String filename) {
        float[] targetEmbedding = faceBiometricsService.extractFaceEmbedding(photoBytes, filename);
        return identifyFaceWithEmbedding(targetEmbedding);
    }

    // Busca paralela no cache
    public IdentificationResponse identifyFaceWithEmbedding(float[] targetEmbedding) {
        cacheLock.readLock().lock();
        try {
            if (userBiometricCache.isEmpty()) {
                return new IdentificationResponse(false, null, null, null, 0.0, faceBiometricsService.getThreshold());
            }

            int size = userBiometricCache.size();
            
            // Busca sequencial para bases pequenas
            if (size < 5000) {
                UserCacheItem bestItem = null;
                double bestSimilarity = -1.0;
                for (UserCacheItem item : userBiometricCache) {
                    double similarity = faceBiometricsService.calculateDotProduct(targetEmbedding, item.embedding());
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity;
                        bestItem = item;
                    }
                }
                return buildIdentificationResponse(bestItem, bestSimilarity);
            }

            // Busca paralela para bases grandes
            int numThreads = Runtime.getRuntime().availableProcessors();
            int chunkSize = (size + numThreads - 1) / numThreads;
            List<CompletableFuture<SearchResult>> futures = new ArrayList<>();
            
            for (int i = 0; i < numThreads; i++) {
                final int start = i * chunkSize;
                final int end = Math.min(start + chunkSize, size);
                if (start >= size) break;
                
                futures.add(CompletableFuture.supplyAsync(() -> {
                    UserCacheItem bestItem = null;
                    double bestSimilarity = -1.0;
                    for (int j = start; j < end; j++) {
                        UserCacheItem item = userBiometricCache.get(j);
                        double similarity = faceBiometricsService.calculateDotProduct(targetEmbedding, item.embedding());
                        if (similarity > bestSimilarity) {
                            bestSimilarity = similarity;
                            bestItem = item;
                        }
                    }
                    return new SearchResult(bestItem, bestSimilarity);
                }));
            }

            SearchResult bestResult = futures.stream()
                    .map(CompletableFuture::join)
                    .max(Comparator.comparingDouble(SearchResult::similarity))
                    .orElse(null);

            if (bestResult != null) {
                return buildIdentificationResponse(bestResult.item(), bestResult.similarity());
            }

            return new IdentificationResponse(false, null, null, null, 0.0, faceBiometricsService.getThreshold());
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    private IdentificationResponse buildIdentificationResponse(UserCacheItem bestItem, double bestSimilarity) {
        if (bestItem != null && faceBiometricsService.isMatch(bestSimilarity)) {
            User matchedUser = userRepository.findById(bestItem.id())
                    .orElseThrow(() -> new UserNotFoundException("Usuário com CPF " + bestItem.cpf() + " não encontrado no banco."));
            String base64 = Base64.getEncoder().encodeToString(matchedUser.getPhoto());
            return new IdentificationResponse(
                    true,
                    bestItem.cpf(),
                    bestItem.name(),
                    base64,
                    bestSimilarity,
                    faceBiometricsService.getThreshold()
            );
        }
        return new IdentificationResponse(
                false,
                null,
                null,
                null,
                bestItem != null ? bestSimilarity : 0.0,
                faceBiometricsService.getThreshold()
        );
    }

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

    private UserResponse toResponse(UserCacheItem item) {
        return new UserResponse(
                item.id(),
                item.cpf(),
                item.name(),
                null, // Sem foto para otimizar transferência
                LocalDateTime.now()
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

    // Auditoria de duplicados via cache
    @Transactional(readOnly = true)
    public List<DuplicatePairResponse> findDuplicateUsers() {
        List<DuplicatePairResponse> duplicates = new java.util.ArrayList<>();
        double threshold = faceBiometricsService.getThreshold();
        
        List<UserCacheItem> items = new ArrayList<>();
        cacheLock.readLock().lock();
        try {
            items.addAll(userBiometricCache);
        } finally {
            cacheLock.readLock().unlock();
        }

        for (int i = 0; i < items.size(); i++) {
            UserCacheItem u1 = items.get(i);
            for (int j = i + 1; j < items.size(); j++) {
                UserCacheItem u2 = items.get(j);
                double similarity = faceBiometricsService.calculateDotProduct(u1.embedding(), u2.embedding());
                if (similarity >= threshold) {
                    duplicates.add(new DuplicatePairResponse(toResponse(u1), toResponse(u2), similarity));
                }
            }
        }
        return duplicates;
    }

    // Valida se o rosto já existe na base
    private void checkForDuplicateFace(float[] embedding, String excludeCpf) {
        float[] normalizedEmb = faceBiometricsService.normalize(embedding);
        
        cacheLock.readLock().lock();
        try {
            if (userBiometricCache.isEmpty()) {
                return;
            }

            Optional<MatchResult> duplicateMatch = userBiometricCache.parallelStream()
                    .filter(item -> excludeCpf == null || !item.cpf().equals(excludeCpf))
                    .map(item -> {
                        double similarity = faceBiometricsService.calculateDotProduct(normalizedEmb, item.embedding());
                        return new MatchResult(item, similarity);
                      })
                    .filter(result -> faceBiometricsService.isMatch(result.similarity()))
                    .max(Comparator.comparingDouble(MatchResult::similarity));

            if (duplicateMatch.isPresent()) {
                UserCacheItem matchedItem = duplicateMatch.get().item();
                throw new DuplicateFaceException("Esta face já está cadastrada no sistema sob o CPF: " 
                        + formatCpf(matchedItem.cpf()) + " (Nome: " + matchedItem.name() + ").");
            }
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    private String formatCpf(String cpf) {
        if (cpf == null || cpf.length() != 11) return cpf;
        return cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9, 11);
    }

    private record MatchResult(UserCacheItem item, double similarity) {}
    private record SearchResult(UserCacheItem item, double similarity) {}
}
