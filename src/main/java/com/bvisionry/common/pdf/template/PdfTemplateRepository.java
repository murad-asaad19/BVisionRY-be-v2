package com.bvisionry.common.pdf.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PdfTemplateRepository extends JpaRepository<PdfTemplate, PdfTemplateKey> {
}
