package com.bvisionry.aiengine.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangChain4j {@link ChatModel} that returns canned, schema-valid JSON instead
 * of calling a live provider — the LangChain4j-seam equivalent of the former
 * Spring-AI {@code MockChatModel}. Used under the {@code mock} (and, scripted,
 * {@code e2e}) profiles so the full evaluation pipeline runs with no API key.
 *
 * <p>By default it discriminates on the system prompt (each schema's contract has
 * a unique field name) and returns a valid default. Tests can {@link #enqueue}
 * specific raw responses — including deliberately malformed / out-of-range output
 * — to exercise the guardrail repair loop and fail-loud paths. Scripted responses
 * are consumed FIFO; once drained it falls back to the canned defaults.
 */
public class MockLangChainChatModel implements ChatModel {

    private final Queue<String> scripted = new ConcurrentLinkedQueue<>();

    /** Queue a raw model response to be returned by the next call(s), FIFO. */
    public MockLangChainChatModel enqueue(String rawResponse) {
        scripted.add(rawResponse);
        return this;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        String body = scripted.poll();
        if (body == null) {
            body = defaultFor(systemText(chatRequest), userText(chatRequest));
        }
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(body))
                .tokenUsage(new TokenUsage(0, 0))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static String systemText(ChatRequest request) {
        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage system) {
                return system.text();
            }
        }
        return "";
    }

    /**
     * The FIRST user message — the one carrying the assessment data. The repair
     * loop appends corrective user messages after it; deriving the canned answer
     * from the first keeps a mocked retry deterministic.
     */
    private static String userText(ChatRequest request) {
        for (ChatMessage message : request.messages()) {
            if (message instanceof UserMessage user) {
                return user.singleText();
            }
        }
        return "";
    }

    private static String defaultFor(String systemPrompt, String userMessage) {
        // Order matters: the pillar schema's scorePercentage is a substring of
        // overallScorePercentage, so check the more specific keys first.
        // The member growth summary (§3). Checked BEFORE the shift-narrative
        // arm: since V193 both prompts name the same kinds, so CARRIED_FORWARD
        // no longer tells them apart — "overall growth breakdown" does, and it
        // appears only here.
        if (systemPrompt.contains("overall growth breakdown")) {
            return memberGrowthBreakdownFor(userMessage);
        }
        // Its pre-V193 contract, still shipped by installations that customised
        // the template — {"summary" appears only there.
        if (systemPrompt.contains("{\"summary\"")) {
            return MEMBER_GROWTH_SUMMARY_DEFAULT;
        }
        if (systemPrompt.contains("teamThemes")) {
            return TEAM_INSIGHT_DEFAULT;
        }
        // The cohort growth report (§4). Without this arm it fell through to
        // PILLAR_DEFAULT and every mocked report FAILED schema validation, so
        // the report could never be seen locally. Routed on the INSTRUCTION,
        // not the schema: V194 took the JSON envelope out of the prompt
        // (LangChain4j derives it from CohortGrowthSummaryResult), so
        // "sharedWins" is no longer there. "COHORT-WIDE" is that prompt's whole
        // job and appears in no other.
        if (systemPrompt.contains("COHORT-WIDE")) {
            return COHORT_GROWTH_SUMMARY_DEFAULT;
        }
        if (systemPrompt.contains("overallScorePercentage")) {
            return OVERALL_SUMMARY_DEFAULT;
        }
        // The shift narrative (§6) has its own schema. Without this arm it fell
        // through to PILLAR_DEFAULT, so every mocked generation failed the
        // kind/narrative guardrail and no narrative could ever be reviewed on a
        // sandbox lane. CARRIED_FORWARD appears only in that prompt.
        if (systemPrompt.contains("CARRIED_FORWARD")) {
            return shiftNarrativeFor(userMessage);
        }
        return PILLAR_DEFAULT;
    }

    /* --------------------------------------- data-grounded shift narrative */

    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * The pillar NAME alone: the growth-summary message suffixes each heading
     * with " — before N% → after M%" (context scores), and a mock that echoed
     * the suffix would put numbers into prose whose one hard rule bans them.
     */
    private static final Pattern PILLAR_LINE =
            Pattern.compile("^PILLAR: (.+?)(?: — before .*)?$", Pattern.MULTILINE);
    private static final Pattern DIRECTION_LINE =
            Pattern.compile("^PILLAR DIRECTION: (.+)$", Pattern.MULTILINE);

    /**
     * A shift narrative grounded in the ACTUAL request, so eleven mocked pillars
     * read as eleven different narratives instead of one canned block repeated —
     * the difference between local QA exercising the flow and rubber-stamping it.
     *
     * <p>Each item names the pillar and quotes a short phrase copied from the
     * matching input section (BEFORE / AFTER / a {@code <submission>} line),
     * mirroring what the real prompt demands of the live model. Items whose
     * section has no material are omitted, exactly as the prompt instructs
     * ("a kind you have no evidence for is simply left out"). Falls back to the
     * static default when the message does not carry the expected structure
     * (unit tests, hand-rolled calls).
     */
    private static String shiftNarrativeFor(String userMessage) {
        Matcher pillarMatch = PILLAR_LINE.matcher(userMessage);
        if (!pillarMatch.find()) {
            return SHIFT_NARRATIVE_DEFAULT;
        }
        String pillar = pillarMatch.group(1).trim();
        Matcher directionMatch = DIRECTION_LINE.matcher(userMessage);
        String direction = directionMatch.find() ? directionMatch.group(1).trim() : "held steady";

        String beforeWorking = firstLineOf(section(userMessage, "BEFORE — what's working"));
        String beforeImprove = firstLineOf(section(userMessage, "BEFORE — what can improve"));
        String afterWorking = firstLineOf(section(userMessage, "AFTER — what's working"));
        List<String> submissions = submissionLines(userMessage);

        ObjectNode root = JSON.createObjectNode();
        ArrayNode items = root.putArray("items");
        if (beforeWorking != null && afterWorking != null) {
            items.addObject().put("kind", "CARRIED_FORWARD").put("text",
                    "The earlier reading on " + pillar + " named \"" + quote(beforeWorking)
                            + "\" and the later one still shows it, now alongside \""
                            + quote(afterWorking) + "\".");
        } else if (beforeWorking != null) {
            items.addObject().put("kind", "CARRIED_FORWARD").put("text",
                    "\"" + quote(beforeWorking) + "\" was named as a strength on " + pillar
                            + " at the start, and nothing in the later material contradicts it.");
        }
        if (beforeImprove != null) {
            items.addObject().put("kind", "PERSISTED").put("text",
                    "The growth edge \"" + quote(beforeImprove) + "\" from the earlier "
                            + pillar + " reading still shows up, though how it appears has shifted.");
        }
        if (!submissions.isEmpty()) {
            items.addObject().put("kind", "NEW").put("text",
                    "Your submitted work on " + pillar + " shows something with no earlier "
                            + "equivalent — \"" + quote(submissions.get(0)) + "\".");
            if (submissions.size() > 1) {
                // A SECOND observation of a kind already present. The real model
                // routinely returns several of one kind — a mock that never
                // repeats one cannot exercise the grouped rendering, so local QA
                // would sign off a layout it never actually saw.
                items.addObject().put("kind", "NEW").put("text",
                        "A second strand with no earlier equivalent shows up in the same work: \""
                                + quote(submissions.get(submissions.size() / 2)) + "\".");
                items.addObject().put("kind", "RESOLVED").put("text",
                        "What the earlier reading flagged no longer appears as a block: your own "
                                + "work records \"" + quote(submissions.get(submissions.size() - 1))
                                + "\".");
            }
        } else if (afterWorking != null) {
            items.addObject().put("kind", "NEW").put("text",
                    "\"" + quote(afterWorking) + "\" appears in the later " + pillar
                            + " reading with no equivalent earlier.");
        }
        if (items.isEmpty()) {
            return SHIFT_NARRATIVE_DEFAULT;
        }
        // Non-empty even off the decline path — a blank close on a mocked
        // decline would read as a generation failure (same reason as the
        // static default's).
        root.put("closingAction", "declined".equals(direction)
                ? "Pick the smallest piece of the " + pillar
                        + " work still open and close it this week."
                : "Keep the cadence that produced the " + pillar + " material above.");
        return root.toString();
    }

    /**
     * The member growth breakdown, naming the ACTUAL pillars the request carries
     * — the rendered card then reads like a synthesis of this founder's pillars
     * rather than a stranger's.
     */
    private static String memberGrowthBreakdownFor(String userMessage) {
        List<String> pillars = new ArrayList<>();
        Matcher matcher = PILLAR_LINE.matcher(userMessage);
        while (matcher.find()) {
            pillars.add(matcher.group(1).trim());
        }
        if (pillars.isEmpty()) {
            return MEMBER_GROWTH_BREAKDOWN_DEFAULT;
        }
        String first = pillars.get(0);
        String last = pillars.get(pillars.size() - 1);
        String span = pillars.size() > 1 ? "across " + first + " and " + last : "in " + first;
        ObjectNode root = JSON.createObjectNode();
        ArrayNode items = root.putArray("items");
        items.addObject().put("kind", "CARRIED_FORWARD").put("text",
                "The ownership you described at intake still runs through most of your work, and "
                        + span + " it now shows up as a fixed cadence rather than something you "
                        + "reach for when time allows.");
        items.addObject().put("kind", "PERSISTED").put("text",
                "The distance between deciding and acting is still the recurring edge, and it "
                        + "surfaces " + span + " just as it did before.");
        if (pillars.size() > 2) {
            items.addObject().put("kind", "NEW").put("text",
                    "Naming a decision owner before a discussion starts appears across "
                            + pillars.get(1) + " and " + last + " with no equivalent earlier.");
        }
        return root.toString();
    }

    /** The lines of one titled block, up to the next blank line — null when absent or "(none recorded)". */
    private static List<String> section(String userMessage, String title) {
        int start = userMessage.indexOf(title + ":");
        if (start < 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : userMessage.substring(start + title.length() + 1).split("\n")) {
            String cleaned = line.strip();
            if (cleaned.isEmpty()) {
                // The title line ends in "\n", so the block's own content starts
                // on the NEXT line — a blank only terminates once content began.
                if (lines.isEmpty()) {
                    continue;
                }
                break;
            }
            if (!cleaned.startsWith("- ")) {
                break;
            }
            String item = cleaned.substring(2);
            if (!item.equals("(none recorded)")) {
                lines.add(item);
            }
        }
        return lines;
    }

    private static String firstLineOf(List<String> lines) {
        return lines.isEmpty() ? null : lines.get(0);
    }

    /** Content lines inside {@code <submission>} fences, in order. */
    private static List<String> submissionLines(String userMessage) {
        List<String> lines = new ArrayList<>();
        Matcher matcher = Pattern.compile("<submission>(.*?)</submission>", Pattern.DOTALL)
                .matcher(userMessage);
        while (matcher.find()) {
            for (String line : matcher.group(1).split("\n")) {
                String cleaned = line.strip();
                // Skip the table header — quoting column titles back as the
                // founder's own words is exactly the confusion the "(column
                // titles)" marker exists to prevent. And quote prose, not
                // plumbing: structured-JSON remnants read as a bug, not a mock.
                if (!cleaned.isEmpty() && !cleaned.contains("(column titles)")
                        && !cleaned.contains("{\"") && !cleaned.contains("[{")) {
                    lines.add(cleaned);
                }
            }
        }
        return lines;
    }

    /**
     * A quotable phrase, clipped the way the prompt asks the live model to quote
     * (a few words, verbatim). Splits on whitespace, keeps the first eight words,
     * and drops a trailing "label:" remnant so an exercise line quotes the
     * founder's value rather than the grid's column name.
     */
    private static String quote(String line) {
        String value = line.contains(": ") ? line.substring(line.indexOf(": ") + 2) : line;
        // Table rows are pipe-separated cells — quote the meatiest cell, the
        // way the live model is asked to quote the founder's prose.
        if (value.contains(" | ")) {
            String longest = "";
            for (String cell : value.split(" \\| ")) {
                if (cell.strip().length() > longest.length() && !cell.strip().equals("—")) {
                    longest = cell.strip();
                }
            }
            value = longest.isEmpty() ? value : longest;
        }
        String[] words = value.strip().split("\\s+");
        int keep = Math.min(words.length, 8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private static final String PILLAR_DEFAULT = """
            {
              "scorePercentage": 72,
              "whatThisScoreMeans": "Solid foundation in this area with clear, addressable growth edges.",
              "whatsWorking": ["Clear ownership of outcomes", "Consistent, structured reflection"],
              "whatCanImprove": ["Tighten feedback loops", "Translate insight into action faster"],
              "whyThisMattersForBusiness": "Faster learning here compounds directly into execution speed.",
              "evidence": [{"qid": "q1", "quote": "I review what worked and what didn't after each milestone."}]
            }
            """;

    /**
     * A shift narrative (§6). {@code closingAction} is deliberately non-empty:
     * a declining pillar MUST come back with a next step, and a mock that
     * returned "" would make every decline look like a generation failure on a
     * sandbox lane.
     */
    private static final String SHIFT_NARRATIVE_DEFAULT = """
            {
              "items": [
                {"kind": "PERSISTED",
                 "text": "The earlier assessment named ownership of outcomes as the strength here, \
            and the later text still leans on it, but the follow-through it described has thinned."},
                {"kind": "CARRIED_FORWARD",
                 "text": "Structured reflection shows up in both readings, and the later one describes \
            it running on a fixed cadence rather than when time allowed."},
                {"kind": "RESOLVED",
                 "text": "The hesitation about asking for help that the earlier text described no longer \
            appears; the later reading treats it as something already handled."},
                {"kind": "NEW",
                 "text": "Naming a decision owner before a discussion starts appears only in the later \
            reading, with no equivalent earlier."},
                {"kind": "FADED",
                 "text": "The appetite for open-ended exploration that the earlier text singled out is \
            absent from the later reading."},
                {"kind": "REGRESSED",
                 "text": "Protecting the first hour of the day was a strength in the earlier \
            reading; the later one describes it as the thing that keeps slipping."},
                {"kind": "EMERGED",
                 "text": "Fatigue late in the week appears as an edge only in the later reading, \
            with no equivalent earlier."}
              ],
              "closingAction": "Pick the one decision currently being revisited and close it this week, \
            writing down what would have to be true to reopen it."
            }
            """;

    /**
     * The member-level growth breakdown (V193) — all seven kinds, so a mocked
     * generation exercises every badge the card renders.
     */
    private static final String MEMBER_GROWTH_BREAKDOWN_DEFAULT = """
            {
              "items": [
                {"kind": "CARRIED_FORWARD",
                 "text": "The ownership you described at intake still runs through most of your \
            work, and across Discipline and Focus & Flow it now shows up as a fixed cadence \
            rather than something you reach for when time allows."},
                {"kind": "PERSISTED",
                 "text": "The distance between deciding and acting is still the recurring edge, \
            and it surfaces in the same pillars it did before."},
                {"kind": "RESOLVED",
                 "text": "The hesitation about asking for help that ran through the earlier \
            readings no longer appears anywhere in the later ones."},
                {"kind": "NEW",
                 "text": "Naming a decision owner before a discussion starts appears across \
            several pillars with no equivalent earlier."},
                {"kind": "FADED",
                 "text": "The appetite for open-ended exploration the earlier readings singled \
            out is absent from the later ones."},
                {"kind": "REGRESSED",
                 "text": "The clean separation between planning and delivery the earlier readings \
            singled out now reads as an edge in the later ones."},
                {"kind": "EMERGED",
                 "text": "Carrying too many parallel commitments shows up as an edge across the \
            later readings, with nothing equivalent earlier."}
              ]
            }
            """;

    /** The member-level growth summary's pre-V193 single-paragraph contract. */
    private static final String MEMBER_GROWTH_SUMMARY_DEFAULT = """
            {
              "summary": "The ownership described at intake is still the spine of the later \
            reading, and the reflection habit around it now runs on a fixed cadence rather than \
            when time allowed. What has not moved is the distance between deciding and acting."
            }
            """;

    /** The cohort-level growth report (redesign spec §4) — staff-facing, so it may cite figures. */
    private static final String COHORT_GROWTH_SUMMARY_DEFAULT = """
            {
              "overview": "The cohort moved up on average, but the gains are concentrated: a \
            few members carried most of the movement while the rest stayed close to where they started.",
              "sharedWins": ["Reflection habits are now on a cadence for most of the group",
                             "Ownership language shows up across the later readings"],
              "sharedRisks": ["Follow-through thins mid-cycle in several members",
                              "The lowest-scoring pillar did not move for anyone"],
              "recommendations": ["Run one working session on closing decisions rather than revisiting them",
                                  "Pair the members who moved most with those who stayed flat"]
            }
            """;

    private static final String OVERALL_SUMMARY_DEFAULT = """
            {
              "overallScorePercentage": 68,
              "summaryNarrative": "A capable founder building durable habits, with a few focused gaps to close.",
              "strengths": ["Outcome ownership", "Cadence of reflection"],
              "developmentAreas": ["Feedback loop latency", "Longer-horizon planning"],
              "corePattern": "Reflective and action-oriented, but follow-through lags insight.",
              "movingForward": "Pick one improvement, ship it, and measure the impact within two weeks."
            }
            """;

    private static final String TEAM_INSIGHT_DEFAULT = """
            {
              "teamThemes": {
                "commonStrengths": ["Shared sense of purpose"],
                "growthEdges": ["Slow feedback loops"],
                "patterns": ["Reflection without rapid follow-through"],
                "recommendations": ["Shorten retro-to-action cycles."]
              },
              "individualCoaching": [
                {"memberId": "00000000-0000-0000-0000-000000000000", "focusAreas": ["Handling Obstacles (20%) — lowest score in the entire team; obstacle is defined as a result rather than a root cause; emotional naming is rated Very Difficult; reframe is absent; no action step was produced", "Energy and Motivation (22%) — second lowest score in the team; no named pain driver, no vivid pleasure driver; motivation appears obligation-based with no described future state", "Listening Mindset (28%) — lowest listening score in the team; physiological observation layer is entirely undeveloped", "Focus and Flow (29%) — second lowest focus score; phone and messaging apps at very high distraction levels; zero shield tools in place", "Discipline (29%) — third lowest discipline score; cognitive fuel domain is inactive; physical movement is absent"], "suggestedActions": ["Start the obstacle navigation sequence with the single most pressing current challenge: write one sentence describing the obstacle using only observable language, name one emotion, then write one action with a deadline.", "Develop a written pain driver and a written pleasure driver describing what the business looks and feels like in two years."]},
                {"memberId": "00000000-0000-0000-0000-000000000001", "focusAreas": ["Curiosity (13%) — lowest curiosity score in the team; all three questions accepted the statistic as true rather than questioning the claim, its source, or its methodology", "Vision Mindset (25%) — second lowest vision score; no specific person served, no legacy impact; vision reads as a product category", "Handling Obstacles (45%) — obstacle definition is an internal state rather than a specific observable problem; reframe is stated but not articulated", "Focus and Flow (62%) — no focus tools in place; internal thoughts and anxiety are elevated; focus endurance is capped"], "suggestedActions": ["Practice the curiosity loop: for every claim, write down its source, its definition, and one way it could be wrong before accepting it.", "Rewrite the vision around a specific person served and the legacy impact on them."]},
                {"memberId": "00000000-0000-0000-0000-000000000002", "focusAreas": ["Energy and Motivation (54%) — pain driver is present but general; the self-regulation strategy exists but the vision pulling forward is not yet vivid", "Discipline (68%) — habits are forming but restorative practices are inconsistent", "Growth Mindset (74%) — receptive to feedback; occasionally defends rather than explores"], "suggestedActions": ["Make the pleasure driver concrete: describe the specific future day in the business that is worth the effort.", "Protect two keystone habits for 60 days before adding anything new."]}
              ],
              "benchmarking": {
                "teamVsPlatformComparison": "Slightly above platform median on ownership; notably below on the execution stack (Discipline, Focus, Handling Obstacles).",
                "outlierPillars": ["Curiosity — team sits well below platform average", "Handling Obstacles — team sits well below platform average"]
              }
            }
            """;
}
