package rexxjvm;

import run.endive.runtime.HostFunction;
import run.endive.wasm.types.ValType;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The local time zone, for the module's localtime(), mktime() and tzname
 * (compat/orx_wasi_stubs.c): wasi-libc has no time zones, so without this every local
 * time would be UTC. The zone is the module's TZ, as the C library reads it natively,
 * each time (a program may change TZ with VALUE(..., 'ENVIRONMENT')):
 * <ul>
 * <li>unset: the JVM's default zone (the host's: on Windows, its time zone
 *     setting; on Linux, TZ as the JVM got it, or /etc/localtime);</li>
 * <li>a POSIX zone without rules, {@code NAME[+-]h[:mm[:ss]]} ({@code UTC0},
 *     {@code EST5}, {@code <+0530>-5:30}): that fixed offset, west positive as POSIX
 *     says; with rules after it ({@code CET-1CEST,M3.5.0,M10.5.0/3}), its standard
 *     offset only: the rules are not read;</li>
 * <li>a name of the tz database, with or without a leading {@code :}
 *     ({@code Europe/Madrid}, {@code :America/New_York}, {@code EST5EDT}): that zone;</li>
 * <li>empty, or anything else: UTC, as glibc does.</li>
 * </ul>
 * Under the warm JVM, a run with TZ unset gets the server's default zone: on the same
 * host, the same as the client's unless the client changed it otherwise.
 */
final class TimeZones {
    private TimeZones() {}

    /** A zone as the C library sees it: its rules and its two names (tzname). */
    private static final class Zone {
        final ZoneRules rules; final String standard, daylight;
        Zone(ZoneRules rules, String standard, String daylight) {
            this.rules = rules; this.standard = standard; this.daylight = daylight;
        }
    }

    private static final Map<String, Zone> zones = new ConcurrentHashMap<>();
    private static final Pattern POSIX =
        Pattern.compile("([A-Za-z]{3,}|<[^>]+>)([+-]?)(\\d{1,2})(?::(\\d{2}))?(?::(\\d{2}))?(.*)");
    private static final Zone UTC = new Zone(ZoneOffset.UTC.getRules(), "UTC", "UTC");

    static List<HostFunction> functions() {
        return List.of(
            // (seconds since the epoch, TZ or 0) -> the offset east of UTC in seconds,
            // times 2, plus 1 if daylight saving time is in effect then
            new HostFunction("rexxjvm", "tz_offset", List.of(ValType.I64, ValType.I32), List.of(ValType.I64),
                (inst, a) -> {
                    Zone z = zone(a[1] == 0 ? null : inst.memory().readCString((int) a[1], StandardCharsets.UTF_8));
                    Instant t = Instant.ofEpochSecond(a[0]);
                    long offset = z.rules.getOffset(t).getTotalSeconds();
                    return new long[] { offset * 2 + (z.rules.isDaylightSavings(t) ? 1 : 0) };
                }),
            // (TZ or 0, 0 standard / 1 daylight, buffer, size) -> 1, or 0 if it does not fit
            new HostFunction("rexxjvm", "tz_name",
                List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32),
                (inst, a) -> {
                    Zone z = zone(a[0] == 0 ? null : inst.memory().readCString((int) a[0], StandardCharsets.UTF_8));
                    return new long[] { Host.put(inst.memory(), (int) a[2], (int) a[3],
                                                 a[1] != 0 ? z.daylight : z.standard) };
                }));
    }

    /** The key of TZ unset (an empty TZ is UTC, as for glibc). */
    private static final String UNSET = "\0";

    static Zone zone(String tz) {
        return zones.computeIfAbsent(tz == null ? UNSET : tz, TimeZones::parse);
    }

    private static Zone parse(String tz) {
        if (tz.equals(UNSET)) return named(ZoneId.systemDefault());
        if (tz.isEmpty()) return UTC;
        String id = tz.startsWith(":") ? tz.substring(1) : tz;
        Matcher m = POSIX.matcher(tz);
        // POSIX first: "GMT+1" is one hour west there, east for ZoneId; but a tz
        // database name that only looks like one (EST5EDT) is that zone
        if (m.matches() && !isZoneId(tz)) {
            int seconds = Integer.parseInt(m.group(3)) * 3600
                + (m.group(4) == null ? 0 : Integer.parseInt(m.group(4)) * 60)
                + (m.group(5) == null ? 0 : Integer.parseInt(m.group(5)));
            if (seconds > 24 * 3600) return UTC;
            int east = m.group(2).equals("-") ? seconds : -seconds;
            String name = m.group(1).startsWith("<") ? m.group(1).substring(1, m.group(1).length() - 1) : m.group(1);
            return new Zone(ZoneOffset.ofTotalSeconds(east).getRules(), name, name);
        }
        try {
            return named(ZoneId.of(id));
        } catch (DateTimeException e) {
            return UTC;
        }
    }

    private static boolean isZoneId(String s) {
        try {
            ZoneId.of(s);
            return !s.matches("(?i)(UTC|GMT|UT)[+-].*");   // ZoneId's own offsets: POSIX's sign wins
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static Zone named(ZoneId id) {
        if (id instanceof ZoneOffset) {
            String n = id.equals(ZoneOffset.UTC) ? "UTC" : id.getId();
            return new Zone(id.getRules(), n, n);
        }
        TimeZone tz = TimeZone.getTimeZone(id);
        String std = tz.getDisplayName(false, TimeZone.SHORT, Locale.US);
        String dst = tz.useDaylightTime() || id.getRules().getTransitions().size() > 0
            ? tz.getDisplayName(true, TimeZone.SHORT, Locale.US) : std;
        return new Zone(id.getRules(), std, dst);
    }
}
