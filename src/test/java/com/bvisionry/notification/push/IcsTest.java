package com.bvisionry.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of RFC 5545 a calendar client actually enforces: a REQUEST that
 * names who it is from and who it is for, a sequence that rises when a session
 * moves, and lines short enough (and whole enough) to survive the parse.
 */
class IcsTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-09-14T10:00:00Z");
    private static final Instant END = Instant.parse("2026-09-14T10:45:00Z");
    private static final Ics.Person COACH = new Ics.Person("Jordan Lee", "coach@test.invalid");
    private static final Ics.Person MEMBER = new Ics.Person("Alex Founder", "alex@test.invalid");

    @Test
    @DisplayName("a REQUEST names the organizer, the one recipient, and a sequence")
    void aRequestIsAddressed() {
        List<String> lines = lines(Ics.event(SESSION, START, END, "Coaching 1:1", "Details",
                COACH, MEMBER));

        assertThat(lines).contains(
                "ORGANIZER;CN=\"Jordan Lee\":mailto:coach@test.invalid",
                "ATTENDEE;RSVP=TRUE;CN=\"Alex Founder\":mailto:alex@test.invalid",
                "UID:" + SESSION + "@bvisionry",
                "DTSTART:20260914T100000Z");
        // Epoch seconds of the start: monotonic across reschedules, no column needed.
        assertThat(lines).contains("SEQUENCE:" + START.getEpochSecond());
    }

    @Test
    @DisplayName("a moved session keeps the UID and raises the sequence")
    void aMoveRaisesTheSequence() {
        Instant later = START.plusSeconds(3600);

        List<String> before = lines(Ics.event(SESSION, START, END, "s", "d", COACH, MEMBER));
        List<String> after = lines(Ics.event(SESSION, later, later.plusSeconds(2700), "s", "d",
                COACH, MEMBER));

        assertThat(uid(after)).isEqualTo(uid(before));
        assertThat(sequence(after)).isGreaterThan(sequence(before));
    }

    @Test
    @DisplayName("a name with a comma or a quote cannot break out of the CN parameter")
    void parameterValuesAreQuoted() {
        List<String> lines = lines(Ics.event(SESSION, START, END, "s", "d",
                new Ics.Person("Lee, Jordan \"JJ\"", "coach@test.invalid"), MEMBER));

        // Quoted, so the comma is data; the DQUOTE is replaced, since RFC 5545
        // parameter values cannot carry one at all.
        assertThat(lines).contains("ORGANIZER;CN=\"Lee, Jordan 'JJ'\":mailto:coach@test.invalid");
    }

    @Test
    @DisplayName("text values keep their RFC 5545 escaping")
    void textValuesAreEscaped() {
        List<String> lines = lines(Ics.event(SESSION, START, END, "a;b,c", "line\nbreak",
                COACH, MEMBER));

        assertThat(lines).contains("SUMMARY:a\\;b\\,c", "DESCRIPTION:line\\nbreak");
    }

    @Test
    @DisplayName("long lines fold at 75 octets without splitting a character")
    void longLinesFold() {
        String accented = "é".repeat(200);

        byte[] ics = Ics.event(SESSION, START, END, accented, "d", COACH, MEMBER);

        for (String line : new String(ics, StandardCharsets.UTF_8).split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        // Unfolding (drop the CRLF + leading space) gives the value back intact.
        assertThat(new String(ics, StandardCharsets.UTF_8).replace("\r\n ", ""))
                .contains("SUMMARY:" + accented);
    }

    @Test
    @DisplayName("a recipient with no email leaves the ATTENDEE line out rather than emitting mailto:")
    void anEmptyAddressIsOmitted() {
        List<String> lines = lines(Ics.event(SESSION, START, END, "s", "d",
                COACH, new Ics.Person("Nobody", "")));

        assertThat(lines).noneMatch(line -> line.startsWith("ATTENDEE"));
    }

    private static List<String> lines(byte[] ics) {
        return Arrays.asList(new String(ics, StandardCharsets.UTF_8).split("\r\n"));
    }

    private static String uid(List<String> lines) {
        return lines.stream().filter(l -> l.startsWith("UID:")).findFirst().orElseThrow();
    }

    private static long sequence(List<String> lines) {
        return lines.stream().filter(l -> l.startsWith("SEQUENCE:")).findFirst()
                .map(l -> Long.parseLong(l.substring("SEQUENCE:".length())))
                .orElseThrow();
    }
}
