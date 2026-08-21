package com.bvisionry.security;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side authority for the {@code showNames} export flag, asserted on the
 * BYTES the user actually receives.
 *
 * <p>The defect this pins: {@code showNames} was a bare {@code @RequestParam} on
 * three org-scoped export controllers, with the whole rule about who may set it
 * living solely as a comment in the web app — so one hand-edited query string
 * decided masking. {@link ExportNameGuard} moved that decision server-side.
 *
 * <p>The RULE it enforces was widened by operator ruling 2026-08-14: a
 * SUPER_ADMIN or an ORG_ADMIN may unmask, a COACH never may, and masked is still
 * the default for everyone. The org-admin case needs no tenancy assertion here
 * because the class-level {@code @PreAuthorize} on each controller already pins
 * them to their own org — that gate has its own tests.
 *
 * <h2>Why the assertions read the document, not a mock</h2>
 *
 * There are FOUR independent name-resolution paths behind these export handlers —
 * {@code MemberDisplayNameResolver}, {@code MemberIdentityFactory},
 * {@code OrgInsight{Excel,Pdf}Service}'s own private {@code resolveMemberNames},
 * and {@code WorkshopAnswersExportService} — so a {@code verify(service).x(...,
 * false)} on one proves nothing about the other three, and proves nothing at all
 * about a service that hardcodes the name past the flag. Every masking case here
 * extracts the real text out of the generated PDF or workbook and asserts the
 * seeded founder's name is not in it.
 *
 * <p>One seeded name per export family, because four resolution paths are four
 * independent chances to lie.
 *
 * <h2>What this cannot see</h2>
 *
 * The workshop EXCEL export writes member names only onto rows built from
 * answered cards, and this fixture seeds a workshop with no exercises, so its
 * content assertion runs on the PDF (which prints the roster regardless) and the
 * workbook is covered for authority only. Nothing here covers
 * {@code /dashboard/overview}, which returns {@code memberName} and
 * {@code memberEmail} as JSON to every in-org admin — a separate, escalated
 * question about what an org admin may see on screen.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class ExportNameAuthorityIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The seeded founder. Every masking assertion is "this string is absent". */
    private static final String FOUNDER = "Ada Lovelace";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization org;
    private User orgAdmin;
    private User superAdmin;
    private UUID founderId;
    private UUID pipelineId;
    private UUID submissionId;
    private UUID insightReportId;
    private UUID workshopId;

    @BeforeEach
    void seed() {
        org = saveOrg();
        orgAdmin = saveUser("export.orgadmin@test.invalid", "Org Admin", UserRole.ORG_ADMIN, org);
        superAdmin = saveUser("export.super@test.invalid", "Super Admin", UserRole.SUPER_ADMIN, null);
        founderId = saveUser("export.founder@test.invalid", FOUNDER, UserRole.MEMBER, org).getId();

        pipelineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pipelines (id, name, status, created_by)
                VALUES (?, 'Founder Readiness', 'PUBLISHED', ?)
                """, pipelineId, orgAdmin.getId());
        UUID pillarId = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name, display_order) VALUES (?, ?, 'Vision', 1)",
                pillarId, pipelineId);

        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, org.getId(), founderId, orgAdmin.getId());

        submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now(), now())
                """, submissionId, assignmentId, founderId);
        jdbc.update("""
                INSERT INTO pillar_evaluations
                    (submission_id, pillar_id, score_percentage, maturity_label, ai_failed)
                VALUES (?, ?, 62.0, 'Developing', false)
                """, submissionId, pillarId);
        jdbc.update("""
                INSERT INTO overall_summaries (submission_id, overall_score_percentage, summary_narrative)
                VALUES (?, 62.0, 'A steady operator with a clear thesis.')
                """, submissionId);

        // An org-insight report whose AI payload carries exactly one coaching
        // entry, so the export has one slot to resolve a name into.
        insightReportId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO insight_reports
                    (id, organization_id, pipeline_id, report_json, ai_model_used, status)
                VALUES (?, ?, ?, ?::jsonb, 'test-model', 'COMPLETED')
                """, insightReportId, org.getId(), pipelineId, """
                {"teamThemes":{"commonStrengths":["Ships fast"],"growthEdges":["Pricing"],
                 "patterns":[],"recommendations":["Run a pricing sprint"]},
                 "individualCoaching":[{"focusAreas":["Pricing"],"suggestedActions":["Interview 5 buyers"]}]}
                """);

        // A workshop with one team of one — enough for the answers export to
        // walk a roster and print (or mask) a name.
        workshopId = UUID.randomUUID();
        jdbc.update("INSERT INTO workshops (id, org_id, name, status) VALUES (?, ?, 'Control Flip', 'ACTIVE')",
                workshopId, org.getId());
        UUID teamId = UUID.randomUUID();
        jdbc.update("INSERT INTO workshop_teams (id, workshop_id, name) VALUES (?, ?, 'Team Alpha')",
                teamId, workshopId);
        jdbc.update("""
                INSERT INTO workshop_team_members (workshop_id, user_id, team_id, is_lead)
                VALUES (?, ?, ?, true)
                """, workshopId, founderId, teamId);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    // ------------------------------------------------------------------
    // 1. An in-org ORG_ADMIN may ask for names on every route they can reach
    //    (operator ruling 2026-08-14) — and gets them, on the bytes.
    // ------------------------------------------------------------------

    @Test
    void orgAdminAskingForNamesGetsTheRealNameOnEveryOrgScopedExport() throws Exception {
        TestAuthentication.authenticate(orgAdmin);
        assertThat(pdfText(ok(withNames(orgInsight("pdf"))))).contains(FOUNDER);
        assertThat(cellText(ok(withNames(orgInsight("excel"))))).contains(FOUNDER);
        assertThat(pdfText(ok(withNames(teamInsight("pdf"))))).contains(FOUNDER);
        assertThat(cellText(ok(withNames(teamInsight("excel"))))).contains(FOUNDER);
        assertThat(pdfText(ok(withNames(perMember("pdf"))))).contains(FOUNDER);
        assertThat(cellText(ok(withNames(perMember("excel"))))).contains(FOUNDER);
    }

    /**
     * Masking is still the DEFAULT, for everyone. An org admin who does not ask
     * for names gets their document masked, not merely "gets a document".
     */
    @Test
    void orgAdminWithoutTheFlagStillGetsEveryExport() throws Exception {
        TestAuthentication.authenticate(orgAdmin);
        for (String route : orgScopedExports()) {
            mockMvc.perform(get(route)).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------
    // 2. …and what they get WITHOUT the flag carries no real name — read off the
    //    bytes. One family per test so a failure names the resolution path.
    // ------------------------------------------------------------------

    @Test
    void orgInsightExportsMaskTheFounderForAnOrgAdmin() throws Exception {
        TestAuthentication.authenticate(orgAdmin);
        assertThat(pdfText(ok(orgInsight("pdf")))).doesNotContain(FOUNDER).contains("Member 1");
        assertThat(cellText(ok(orgInsight("excel")))).doesNotContain(FOUNDER).contains("Member 1");
    }

    @Test
    void teamInsightExportsMaskTheFounderForAnOrgAdmin() throws Exception {
        TestAuthentication.authenticate(orgAdmin);
        assertThat(pdfText(ok(teamInsight("pdf")))).doesNotContain(FOUNDER);
        assertThat(cellText(ok(teamInsight("excel")))).doesNotContain(FOUNDER);
    }

    @Test
    void perMemberExportsMaskTheFounderForAnOrgAdmin() throws Exception {
        TestAuthentication.authenticate(orgAdmin);
        assertThat(pdfText(ok(perMember("pdf")))).doesNotContain(FOUNDER).contains("Member");
        assertThat(cellText(ok(perMember("excel")))).doesNotContain(FOUNDER);
    }

    /**
     * The workshop console is SUPER_ADMIN-only (product ruling: an org admin
     * gets no workshop authoring or detail access at all), so its answers
     * export is refused to an org admin outright — flag or no flag. The masking
     * default is asserted below on the SUPER_ADMIN, who is the only caller that
     * can reach {@code WorkshopAnswersExportService} at all.
     */
    @Test
    void workshopAnswersExportIsForbiddenToAnOrgAdminEntirely() throws Exception {
        TestAuthentication.authenticate(orgAdmin);
        for (String format : new String[] {"pdf", "excel"}) {
            mockMvc.perform(get(workshopAnswers(format))).andExpect(status().isForbidden());
            mockMvc.perform(get(withNames(workshopAnswers(format)))).andExpect(status().isForbidden());
        }
    }

    @Test
    void workshopAnswersExportMasksTheFounderByDefault() throws Exception {
        TestAuthentication.authenticate(superAdmin);
        assertThat(pdfText(ok(workshopAnswers("pdf")))).doesNotContain(FOUNDER).contains("Member 1");
        // The workbook is covered for authority only — see the class javadoc.
        ok(workshopAnswers("excel"));
    }

    // ------------------------------------------------------------------
    // 3. A SUPER_ADMIN asking for names gets them — the guard denies, it does
    //    not disable the feature, and masking is not unconditional.
    // ------------------------------------------------------------------

    @Test
    void superAdminAskingForNamesGetsTheRealNameInEveryFamily() throws Exception {
        TestAuthentication.authenticate(superAdmin);
        assertThat(pdfText(ok(withNames(orgInsight("pdf"))))).contains(FOUNDER);
        assertThat(cellText(ok(withNames(orgInsight("excel"))))).contains(FOUNDER);
        assertThat(pdfText(ok(withNames(teamInsight("pdf"))))).contains(FOUNDER);
        assertThat(cellText(ok(withNames(teamInsight("excel"))))).contains(FOUNDER);
        assertThat(pdfText(ok(withNames(perMember("pdf"))))).contains(FOUNDER);
        assertThat(cellText(ok(withNames(perMember("excel"))))).contains(FOUNDER);
        assertThat(pdfText(ok(withNames(workshopAnswers("pdf"))))).contains(FOUNDER);
    }

    // ------------------------------------------------------------------
    // 4. The self surface keeps working. MemberDisplayNameResolver serves both
    //    this and the admin per-member export, which is exactly why the guard
    //    could not live in the resolver.
    // ------------------------------------------------------------------

    @Test
    void memberGetsTheirOwnNameOnTheirOwnReport() throws Exception {
        TestAuthentication.authenticate(userRepository.findById(founderId).orElseThrow());
        String pdf = pdfText(ok("/api/my/assessments/" + submissionId + "/results/pdf?showNames=true"));
        assertThat(pdf).contains(FOUNDER);

        String xlsx = cellText(ok("/api/my/assessments/" + submissionId + "/results/excel?showNames=true"));
        assertThat(xlsx).contains(FOUNDER);
    }

    /** The member may still choose to anonymise their own copy. */
    @Test
    void memberCanStillAnonymiseTheirOwnReport() throws Exception {
        TestAuthentication.authenticate(userRepository.findById(founderId).orElseThrow());
        assertThat(pdfText(ok("/api/my/assessments/" + submissionId + "/results/pdf?showNames=false")))
                .doesNotContain(FOUNDER);
    }

    /**
     * THE PREMISE OF THE `@NamesVisibleToSelf` EXEMPTION, pinned.
     *
     * <p>`/api/my/...` is exempt from {@link ExportNameGuard} for exactly one
     * reason: `verifySubmissionOwnership` means the only name it can reveal is
     * the caller's own. That reason lives in an annotation string, and an
     * annotation string is a comment — ArchUnit's rule reads `carries()` and
     * never `value()`, so NOTHING was checking the premise. Measured: deleting
     * `verifySubmissionOwnership` from the handler left the entire suite green,
     * including Rule 7. The exemption was resting on an unverified claim, which
     * is the defect class this whole ticket exists to remove.
     *
     * <p>So: a DIFFERENT member, asking for someone else's submission, must be
     * refused — and refused whatever `showNames` says, because the guard does
     * not run on this surface at all.
     *
     * <p>The status is <b>400, not 403</b>, and that is asserted rather than
     * corrected: `verifySubmissionOwnership` throws `BadRequestException`. It is
     * a real refusal with an odd label, it is PRE-EXISTING, and this ticket is a
     * comment-truth fix that has no business changing a status code the web app
     * may branch on. Pinning the true value is what makes this test evidence;
     * asserting the status I would have preferred would have made it fiction.
     * (Recorded as a follow-up: 400-for-authorization also distinguishes
     * "exists but not yours" from the 404 for "does not exist", which is a mild
     * existence oracle.)
     */
    @Test
    void anotherMemberCannotReadThisFoundersOwnReport() throws Exception {
        User stranger = saveUser("export.stranger@test.invalid", "Stranger", UserRole.MEMBER, org);
        TestAuthentication.authenticate(stranger);

        for (String showNames : new String[] {"true", "false"}) {
            mockMvc.perform(get("/api/my/assessments/" + submissionId
                            + "/results/pdf?showNames=" + showNames))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/my/assessments/" + submissionId
                            + "/results/excel?showNames=" + showNames))
                    .andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------
    // 5. The per-member export's default flipped from true to false. Pin it on
    //    the document: the old default put a real name in an org admin's hands
    //    with no query string at all.
    // ------------------------------------------------------------------

    @Test
    void perMemberExportDefaultsToMasked() throws Exception {
        TestAuthentication.authenticate(superAdmin);
        assertThat(pdfText(ok(perMember("pdf")))).doesNotContain(FOUNDER);
        assertThat(cellText(ok(perMember("excel")))).doesNotContain(FOUNDER);
    }

    // ------------------------------------------------------------- routes

    /**
     * The org-scoped exports an ORG_ADMIN can still reach. The workshop answers
     * export is deliberately NOT here: that console is SUPER_ADMIN-only now, so
     * an org admin is refused the document itself, not just the flag — see
     * {@code workshopAnswersExportIsForbiddenToAnOrgAdminEntirely}.
     */
    private List<String> orgScopedExports() {
        return List.of(
                orgInsight("pdf"), orgInsight("excel"),
                teamInsight("pdf"), teamInsight("excel"),
                perMember("pdf"), perMember("excel"));
    }

    private String orgInsight(String format) {
        return "/api/organizations/" + org.getId() + "/org-insights/" + insightReportId + "/" + format;
    }

    private String teamInsight(String format) {
        return "/api/organizations/" + org.getId() + "/dashboard/insights/" + format
                + "?pipelineId=" + pipelineId;
    }

    private String perMember(String format) {
        return "/api/organizations/" + org.getId() + "/dashboard/members/" + founderId
                + "/results/" + submissionId + "/" + format;
    }

    private String workshopAnswers(String format) {
        return "/api/organizations/" + org.getId() + "/workshops/" + workshopId + "/answers/" + format;
    }

    private static String withNames(String route) {
        return route + separator(route) + "showNames=true";
    }

    private static String separator(String route) {
        return route.contains("?") ? "&" : "?";
    }

    // ------------------------------------------------------------- helpers

    private byte[] ok(String route) throws Exception {
        return mockMvc.perform(get(route))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    /**
     * The rendered text of the PDF, decompressed and de-positioned. A raw byte
     * search would find nothing either way: Flying Saucer writes the page
     * content stream FlateDecoded, so an unmasked name is invisible to
     * {@code new String(pdf)} and a masking bug would pass unnoticed.
     */
    private static String pdfText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    /** Every string cell in the workbook — the readable content of the export. */
    private static String cellText(byte[] xlsx) throws Exception {
        List<String> out = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (var sheet : wb) {
                for (var row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) {
                            out.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        }
        return String.join("\n", out);
    }

    // ------------------------------------------------------------ fixtures

    /** PREMIUM: org insights are entitlement-gated, and that gate is not what is under test. */
    private Organization saveOrg() {
        Organization organization = new Organization();
        organization.setName("Export Org");
        organization.setActive(true);
        organization.setSubscriptionTier(SubscriptionTier.GROWTH);
        return organizationRepository.saveAndFlush(organization);
    }

    private User saveUser(String email, String name, UserRole role, Organization organization) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(organization);
        return userRepository.saveAndFlush(user);
    }
}
