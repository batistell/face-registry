package com.batistell.faceregistry.repository;

import com.batistell.faceregistry.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.batistell.faceregistry.dto.UserLightweight;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByCpf(String cpf);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.cpf = :cpf")
    Optional<User> findByCpfWithLock(@Param("cpf") String cpf);
    
    @Query("SELECT new com.batistell.faceregistry.dto.UserLightweight(u.id, u.cpf, u.name, u.embeddingString) FROM User u")
    List<UserLightweight> findAllLightweight();
    
    boolean existsByCpf(String cpf);
    void deleteByCpf(String cpf);

    @Query("SELECT u FROM User u WHERE " +
           "(:searchTerm IS NULL OR :searchTerm = '' OR " +
           " LOWER(u.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " u.cpf LIKE CONCAT('%', :searchTerm, '%')) " +
           "AND (:startDate IS NULL OR u.createdAt >= :startDate) " +
           "AND (:endDate IS NULL OR u.createdAt <= :endDate) " +
           "ORDER BY u.createdAt DESC")
    List<User> searchUsers(
            @Param("searchTerm") String searchTerm,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate,
            org.springframework.data.domain.Pageable pageable
    );
}
