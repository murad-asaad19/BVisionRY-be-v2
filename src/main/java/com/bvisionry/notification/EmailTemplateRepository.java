package com.bvisionry.notification;

import com.bvisionry.notification.entity.EmailTemplate;
import com.bvisionry.notification.entity.EmailTemplateKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, EmailTemplateKey> {
}
