package com.bvisionry.reporting.service;

import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;

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
    private final PdfRenderer pdfRenderer;

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
     * @param redactor Anonymisation for this report: scrubs the member's name out
     *                 of the AI narratives (the AI writes the name into the prose,
     *                 so blanking the cover label alone would still leak it) and,
     *                 when {@link NarrativeRedactor#isAnonymized() anonymised},
     *                 drops the Personal Information section entirely
     * @return PDF bytes
     */
    public byte[] generateReport(UUID submissionId, String participantName,
                                 NarrativeRedactor redactor) {
        // Redact the member's name out of every narrative field once, up front
        // (a no-op when names are shown), so the writes below never repeat it.
        MemberResultsResponse results = memberResultsService.getResults(submissionId).redacted(redactor);

        // Build per-pillar detail list for pillar pages: redact, then strip qids.
        List<PillarDetailResponse> pillarDetails = results.pillarScores().stream()
                .map(ps -> memberResultsService.getPillarDetail(submissionId, ps.pillarId()).redacted(redactor))
                .map(this::stripQids)
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
        // Personal info is the member's general info (name, contact, DOB, …) —
        // exactly what anonymisation suppresses, so it is omitted when names are
        // hidden. The template's not-empty guard hides the section on an empty list.
        ctx.setVariable("personalInfo",
                redactor.isAnonymized() || results.personalInfo() == null ? List.of() : results.personalInfo());

        // Render the branded PDF via the shared renderer (fonts + brand imagery
        // are injected centrally).
        byte[] pdf = pdfRenderer.renderTemplate("pdf-report", ctx);
        log.info("Generated PDF report for submission {} ({} bytes)", submissionId, pdf.length);
        return pdf;
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

    /**
     * Strip inline qid references from an already-redacted pillar detail. The
     * name scrubbing happens in {@link PillarDetailResponse#redacted}; qid removal
     * is PDF-only, so it stays here.
     */
    private PillarDetailResponse stripQids(PillarDetailResponse p) {
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
                p.selfAssessmentGap(),
                p.aiFailed()
        );
    }
}
