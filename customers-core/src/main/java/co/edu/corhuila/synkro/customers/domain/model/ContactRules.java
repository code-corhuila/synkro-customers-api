package co.edu.corhuila.synkro.customers.domain.model;

/**
 * HU-CLI-05 contact rules. Each method returns {@code null} when the value is acceptable, otherwise the stable
 * issue code (required, too_long, invalid_format). Values are validated exactly as sent: nothing is trimmed,
 * lower-cased, normalised or completed.
 */
public final class ContactRules {
    public static final int EMAIL_MAX = 255, PHONE_DIGITS = 10, ADDRESS_MAX = 255, LOCAL_MAX = 64, DOMAIN_MAX = 253, LABEL_MAX = 63;
    private static final String ATEXT_SYMBOLS = "!#$%&'*+/=?^_`{|}~-";
    private ContactRules() {}

    /** Blank = every code point is whitespace, a Unicode space separator, a control or a format (zero-width) character. */
    public static boolean blank(String value) {
        return value == null || value.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                || Character.getType(cp) == Character.CONTROL || Character.getType(cp) == Character.FORMAT);
    }

    public static String email(String email) {
        if (blank(email)) return "required";
        if (email.codePointCount(0, email.length()) > EMAIL_MAX) return "too_long";
        int at = email.indexOf('@');
        if (at < 1 || at != email.lastIndexOf('@')) return "invalid_format";
        String local = email.substring(0, at), domain = email.substring(at + 1);
        if (local.length() > LOCAL_MAX || !validLocal(local)) return "invalid_format";
        return validDomain(domain) ? null : "invalid_format";
    }

    private static boolean validLocal(String local) {
        if (local.charAt(0) == '.' || local.charAt(local.length() - 1) == '.' || local.contains("..")) return false;
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (!(ascii(c) && (Character.isLetterOrDigit(c) || c == '.' || ATEXT_SYMBOLS.indexOf(c) >= 0))) return false;
        }
        return true;
    }

    private static boolean validDomain(String domain) {
        if (domain.isEmpty() || domain.length() > DOMAIN_MAX) return false;
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) return false;
        for (String label : labels) {
            if (label.isEmpty() || label.length() > LABEL_MAX || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') return false;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!(ascii(c) && (Character.isLetterOrDigit(c) || c == '-'))) return false;
            }
        }
        String tld = labels[labels.length - 1];
        return tld.length() >= 2 && !tld.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    public static String phone(String phone) {
        if (blank(phone)) return "required";
        if (phone.length() != PHONE_DIGITS) return "invalid_format";
        for (int i = 0; i < phone.length(); i++) if (phone.charAt(i) < '0' || phone.charAt(i) > '9') return "invalid_format";
        return null;
    }

    public static String address(String address) {
        if (blank(address)) return "required";
        return address.codePointCount(0, address.length()) > ADDRESS_MAX ? "too_long" : null;
    }

    private static boolean ascii(char c) { return c < 128; }
}
