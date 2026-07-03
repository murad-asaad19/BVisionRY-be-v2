package com.bvisionry.reporting.service;

import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PersonalInfoEntry;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import com.bvisionry.reporting.dto.PillarScoreSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberResultsExcelService {

    private final MemberResultsService memberResultsService;

    /**
     * @param redactor anonymisation for this report: scrubs the member's name out
     *                 of the AI narratives and, when
     *                 {@link NarrativeRedactor#isAnonymized() anonymised}, drops
     *                 the Personal information sheet (the member's general info:
     *                 name, contact, DOB, …) entirely — that section is exactly
     *                 what anonymisation suppresses
     */
    public byte[] generateReport(UUID submissionId, String participantName,
                                 NarrativeRedactor redactor) {
        MemberResultsResponse results = memberResultsService.getResults(submissionId);
        Map<UUID, PillarDetailResponse> pillarDetails =
                memberResultsService.getAllPillarDetails(submissionId);

        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            writeOverviewSheet(wb, participantName, results, redactor);
            if (!redactor.isAnonymized()) {
                writePersonalInformationSheet(wb, results.personalInfo());
            }
            writeHighlightsSheet(wb, results, redactor);
            for (PillarScoreSummary pillar : results.pillarScores()) {
                writePillarSheet(wb, pillar, pillarDetails.get(pillar.pillarId()), redactor);
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate member Excel report", e);
        }
    }

    /**
     * Renders the Personal-pillar answers as a label/value sheet. Skipped
     * entirely when the pipeline didn't collect any personal info — adding an
     * empty sheet would just add noise.
     */
    private void writePersonalInformationSheet(ExcelWorkbookBuilder wb,
                                                List<PersonalInfoEntry> personalInfo) {
        if (personalInfo == null || personalInfo.isEmpty()) return;
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Personal information");
        s.headers("Field", "Value");
        for (PersonalInfoEntry entry : personalInfo) {
            s.labeledRow(entry.label(), entry.value());
        }
        s.autoSize();
    }

    private void writeOverviewSheet(ExcelWorkbookBuilder wb, String participantName,
                                     MemberResultsResponse r, NarrativeRedactor redactor) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Overview");
        s.headers("Field", "Value");
        s.labeledRow("Participant", participantName);
        s.labeledRow("Pipeline", r.pipelineName());
        s.labeledRow("Overall score", ExcelWorkbookBuilder.formatPercent(r.overallScore()));
        s.labeledRow("Evaluated at", r.evaluatedAt());
        s.labeledRow("Summary narrative", redactor.redact(r.summaryNarrative()));
        if (r.corePattern() != null) {
            s.labeledRow("Core pattern", redactor.redact(r.corePattern()));
        }
        if (r.movingForwardNarrative() != null) {
            s.labeledRow("Moving forward", redactor.redact(r.movingForwardNarrative()));
        }
        s.autoSize();

        ExcelWorkbookBuilder.SheetBuilder scoresSheet = wb.newSheet("Pillar scores");
        scoresSheet.headers("Pillar", "Score", "Maturity");
        for (PillarScoreSummary p : r.pillarScores()) {
            scoresSheet.row(
                    p.pillarName(),
                    ExcelWorkbookBuilder.formatPercent(p.scorePercentage()),
                    p.maturityLabel());
        }
        scoresSheet.autoSize();
    }

    private void writeHighlightsSheet(ExcelWorkbookBuilder wb, MemberResultsResponse r,
                                      NarrativeRedactor redactor) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Highlights");
        s.headers("Strengths", "Development areas");

        List<String> strengths = redactor.redact(nullToEmpty(r.strengths()));
        List<String> dev = redactor.redact(nullToEmpty(r.developmentAreas()));
        int rowCount = Math.max(strengths.size(), dev.size());
        for (int i = 0; i < rowCount; i++) {
            s.row(
                    i < strengths.size() ? strengths.get(i) : "",
                    i < dev.size() ? dev.get(i) : ""
            );
        }
        s.autoSize();
    }

    private void writePillarSheet(ExcelWorkbookBuilder wb, PillarScoreSummary summary,
                                   PillarDetailResponse detail, NarrativeRedactor redactor) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet(summary.pillarName());
        s.headers("Field", "Value");
        s.labeledRow("Pillar", summary.pillarName());
        s.labeledRow("Score", ExcelWorkbookBuilder.formatPercent(summary.scorePercentage()));
        s.labeledRow("Maturity", summary.maturityLabel());
        if (detail != null) {
            if (detail.selfAssessmentGap() != null) {
                s.labeledRow("Self-assessment gap", detail.selfAssessmentGap());
            }
            if (detail.whatThisScoreMeans() != null) {
                s.labeledRow("What this score means", redactor.redact(detail.whatThisScoreMeans()));
            }
            if (detail.whatsWorking() != null && !detail.whatsWorking().isEmpty()) {
                s.labeledRow("What's working", ExcelWorkbookBuilder.bullets(redactor.redact(detail.whatsWorking())));
            }
            if (detail.whatCanImprove() != null && !detail.whatCanImprove().isEmpty()) {
                s.labeledRow("What can improve", ExcelWorkbookBuilder.bullets(redactor.redact(detail.whatCanImprove())));
            }
            if (detail.whyThisMattersForBusiness() != null) {
                s.labeledRow("Why this matters for business", redactor.redact(detail.whyThisMattersForBusiness()));
            }
        }
        s.autoSize();
    }

    private <T> List<T> nullToEmpty(List<T> items) {
        return items == null ? List.of() : items;
    }
}
