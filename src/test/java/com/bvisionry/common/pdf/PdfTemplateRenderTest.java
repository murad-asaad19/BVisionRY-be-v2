package com.bvisionry.common.pdf;

import com.bvisionry.reporting.dto.PersonalInfoEntry;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import com.bvisionry.workshops.dto.PlayResponse;
import com.bvisionry.workshops.web.WorkshopAnswersExportService;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render-level smoke coverage for the three branded PDF templates. Drives the
 * real {@link PdfRenderer} (brand fonts + logo data URIs + Flying Saucer) with
 * representative mock data and asserts each template produces a well-formed PDF.
 *
 * <p>This guards the parts of the export pipeline that only fail at render time
 * and are invisible to the compiler: Thymeleaf/SpEL expressions, the shared
 * {@code fragments/pdf-base} include, font registration and image embedding.
 * It deliberately avoids a Spring context so it stays fast and infra-free; the
 * {@link SpringTemplateEngine} mirrors production's SpEL dialect.
 */
class PdfTemplateRenderTest {

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /**
     * Assert the bytes are a well-formed, branded PDF. Beyond the {@code %PDF-}
     * header and a non-trivial size, this inspects the document's objects to prove
     * the brand assets actually embedded — the two failure modes a plain header
     * check misses: a missing/renamed brand image degrades to an empty {@code src}
     * (no image XObject), and a font-registration failure silently falls back off
     * Inter to a default serif (no embedded Inter {@code FontFile2}). Both still
     * produce a valid, &gt;5KB PDF, so only reading the objects can catch them.
     */
    private static void assertBrandedPdf(byte[] pdf) throws IOException {
        assertTrue(pdf != null && pdf.length > 5000, "PDF should be non-trivial");
        String header = new String(pdf, 0, 5, StandardCharsets.ISO_8859_1);
        assertTrue(header.equals("%PDF-"), "Output should be a PDF document");

        PdfReader reader = new PdfReader(pdf);
        try {
            boolean interFontEmbedded = false;
            boolean imageEmbedded = false;
            for (int i = 1; i < reader.getXrefSize(); i++) {
                PdfObject obj = reader.getPdfObject(i);
                // Image XObjects are streams; font descriptors are plain dictionaries.
                // PdfStream extends PdfDictionary, so both expose their keys this way.
                if (obj == null || !(obj.isDictionary() || obj.isStream())) {
                    continue;
                }
                PdfDictionary dict = (PdfDictionary) obj;
                if (PdfName.IMAGE.equals(dict.getAsName(PdfName.SUBTYPE))) {
                    imageEmbedded = true;
                }
                if (dict.contains(PdfName.FONTFILE2)) {
                    PdfName fontName = dict.getAsName(PdfName.FONTNAME);
                    if (fontName != null && fontName.toString().contains("Inter")) {
                        interFontEmbedded = true;
                    }
                }
            }
            assertTrue(imageEmbedded,
                    "Brand imagery (logo/watermark) should embed as an image XObject; "
                            + "an empty data URI would ship an un-branded report");
            assertTrue(interFontEmbedded,
                    "Brand typeface 'Inter' should embed as a FontFile2; a registration "
                            + "failure would silently fall back to a default serif");
        } finally {
            reader.close();
        }
    }

    private static PillarDetailResponse pillar(String name, int score, String maturity, Integer gap) {
        return new PillarDetailResponse(
                UUID.randomUUID(), name, "icon", BigDecimal.valueOf(score), maturity,
                "Your " + name + " sits in a strong band. You can articulate the core of this area "
                        + "clearly and back it with concrete evidence from how you operate day to day.",
                List.of("You consistently connect this area to measurable outcomes.",
                        "Your decisions here are backed by data rather than intuition alone."),
                List.of("Tighten the narrative so non-technical stakeholders follow it instantly.",
                        "Create a repeatable ritual so this strength survives busy weeks."),
                "Founders who score well here close their first key hires faster and present a more "
                        + "fundable story to investors, because the vision travels without the founder.",
                gap, false);
    }

    @Test
    void rendersMemberResultsReport() throws IOException {
        Context ctx = new Context();
        ctx.setVariable("participantName", "Sarah Al-Founder");
        ctx.setVariable("assessmentTitle", "Founder Mindset Assessment");
        ctx.setVariable("reportDate", "June 2026");
        ctx.setVariable("overallScore", 63);
        ctx.setVariable("overallCategory", "Strong Mindset");
        ctx.setVariable("summaryNarrative",
                "You bring a strong, evidence-led mindset to building. Your clearest advantage is "
                        + "turning ambiguity into a concrete next step.");
        ctx.setVariable("pillarScores", List.of());
        ctx.setVariable("pillarDetails", List.of(
                pillar("Vision Clarity", 78, "Architectural Operator", 24),
                pillar("Decision Velocity", 54, "Emerging Operator", -8),
                pillar("Resilience Under Load", 61, "Steady Operator", null)));
        ctx.setVariable("strengths", List.of(
                "Translating a fuzzy goal into a shippable first step.",
                "Holding a high bar while still moving quickly."));
        ctx.setVariable("developmentAreas", List.of(
                "Delegating outcomes, not just tasks.",
                "Protecting deep-focus time from reactive work."));
        ctx.setVariable("corePattern",
                "You lead with conviction and speed; your growth edge is building the systems that let "
                        + "others carry that pace with you.");
        ctx.setVariable("movingForward",
                "Over the next cohort, focus first on delegation and focus protection.");
        ctx.setVariable("personalInfo", List.of(
                new PersonalInfoEntry("Role", "Co-founder & CEO"),
                new PersonalInfoEntry("Stage", "Seed")));

        assertBrandedPdf(new PdfRenderer(engine()).renderTemplate("pdf-report", ctx));
    }

    @Test
    void rendersOrgInsightsReport() throws IOException {
        Context ctx = new Context();
        ctx.setVariable("orgName", "Horizon Ventures");
        ctx.setVariable("pipelineName", "2026 Founder Cohort");
        ctx.setVariable("reportDate", "June 2026");
        ctx.setVariable("strengths", List.of("Founders translate ambiguity into a concrete next step."));
        ctx.setVariable("weaknesses", List.of("Delegation lags relative to vision and execution."));
        ctx.setVariable("patterns", List.of("High-velocity founders under-invest in delegation early."));
        ctx.setVariable("recommendations", List.of("Run a cohort workshop on delegating outcomes."));
        Map<String, Object> coach = new LinkedHashMap<>();
        coach.put("focusAreas", List.of("Delegation", "Focus protection"));
        coach.put("suggestedActions", List.of("Pair with an operator mentor for two sessions."));
        ctx.setVariable("coaching", List.of(coach));
        ctx.setVariable("showNames", false);
        ctx.setVariable("memberNames", List.of());
        ctx.setVariable("benchmarkComparison",
                "This cohort scores above the platform median on Vision Clarity and below on Delegation.");
        ctx.setVariable("outlierPillars", List.of("Delegation", "Focus Management"));
        ctx.setVariable("rawResponse", null);

        assertBrandedPdf(new PdfRenderer(engine()).renderTemplate("org-insights-report", ctx));
    }

    @Test
    void rendersTeamInsightsReport() throws IOException {
        Context ctx = new Context();
        ctx.setVariable("pipelineName", "2026 Founder Cohort");
        ctx.setVariable("reportDate", "June 2026");
        ctx.setVariable("memberFilterApplied", true);
        ctx.setVariable("totalAssignments", 12);
        ctx.setVariable("evaluatedCount", 9);
        ctx.setVariable("submittedCount", 2);
        ctx.setVariable("inProgressCount", 1);
        ctx.setVariable("failedCount", 0);
        ctx.setVariable("completionRate", "75%");
        ctx.setVariable("avgOverallScore", "64%");
        Map<String, Object> gender = new LinkedHashMap<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("Female", 5L);
        counts.put("Male", 6L);
        counts.put("Unspecified", 1L);
        gender.put("counts", counts);
        gender.put("total", 12);
        ctx.setVariable("genderBreakdown", gender);

        Map<String, Object> avg = new LinkedHashMap<>();
        avg.put("pillarName", "Vision Clarity");
        avg.put("avgScore", "71%");
        Map<String, Object> mat = new LinkedHashMap<>();
        mat.put("Architectural Operator", 4L);
        mat.put("Emerging Operator", 5L);
        avg.put("maturityCounts", mat);
        ctx.setVariable("pillarAverages", List.of(avg));

        Map<String, Object> pillar = new LinkedHashMap<>();
        pillar.put("pillarName", "Vision Clarity");
        pillar.put("score", "78%");
        pillar.put("maturityLabel", "Architectural Operator");
        pillar.put("whatsWorking", List.of("Connects vision to measurable outcomes."));
        pillar.put("whatCanImprove", List.of("Tighten the narrative for non-technical stakeholders."));
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("name", "Sarah Al-Founder");
        member.put("email", "sarah@example.com");
        member.put("userType", "Founder");
        member.put("gender", "Female");
        member.put("overallScore", "64%");
        member.put("summaryNarrative", "Strong, evidence-led operator with clear growth edges.");
        member.put("strengths", List.of("Turns ambiguity into a shippable first step."));
        member.put("developmentAreas", List.of("Delegating outcomes, not just tasks."));
        member.put("personalInfo", List.of(new PersonalInfoEntry("Role", "Co-founder & CEO")));
        member.put("pillars", List.of(pillar));
        ctx.setVariable("members", List.of(member));

        assertBrandedPdf(new PdfRenderer(engine()).renderTemplate("team-insights-report", ctx));
    }

    @Test
    void rendersWorkshopAnswersReport() throws IOException {
        Context ctx = new Context();
        ctx.setVariable("workshopName", "Ctrl + Alt + Own");
        ctx.setVariable("reportDate", "July 12, 2026");
        ctx.setVariable("scopeLabel", "All members");
        ctx.setVariable("showNames", false);

        PlayResponse.SortRecap sort = new PlayResponse.SortRecap(
                UUID.randomUUID(), "Flip It", "Green", "Red",
                List.of(new PlayResponse.CardDto("c1", "A noisy neighborhood disrupting your workspace")),
                List.of(new PlayResponse.CardDto("c2", "Unfair or overly complex online assessments")));
        WorkshopAnswersExportService.MemberExport lead =
                new WorkshopAnswersExportService.MemberExport("Member 1", true, List.of(
                        new PlayResponse.RecapRow(UUID.randomUUID(), "Your Turn to Talk",
                                "What can I do about it?",
                                List.of(new PlayResponse.RecapAnswer("c1",
                                        "A noisy neighborhood disrupting your workspace",
                                        "Set fixed focus hours and a dedicated space.")),
                                List.of()),
                        new PlayResponse.RecapRow(UUID.randomUUID(), "Top Cards", "",
                                List.of(),
                                List.of(new PlayResponse.ScoredCard("c1",
                                        "A noisy neighborhood disrupting your workspace", 90)))));
        WorkshopAnswersExportService.MemberExport noAnswers =
                new WorkshopAnswersExportService.MemberExport("Member 2", false, List.of());
        ctx.setVariable("teams", List.of(
                new WorkshopAnswersExportService.TeamExport("Team Red", List.of(sort),
                        List.of(lead, noAnswers)),
                new WorkshopAnswersExportService.TeamExport("Team Green", List.of(), List.of())));

        assertBrandedPdf(new PdfRenderer(engine()).renderTemplate("workshop-answers-report", ctx));
    }
}
