package rexxjvm;

import java.io.File;
import java.util.Locale;

/**
 * Windows paths as the module sees them. The interpreter is a Unix one (WASI): '/' as
 * the separator, ':' in PATH. On Windows each drive is a directory of the module's
 * file system, as in MSYS and Git Bash: {@code C:\Users\x} is {@code /c/Users/x}
 * (Run preopens every root as /letter). The host translates at the borders: the
 * current directory, PATH, the program's name on the command line, the interpreter's
 * own paths (.RexxInfo), the paths of the host functions (FileSys), and commands'
 * names, directory and PATH (Host). Elsewhere every method gives its argument back.
 *
 * <p>What the program builds itself from these paths stays in the module's form: a
 * command given {@code /c/x} (cmd.exe's {@code type /c/x}) does not understand it.
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
