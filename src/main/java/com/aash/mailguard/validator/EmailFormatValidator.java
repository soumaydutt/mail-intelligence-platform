package com.aash.mailguard.validator;

import java.net.IDN;
import java.text.Normalizer;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class EmailFormatValidator {
    public enum Reason {
        VALID, EMPTY_ADDRESS, ADDRESS_TOO_LONG, MISSING_AT, MULTIPLE_AT, EMPTY_LOCAL_PART,
        INVALID_LOCAL_PART, UNSUPPORTED_LOCAL_UNICODE, LOCAL_TOO_LONG, EMPTY_DOMAIN,
        INVALID_DOMAIN_LABEL, INVALID_IDNA, DOMAIN_TOO_LONG, UNSUPPORTED_CHARACTER
    }

    public record Result(Reason reason, String domain) {
        public boolean valid() {
            return reason == Reason.VALID;
        }
    }

    private static final Result[] REJECTED = new Result[Reason.values().length];

    static {
        for (Reason reason : Reason.values()) {
            REJECTED[reason.ordinal()] = new Result(reason, null);
        }
    }

    private static Result reject(Reason reason) {
        return REJECTED[reason.ordinal()];
    }

    public Result validate(String email) {
        if (email == null || email.isEmpty()) {
            return reject(Reason.EMPTY_ADDRESS);
        }
        // An early UTF-16 bound keeps even bulk callers from scanning oversized input.
        if (email.length() > 254) {
            return reject(Reason.ADDRESS_TOO_LONG);
        }
        if (email.isBlank()) {
            return reject(Reason.EMPTY_ADDRESS);
        }

        int at;
        if (email.charAt(0) == '"') {
            int i = 1;
            for (; i < email.length(); i++) {
                char c = email.charAt(i);
                if (c == '\\') {
                    if (++i == email.length()) {
                        return reject(Reason.INVALID_LOCAL_PART);
                    }
                    c = email.charAt(i);
                } else if (c == '"') {
                    break;
                }
                if (c < ' ' || c > '~') {
                    return reject(c >= 128 && !Character.isISOControl(c)
                            ? Reason.UNSUPPORTED_LOCAL_UNICODE : Reason.UNSUPPORTED_CHARACTER);
                }
            }
            if (i == email.length()) {
                return reject(Reason.INVALID_LOCAL_PART);
            }
            if (i == 1) {
                return reject(Reason.EMPTY_LOCAL_PART);
            }
            at = i + 1;
            if (at == email.length() || email.charAt(at) != '@') {
                return reject(Reason.INVALID_LOCAL_PART);
            }
        } else {
            at = email.indexOf('@');
            if (at < 0) {
                return reject(Reason.MISSING_AT);
            }
            if (at == 0) {
                return reject(Reason.EMPTY_LOCAL_PART);
            }
            boolean dot = true;
            for (int i = 0; i < at; i++) {
                char c = email.charAt(i);
                if (c == '.') {
                    if (dot) {
                        return reject(Reason.INVALID_LOCAL_PART);
                    }
                    dot = true;
                } else if (atom(c)) {
                    dot = false;
                } else if (c <= ' ' || c == 127 || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                    return reject(Reason.UNSUPPORTED_CHARACTER);
                } else if (c > '~') {
                    return reject(Reason.UNSUPPORTED_LOCAL_UNICODE);
                } else {
                    return reject(Reason.INVALID_LOCAL_PART);
                }
            }
            if (dot) {
                return reject(Reason.INVALID_LOCAL_PART);
            }
        }

        if (at > 64) {
            return reject(Reason.LOCAL_TOO_LONG);
        }
        if (at == email.length() - 1) {
            return reject(Reason.EMPTY_DOMAIN);
        }
        if (email.indexOf('@', at + 1) >= 0) {
            return reject(Reason.MULTIPLE_AT);
        }
        return domain(email, at);
    }

    private static boolean atom(char c) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
            return true;
        }
        return "!#$%&'*+-/=?^_`{|}~".indexOf(c) >= 0;
    }

    private static Result domain(String email, int at) {
        StringBuilder converted = null;
        int bytes = 0;
        int start = at + 1;
        int labels = 0;
        for (int i = start; i <= email.length(); i++) {
            if (i < email.length() && email.charAt(i) != '.') {
                continue;
            }
            if (i == start) {
                return reject(Reason.INVALID_DOMAIN_LABEL);
            }
            boolean numericLabel = true;
            boolean unicode = false;
            for (int j = start; j < i; ) {
                int c = email.codePointAt(j);
                if (c < '0' || c > '9') {
                    numericLabel = false;
                }
                if (c < 128) {
                    if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                            || c >= '0' && c <= '9' || c == '-')) {
                        return reject(c <= ' ' || c == 127 ? Reason.UNSUPPORTED_CHARACTER : Reason.INVALID_DOMAIN_LABEL);
                    }
                } else {
                    int type = Character.getType(c);
                    if (!Character.isLetterOrDigit(c)
                            && !((type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK)
                                    && j > start)) {
                        return reject(Character.isWhitespace(c) || Character.isSpaceChar(c)
                                || type == Character.FORMAT || type == Character.CONTROL
                                ? Reason.UNSUPPORTED_CHARACTER : Reason.INVALID_DOMAIN_LABEL);
                    }
                    unicode = true;
                }
                j += Character.charCount(c);
            }
            if (email.charAt(start) == '-' || email.charAt(i - 1) == '-') {
                return reject(Reason.INVALID_DOMAIN_LABEL);
            }
            if (i == email.length() && numericLabel) {
                return reject(Reason.INVALID_DOMAIN_LABEL);
            }

            int labelBytes = i - start;
            if (!unicode && labelBytes > 63) {
                return reject(Reason.INVALID_DOMAIN_LABEL);
            }
            String wire = null;
            if (unicode || email.regionMatches(true, start, "xn--", 0, 4)) {
                String label = email.substring(start, i);
                wire = label;
                try {
                    if (unicode) {
                        String canonical = Normalizer.normalize(label, Normalizer.Form.NFC);
                        wire = IDN.toASCII(canonical, IDN.USE_STD3_ASCII_RULES);
                        if (!Normalizer.normalize(IDN.toUnicode(wire), Normalizer.Form.NFC)
                                .equalsIgnoreCase(canonical)) {
                            return reject(Reason.INVALID_IDNA);
                        }
                    } else {
                        String decoded = IDN.toUnicode(label);
                        if (decoded.equalsIgnoreCase(label)
                                || !IDN.toASCII(decoded, IDN.USE_STD3_ASCII_RULES).equalsIgnoreCase(label)) {
                            return reject(Reason.INVALID_IDNA);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    return reject(Reason.INVALID_IDNA);
                }
                labelBytes = wire.length();
                if (labelBytes > 63) {
                    return reject(Reason.INVALID_DOMAIN_LABEL);
                }
            }

            bytes += labelBytes + (labels++ > 0 ? 1 : 0);
            if (bytes > 253) {
                return reject(Reason.DOMAIN_TOO_LONG);
            }
            boolean firstConversion = unicode && converted == null;
            if (firstConversion) {
                converted = new StringBuilder(email.length() - at - 1 + 16);
                converted.append(email, at + 1, start);
            }
            if (converted != null) {
                if (labels > 1 && !firstConversion) {
                    converted.append('.');
                }
                if (wire == null) {
                    converted.append(email, start, i);
                } else {
                    converted.append(wire);
                }
            }
            start = i + 1;
        }
        if (labels < 2) {
            return reject(Reason.INVALID_DOMAIN_LABEL);
        }
        if (at + 1 + bytes > 254) {
            return reject(Reason.ADDRESS_TOO_LONG);
        }
        String domain = converted == null ? email.substring(at + 1) : converted.toString();
        return new Result(Reason.VALID, domain.toLowerCase(Locale.ROOT));
    }
}
