package com.bvisionry.reporting.service;

import com.bvisionry.common.exception.ReportGenerationException;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfReportService {

    private final MemberResultsService memberResultsService;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final TemplateEngine templateEngine;

    // Strips inline references like "(qid: <uuid>)", "qid: <uuid>", "(Q: <hex>)",
    // or "Q: <hex>" added by the upstream AI evaluator — they are useful for
    // traceability but should never surface to the end user.
    private static final Pattern QID_PATTERN =
            Pattern.compile("\\s*\\(?\\s*(?:qid|Q):\\s*[0-9a-fA-F-]{8,}\\s*\\)?",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Generate a branded PDF report for a submission.
     *
     * @param submissionId  The submission to generate the report for
     * @param participantName The name to display on the cover
     * @return PDF bytes
     */
    public byte[] generateReport(UUID submissionId, String participantName) {
        MemberResultsResponse results = memberResultsService.getResults(submissionId);

        // Build per-pillar detail list for pillar pages, stripping qid references.
        List<PillarDetailResponse> pillarDetails = results.pillarScores().stream()
                .map(ps -> memberResultsService.getPillarDetail(submissionId, ps.pillarId()))
                .map(this::sanitizePillar)
                .toList();

        // Derive overall category from score
        String overallCategory = deriveCategory(results.overallScore().intValue());

        // Build Thymeleaf context
        Context ctx = new Context();
        ctx.setVariable("participantName", participantName);
        ctx.setVariable("assessmentTitle", results.pipelineName());
        ctx.setVariable("reportDate", LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy")));
        ctx.setVariable("overallScore", results.overallScore().intValue());
        ctx.setVariable("overallCategory", overallCategory);
        ctx.setVariable("summaryNarrative", stripQids(results.summaryNarrative()));
        ctx.setVariable("pillarScores", results.pillarScores());
        ctx.setVariable("pillarDetails", pillarDetails);
        ctx.setVariable("strengths", stripQids(results.strengths()));
        ctx.setVariable("developmentAreas", stripQids(results.developmentAreas()));
        ctx.setVariable("corePattern", stripQids(results.corePattern()));
        ctx.setVariable("movingForward", stripQids(results.movingForwardNarrative()));
        ctx.setVariable("personalInfo", results.personalInfo() == null ? List.of() : results.personalInfo());

        // Render HTML
        String html = templateEngine.process("pdf-report", ctx);

        // Convert to PDF
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();

            // Register Dosis fonts for brand consistency
            registerFont(renderer, "fonts/Dosis-Regular.ttf", "Dosis", false, false);
            registerFont(renderer, "fonts/Dosis-SemiBold.ttf", "Dosis", false, false);
            registerFont(renderer, "fonts/Dosis-Bold.ttf", "Dosis", true, false);

            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(os);
            log.info("Generated PDF report for submission {} ({} bytes)", submissionId, os.size());
            return os.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate PDF for submission {}: {}", submissionId, e.getMessage(), e);
            throw new ReportGenerationException("Member results PDF generation failed", e);
        }
    }

    private void registerFont(ITextRenderer renderer, String resourcePath, String family,
                              boolean bold, boolean italic) {
        try {
            var resource = getClass().getClassLoader().getResource(resourcePath);
            if (resource != null) {
                renderer.getFontResolver().addFont(
                        resource.toExternalForm(), family, com.lowagie.text.pdf.BaseFont.IDENTITY_H,
                        true, null);
            } else {
                log.warn("Font resource not found: {}", resourcePath);
            }
        } catch (Exception e) {
            log.warn("Failed to register font {}: {}", resourcePath, e.getMessage());
        }
    }

    private String deriveCategory(int score) {
        if (score >= 81) return "Elite Mindset";
        if (score >= 61) return "Strong Mindset";
        if (score >= 41) return "Emerging Mindset";
        if (score >= 21) return "Developing Mindset";
        return "Foundational Mindset";
    }

    private String stripQids(String text) {
        if (text == null) return null;
        return QID_PATTERN.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private List<String> stripQids(List<String> texts) {
        if (texts == null) return null;
        return texts.stream().map(this::stripQids).toList();
    }

    private PillarDetailResponse sanitizePillar(PillarDetailResponse p) {
        return new PillarDetailResponse(
                p.pillarId(),
                p.pillarName(),
                p.iconKey(),
                p.scorePercentage(),
                p.maturityLabel(),
                stripQids(p.whatThisScoreMeans()),
                stripQids(p.whatsWorking()),
                stripQids(p.whatCanImprove()),
                stripQids(p.whyThisMattersForBusiness()),
                p.selfAssessmentGap()
        );
    }
}
