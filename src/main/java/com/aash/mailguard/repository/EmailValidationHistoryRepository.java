package com.aash.mailguard.repository;

import com.aash.mailguard.entity.EmailValidationHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface EmailValidationHistoryRepository
        extends JpaRepository<EmailValidationHistory, Long> {
    @Transactional(readOnly = true)
    Slice<EmailValidationHistory> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}