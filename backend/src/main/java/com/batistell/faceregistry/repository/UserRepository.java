package com.batistell.faceregistry.repository;

import com.batistell.faceregistry.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByCpf(String cpf);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.cpf = :cpf")
    Optional<User> findByCpfWithLock(@Param("cpf") String cpf);
    
    boolean existsByCpf(String cpf);
    void deleteByCpf(String cpf);
}
