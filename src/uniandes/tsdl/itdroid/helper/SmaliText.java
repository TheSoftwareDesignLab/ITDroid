package uniandes.tsdl.itdroid.helper;

/**
 * The escaping of string literals in smali, in one place: the detector decodes a literal with
 * {@link #unescape}, the patcher writes its translation back with {@link #escape}, and a text only
 * survives the round trip if both agree character by character.
 */
public final class SmaliText {

    private SmaliText() {
    }

    /** Decodes the body of a smali string literal (without the surrounding quotes). */
    public static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'u' && i + 5 < s.length()) {
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) {
                        // not a valid escape; emit verbatim
                    }
                } else if (n == 'n' || n == 't' || n == 'r') {
                    sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : '\r');
                    i++;
                    continue;
                } else if (n == '"' || n == '\'' || n == '\\') {
                    sb.append(n);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Encodes a text as the body of a smali string literal: quotes, backslashes and control characters escaped, non-ASCII as {@code \\uXXXX}. */
    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
