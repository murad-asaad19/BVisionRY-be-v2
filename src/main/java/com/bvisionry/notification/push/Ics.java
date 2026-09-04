package com.bvisionry.notification.push;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A one-event RFC 5545 calendar file, so the booking confirmation email lands
 * in the recipient's calendar rather than only in their inbox (spec §7).
 *
 * <p><strong>Why it lives in {@code notification} and not next to the booking
 * it describes.</strong> The only consumer is the email handler below, and the
 * ArchUnit ratchet forbids a new {@code notification -> coaching} import — so
 * putting it in the coaching slice would either need a third home in
 * {@code common} for one caller, or bytes smuggled through the event payload.
 *
 * <p>Hand-rolled rather than pulled in as a dependency: a single VEVENT with
 * fixed UTC timestamps is a few dozen lines, and the only genuinely subtle
 * parts — escaping {@code \ ; ,} and newlines in text values, quoting parameter
 * values, folding at 75 octets — are one method each. Add a library the day
 * this needs recurrence or alarms.
 */
final class Ics {

    private Ics() {}

    /** A calendar user: the coach who organises the session, or one recipient. */
    record Person(String name, String email) {}

    /** RFC 5545 UTC form: {@code 20260904T100000Z}. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** RFC 5545 §3.1: content lines are folded at 75 octets, excluding the CRLF. */
    private static final int MAX_OCTETS = 75;

    /**
     * One VEVENT addressed from {@code organizer} to {@code attendee}, so the
     * file is a real REQUEST rather than an unaddressed one a strict client
     * refuses to import. Build it once per RECIPIENT — the ATTENDEE is the
     * person this copy is for.
     *
     * <p>The UID is the session, identical in every mail about it, which is
     * what makes a move REPLACE the entry in a calendar instead of adding a
     * second one beside it.
     */
    static byte[] event(UUID sessionId, Instant startsAt, Instant endsAt,
                        String summary, String description,
                        Person organizer, Person attendee) {
        List<String> lines = new ArrayList<>(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Bvisionry//Coaching//EN",
                "CALSCALE:GREGORIAN",
                "METHOD:REQUEST",
                "BEGIN:VEVENT",
                "UID:" + sessionId + "@bvisionry",
                "DTSTAMP:" + STAMP.format(Instant.now()),
                "DTSTART:" + STAMP.format(startsAt),
                "DTEND:" + STAMP.format(endsAt),
                // The start instant IS the revision counter: it rises on every
                // reschedule, so a client accepts the newer file and no column
                // has to track a sequence. (INTEGER-safe until 2038.)
                "SEQUENCE:" + startsAt.getEpochSecond(),
                "SUMMARY:" + escape(summary),
                "DESCRIPTION:" + escape(description)));
        address(lines, "ORGANIZER", "", organizer);
        address(lines, "ATTENDEE", ";RSVP=TRUE", attendee);
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");

        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            body.append(fold(line)).append("\r\n");
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** No email, no line: a CAL-ADDRESS with nothing after {@code mailto:} is invalid. */
    private static void address(List<String> lines, String property, String params, Person person) {
        if (person == null || person.email() == null || person.email().isBlank()) {
            return;
        }
        String cn = person.name() == null || person.name().isBlank()
                ? "" : ";CN=" + quoted(person.name());
        lines.add(property + params + cn + ":mailto:" + person.email().trim());
    }

    /**
     * RFC 5545 §3.3.11 TEXT escaping. Backslash first — escaping it after the
     * others would double-escape the backslashes they just introduced.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    /**
     * A PARAMETER value (RFC 5545 §3.1), which is not TEXT: there are no
     * backslash escapes here, so a name containing {@code ; : ,} has to be
     * DQUOTE-quoted instead — and a DQUOTE itself cannot appear at all, quoted
     * or not. Always quote, so one rule covers every name.
     */
    private static String quoted(String value) {
        return '"' + value.replace("\"", "'").replaceAll("\\p{Cntrl}", " ") + '"';
    }

    /**
     * RFC 5545 §3.1 folding: CRLF plus one space every 75 octets. Counted in
     * UTF-8 bytes rather than chars, and never mid-character — a split code
     * point is a corrupt file, and names carry accents.
     */
    private static String fold(String line) {
        StringBuilder out = new StringBuilder(line.length() + 8);
        int octets = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int width = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (octets + width > MAX_OCTETS) {
                out.append("\r\n ");
                octets = 1;
            }
            out.appendCodePoint(cp);
            octets += width;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
