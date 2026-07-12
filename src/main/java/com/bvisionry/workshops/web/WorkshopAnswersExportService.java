package com.bvisionry.workshops.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.workshops.domain.Workshop;
import com.bvisionry.workshops.domain.WorkshopTeam;
import com.bvisionry.workshops.dto.MemberAnswersResponse;
import com.bvisionry.workshops.dto.PlayResponse;
import com.bvisionry.workshops.repository.WorkshopRepository;
import com.bvisionry.workshops.repository.WorkshopTeamRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin export of workshop answers — every team's members (or only the team
 * leads) with their final-review recap, as a branded PDF or an Excel workbook.
 *
 * <p>Mirrors the insight exports: {@code showNames=false} masks identities as
 * positional labels ("Member 1", …) that stay stable across PDF and Excel
 * because both walk the same lead-first, then-by-name roster order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkshopAnswersExportService {

    private final WorkshopRepository workshops;
    private final WorkshopTeamRepository teams;
    private final MyWorkshopService myWorkshops;
    private final PdfRenderer pdfRenderer;

    /** One team's slice of the export. */
    public record TeamExport(
            String teamName,
            List<PlayResponse.SortRecap> sortRecaps,
            List<MemberExport> members) {
    }

    /** One member's answers; {@code displayName} is already masked when names are hidden. */
    public record MemberExport(
            String displayName,
            boolean lead,
            List<PlayResponse.RecapRow> recap) {
    }

    @Transactional(readOnly = true)
    public byte[] pdf(UUID orgId, UUID workshopId, boolean leadsOnly, boolean showNames) {
        Workshop w = requireWorkshop(orgId, workshopId);
        Context ctx = new Context();
        ctx.setVariable("workshopName", w.getName());
        ctx.setVariable("reportDate",
                LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy")));
        ctx.setVariable("scopeLabel", leadsOnly ? "Team leads only" : "All members");
        ctx.setVariable("showNames", showNames);
        ctx.setVariable("teams", build(w, leadsOnly, showNames));
        byte[] pdf = pdfRenderer.renderTemplate("workshop-answers-report", ctx);
        log.info("Generated workshop answers PDF for {} ({} bytes)", workshopId, pdf.length);
        return pdf;
    }

    @Transactional(readOnly = true)
    public byte[] excel(UUID orgId, UUID workshopId, boolean leadsOnly, boolean showNames) {
        Workshop w = requireWorkshop(orgId, workshopId);
        List<TeamExport> data = build(w, leadsOnly, showNames);
        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeOverview(wb, w, leadsOnly, showNames);
            writeAnswers(wb, data);
            writeTeamSorts(wb, data);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate workshop answers Excel", e);
        }
    }

    // ------------------------------------------------------------ data

    /**
     * Every team in board order; within a team the roster order is lead-first
     * then by name, and the positional mask ("Member N") is assigned over the
     * FULL roster before the leads-only filter — so a lead keeps the same
     * label whether or not members are included.
     */
    private List<TeamExport> build(Workshop w, boolean leadsOnly, boolean showNames) {
        List<TeamExport> out = new ArrayList<>();
        for (WorkshopTeam team : teams.findByWorkshopIdOrderByPositionAscCreatedAtAsc(w.getId())) {
            List<WorkshopTeamRepository.TeamMemberRow> roster = teams.findTeamMembers(team.getId());
            List<PlayResponse.SortRecap> sortRecaps = List.of();
            List<MemberExport> members = new ArrayList<>();
            int position = 0;
            for (WorkshopTeamRepository.TeamMemberRow m : roster) {
                position++;
                if (leadsOnly && !m.getLead()) {
                    continue;
                }
                MemberAnswersResponse answers = myWorkshops.memberAnswers(w, m.getId());
                if (sortRecaps.isEmpty()) {
                    sortRecaps = answers.sortRecaps();
                }
                members.add(new MemberExport(
                        showNames ? m.getName() : "Member " + position,
                        m.getLead(), answers.recap()));
            }
            out.add(new TeamExport(team.getName(), sortRecaps, members));
        }
        return out;
    }

    /** The workshop's name for export filenames, org-checked like the exports themselves. */
    @Transactional(readOnly = true)
    public String workshopName(UUID orgId, UUID workshopId) {
        return requireWorkshop(orgId, workshopId).getName();
    }

    private Workshop requireWorkshop(UUID orgId, UUID workshopId) {
        return workshops.findById(workshopId)
                .filter(w -> w.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Workshop", workshopId.toString()));
    }

    // ------------------------------------------------------------ excel sheets

    private void writeOverview(ExcelWorkbookBuilder wb, Workshop w,
                               boolean leadsOnly, boolean showNames) {
        wb.newSheet("Overview")
                .labeledRow("Workshop", w.getName())
                .labeledRow("Exported",
                        LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy")))
                .labeledRow("Scope", leadsOnly ? "Team leads only" : "All members")
                .labeledRow("Member names", showNames ? "Shown" : "Anonymised")
                .autoSize();
    }

    /** One row per answered card; ranked top-card lists become "Rank N" rows. */
    private void writeAnswers(ExcelWorkbookBuilder wb, List<TeamExport> data) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Answers")
                .headers("Team", "Member", "Role", "Task", "Question", "Card", "Response");
        for (TeamExport team : data) {
            for (MemberExport member : team.members()) {
                String role = member.lead() ? "Team lead" : "Team member";
                for (PlayResponse.RecapRow row : member.recap()) {
                    if (row.answers().isEmpty()) {
                        int rank = 0;
                        for (PlayResponse.ScoredCard card : row.topRows()) {
                            rank++;
                            s.row(team.teamName(), member.displayName(), role,
                                    row.taskTitle(), row.prompt(), card.text(),
                                    "Rank " + rank + " (" + card.weight() + ")");
                        }
                    } else {
                        for (PlayResponse.RecapAnswer answer : row.answers()) {
                            s.row(team.teamName(), member.displayName(), role,
                                    row.taskTitle(), row.prompt(),
                                    answer.cardText(), answer.text());
                        }
                    }
                }
            }
        }
        s.autoSize();
    }

    /** The team's shared sort piles — one row per dealt card under its pile label. */
    private void writeTeamSorts(ExcelWorkbookBuilder wb, List<TeamExport> data) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Team Sorts")
                .headers("Team", "Task", "Pile", "Card");
        for (TeamExport team : data) {
            for (PlayResponse.SortRecap sort : team.sortRecaps()) {
                for (PlayResponse.CardDto card : sort.left()) {
                    s.row(team.teamName(), sort.taskTitle(), sort.leftLabel(), card.text());
                }
                for (PlayResponse.CardDto card : sort.right()) {
                    s.row(team.teamName(), sort.taskTitle(), sort.rightLabel(), card.text());
                }
            }
        }
        s.autoSize();
    }
}
