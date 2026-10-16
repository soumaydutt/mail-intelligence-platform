package com.aash.mailguard.smtp;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SmtpResponse(
        int code,
        String enhancedStatusCode,
        String message,
        List<String> lines) {

    private static final Pattern ENHANCED_CODE_PATTERN = Pattern.compile("^([245]\\.[0-9]{1,3}\\.[0-9]{1,3})(?:\\s|$)");

    public SmtpResponse {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static SmtpResponse parse(List<String> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            throw new IllegalArgumentException("SMTP reply must have a final line");
        }
        int code = -1;
        for (int i = 0; i < rawLines.size(); i++) {
            String line = rawLines.get(i);
            if (line == null || line.length() < 4 || line.charAt(0) < '2' || line.charAt(0) > '5'
                    || line.charAt(1) < '0' || line.charAt(1) > '9'
                    || line.charAt(2) < '0' || line.charAt(2) > '9'
                    || line.charAt(3) != (i == rawLines.size() - 1 ? ' ' : '-')) {
                throw new IllegalArgumentException("Malformed SMTP reply status");
            }
            int lineCode = (line.charAt(0) - '0') * 100 + (line.charAt(1) - '0') * 10 + line.charAt(2) - '0';
            if (code != -1 && code != lineCode) {
                throw new IllegalArgumentException("SMTP multiline status mismatch");
            }
            code = lineCode;
        }
        String lastLine = rawLines.get(rawLines.size() - 1);
        String message = lastLine.length() > 4 ? lastLine.substring(4).trim() : "";
        Matcher matcher = ENHANCED_CODE_PATTERN.matcher(message);
        String enhanced = matcher.find() ? matcher.group(1) : null;
        return new SmtpResponse(code, enhanced, message, rawLines);
    }

    public boolean isPositiveCompletion() {
        return code >= 200 && code < 300;
    }

    public boolean isPositiveIntermediate() {
        return code >= 300 && code < 400;
    }

    public boolean isTransientNegative() {
        return code >= 400 && code < 500;
    }

    public boolean isPermanentNegative() {
        return code >= 500 && code < 600;
    }
}
