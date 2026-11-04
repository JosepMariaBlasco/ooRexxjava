package rexxjvm;

import java.io.File;
import java.util.Locale;

/**
 * Windows paths as the module sees them. The interpreter is a Unix one (WASI): '/' as
 * the separator, ':' in PATH. On Windows each drive is a directory of the module's
 * file system, as in MSYS and Git Bash: {@code C:\Users\x} is {@code /c/Users/x}
 * (Run preopens every root as /letter). The host translates at the borders: the
 * current directory, PATH, the program's name on the command line, the interpreter's
 * own paths (.RexxInfo), the paths of the host functions (FileSys), HOME and TMPDIR
 * (Run), and commands' names, directory, PATH, HOME, TMPDIR and the drives' paths in
 * their command lines (Host, commandToHost). Elsewhere every method gives its argument
 * back.
 */
final class WinPath {
    static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private WinPath() {}

    /** A drive's directory in the module: "C:\" is "/c". */
    static String drive(char letter) {
        return "/" + Character.toLowerCase(letter);
    }

    /** A host path in the module's form: C:\a\b is /c/a/b, a\b is a/b; UNC paths as //server/share. */
    static String toGuest(String host) {
        if (!WINDOWS || host == null) return host;
        String s = host.replace('\\', '/');
        if (s.length() >= 2 && s.charAt(1) == ':' && isLetter(s.charAt(0))) {
            String rest = s.substring(2);
            if (rest.isEmpty() || rest.equals("/")) return drive(s.charAt(0));
            return drive(s.charAt(0)) + (rest.startsWith("/") ? rest : "/" + rest);
        }
        return s;
    }

    /**
     * A path of the module's in the host's form: /c/a/b is C:\a\b, /c is C:\, a/b is a\b.
     * An absolute path outside the drives (/tmp) stays as it is: the host has none such,
     * and fails on it as the module would.
     */
    static String toHost(String guest) {
        if (!WINDOWS || guest == null) return guest;
        if (guest.length() >= 2 && guest.charAt(0) == '/' && isLetter(guest.charAt(1))
            && (guest.length() == 2 || guest.charAt(2) == '/')) {
            String rest = guest.length() > 3 ? guest.substring(3) : "";
            return Character.toUpperCase(guest.charAt(1)) + ":\\" + rest.replace('/', '\\');
        }
        if (guest.startsWith("//")) return guest.replace('/', '\\');      // UNC
        if (guest.startsWith("/")) return guest;
        return guest.replace('/', '\\');
    }

    /** A host path list (';') in the module's (':'): C:\a;D:\b is /c/a:/d/b. */
    static String listToGuest(String host) {
        if (!WINDOWS || host == null) return host;
        StringBuilder b = new StringBuilder();
        for (String e : host.split(";", -1)) {
            if (b.length() > 0) b.append(':');
            b.append(toGuest(unquote(e)));
        }
        return b.toString();
    }

    /** A path list of the module's (':') in the host's form (';'). */
    static String listToHost(String guest) {
        if (!WINDOWS || guest == null) return guest;
        StringBuilder b = new StringBuilder();
        for (String e : guest.split(":", -1)) {
            if (b.length() > 0) b.append(';');
            b.append(toHost(e));
        }
        return b.toString();
    }

    /**
     * A command line for cmd.exe, with the module's absolute paths in the host's form:
     * every word that is a path on a drive ({@code /c} or {@code /c/...}, the drive being
     * one of the host's) becomes {@code C:\...}. A word starts the line, or follows a
     * blank, a quote, {@code < > | & ( =}; a quoted one runs to its closing quote
     * (blanks and all), an unquoted one to the next blank, quote or {@code < > | & )}.
     * So {@code "/d/a/rexx.cmd" prog < /d/in} is {@code "D:\a\rexx.cmd" prog < D:\in}.
     * cmd.exe's switches ({@code dir /s}, {@code /c:x}) are not paths: no drive's name
     * is followed by '/' there, or the word is not a drive's.
     */
    static String commandToHost(String command) {
        if (!WINDOWS || command == null || command.indexOf('/') < 0) return command;
        StringBuilder b = new StringBuilder(command.length());
        int n = command.length();
        int i = 0;
        while (i < n) {
            char c = command.charAt(i);
            char prev = i == 0 ? ' ' : command.charAt(i - 1);
            if (c == '/' && i + 1 < n && isLetter(command.charAt(i + 1))
                && (i == 0 || " \t\"'<>|&(=".indexOf(prev) >= 0)
                && (i + 2 == n || "/\"' \t<>|&)".indexOf(command.charAt(i + 2)) >= 0)
                && isDrive(command.charAt(i + 1))) {
                boolean quoted = prev == '"' || prev == '\'';
                int end = i + 2;
                if (quoted) {
                    while (end < n && command.charAt(end) != prev) end++;
                } else {
                    while (end < n && " \t\"'<>|&)".indexOf(command.charAt(end)) < 0) end++;
                }
                b.append(toHost(command.substring(i, end)));
                i = end;
                continue;
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }

    /** An argument of a command run without a shell: a drive's path ({@code /c/x}) in the host's form. */
    static String argToHost(String arg) {
        if (!WINDOWS || arg == null || arg.length() < 2 || arg.charAt(0) != '/' || !isLetter(arg.charAt(1))
            || (arg.length() > 2 && arg.charAt(2) != '/') || !isDrive(arg.charAt(1))) return arg;
        return toHost(arg);
    }

    /** Whether the host has a drive with this letter. */
    static boolean isDrive(char letter) {
        char l = Character.toUpperCase(letter);
        for (File r : roots()) {
            String s = r.getPath();
            if (s.length() >= 2 && s.charAt(1) == ':' && Character.toUpperCase(s.charAt(0)) == l) return true;
        }
        return false;
    }

    /** "C:\Program Files" in PATH may come quoted. */
    private static String unquote(String e) {
        return e.length() >= 2 && e.startsWith("\"") && e.endsWith("\"") ? e.substring(1, e.length() - 1) : e;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** The drives to preopen, as root directories ("C:\", "D:\"). */
    static File[] roots() {
        File[] r = File.listRoots();
        return r == null ? new File[0] : r;
    }
}
