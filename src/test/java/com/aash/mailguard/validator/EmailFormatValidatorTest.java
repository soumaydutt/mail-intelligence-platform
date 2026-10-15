package com.aash.mailguard.validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static com.aash.mailguard.validator.EmailFormatValidator.Reason.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class EmailFormatValidatorTest {
    private final EmailFormatValidator validator = new EmailFormatValidator();

    @ParameterizedTest
    @CsvSource({
            "a@example.test, example.test",
            "first.last@example.test, example.test",
            "first+tag@sub.example.test, sub.example.test",
            "user@127.0.0.test, 127.0.0.test",
            "a_b-c!d@example.test, example.test",
            "user@EXAMPLE.TEST, example.test",
            "user@Bücher.Test, xn--bcher-kva.test",
            "user@xn--bcher-kva.test, xn--bcher-kva.test",
            "user@Xn--BcHeR-KvA.TeSt, xn--bcher-kva.test"
    })
    void acceptsDotAtomsAndNormalizesDomain(String email, String domain) {
        assertValid(email, domain);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"first last\"@example.test",
            "\"a@b\"@example.test",
            "\"a\\\"b\"@example.test",
            "\"a\\\\b\"@example.test",
            "\"a b\\@c\"@example.test"
    })
    void acceptsWholeQuotedAsciiLocalParts(String email) {
        assertValid(email, "example.test");
    }

    @Test
    void acceptsRfc5322AtextSymbols() {
        assertValid("!#$%&'*+-/=?^_`{|}~@example.test", "example.test");
    }

    @Test
    void normalizesCombiningMarksAndRejectsLeadingMarks() {
        assertValid("user@bu\u0308cher.test", "xn--bcher-kva.test");
        assertInvalid("user@\u0308bucher.test", INVALID_DOMAIN_LABEL);
    }

    @ParameterizedTest
    @MethodSource("invalidWithReasons")
    void reportsStableReasons(String email, EmailFormatValidator.Reason reason) {
        assertInvalid(email, reason);
    }

    private static Stream<Arguments> invalidWithReasons() {
        return Stream.of(
                arguments(null, EMPTY_ADDRESS),
                arguments("", EMPTY_ADDRESS),
                arguments(" \t ", EMPTY_ADDRESS),
                arguments("missing-at", MISSING_AT),
                arguments("a\r\n@example.test", UNSUPPORTED_CHARACTER),
                arguments("@example.test", EMPTY_LOCAL_PART),
                arguments("\"\"@example.test", EMPTY_LOCAL_PART),
                arguments("user@", EMPTY_DOMAIN),
                arguments("user@example@test", MULTIPLE_AT),
                arguments("user..name@example.test", INVALID_LOCAL_PART),
                arguments("müller@example.test", UNSUPPORTED_LOCAL_UNICODE),
                arguments("user@-example.test", INVALID_DOMAIN_LABEL),
                arguments("user@faß.test", INVALID_IDNA),
                arguments("user@xn--a.test", INVALID_IDNA),
                arguments("user@127.0.0.1", INVALID_DOMAIN_LABEL),
                arguments("user@example.123", INVALID_DOMAIN_LABEL)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".user@example.test", "user.@example.test", "user..name@example.test",
            "user@example..test", "user@example.test.", "user@example", "user@.test",
            "user@example-.test", "user@exa_mple.test",
            "a@b@c.test", "\"a@b\"@c@test", "user name@example.test",
            "user@exa mple.test", "user\u00a0name@example.test", "user@\u2003example.test",
            "a\r@example.test", "a\n@example.test", "a\r\n@example.test",
            "a\0@example.test", "a\177@example.test", "\"a\nb\"@example.test",
            "\"unfinished@example.test", "\"a\"b@example.test",
            "\"a\\\"@example.test", "a\"b@example.test",
            "müller@example.test", "\"müller\"@example.test", "user@faß.test",
            "user@😀.test", "user@a\u200bb.test",
            "user@example\u3002test", "user@example\uff0etest"
    })
    void rejectsMalformedOrUnsupportedSyntax(String email) {
        EmailFormatValidator.Result result = validator.validate(email);
        assertFalse(result.valid(), email);
        assertNull(result.domain(), email);
    }

    @Test
    void enforcesLocalPartByteBoundary() {
        assertValid("a".repeat(64) + "@example.test", "example.test");
        assertInvalid("a".repeat(65) + "@example.test", LOCAL_TOO_LONG);
        assertValid("\"" + "a".repeat(62) + "\"@example.test", "example.test");
        assertInvalid("\"" + "a".repeat(63) + "\"@example.test", LOCAL_TOO_LONG);
    }

    @Test
    void enforcesAsciiLabelByteBoundary() {
        assertValid("user@" + "a".repeat(63) + ".test", "a".repeat(63) + ".test");
        assertInvalid("user@" + "a".repeat(64) + ".test", INVALID_DOMAIN_LABEL);
    }

    @Test
    void enforcesAsciiAddressByteBoundary() {
        String local = "a".repeat(64);
        String domain254 = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertEquals(254, (local + "@" + domain254).length());
        assertEquals(255, (local + "@" + domain254 + "d").length());
        assertValid(local + "@" + domain254, domain254);
        assertInvalid(local + "@" + domain254 + "d", ADDRESS_TOO_LONG);
    }

    @Test
    void enforcesNormalizedIdnAddressAndLabelByteBoundaries() {
        String local = "a".repeat(64);
        String prefix = "b".repeat(63) + "." + "c".repeat(63) + ".";
        String unicodeDomain = prefix + "d".repeat(47) + ".bücher";
        String asciiDomain = prefix + "d".repeat(47) + ".xn--bcher-kva";
        assertEquals(254, (local + "@" + asciiDomain).length());
        assertTrue((local + "@" + unicodeDomain).length() < 254);
        assertValid(local + "@" + unicodeDomain, asciiDomain);
        assertEquals(255, (local + "@" + prefix + "d".repeat(48) + ".xn--bcher-kva").length());
        assertInvalid(local + "@" + prefix + "d".repeat(48) + ".bücher", ADDRESS_TOO_LONG);
        EmailFormatValidator.Result expandedLabel = validator.validate("user@" + "ü".repeat(63) + ".test");
        assertFalse(expandedLabel.valid());
        assertNull(expandedLabel.domain());
    }

    @Test
    void idnaExpansionCannotExceedDnsDomainWireLength() {
        String domain = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63)
                + "." + "ü".repeat(56);
        assertTrue(("a@" + domain).length() < 254);
        assertInvalid("a@" + domain, DOMAIN_TOO_LONG);
    }

    private void assertValid(String email, String domain) {
        EmailFormatValidator.Result result = validator.validate(email);
        assertTrue(result.valid(), email);
        assertEquals(VALID, result.reason(), email);
        assertEquals(domain, result.domain(), email);
    }

    private void assertInvalid(String email, EmailFormatValidator.Reason reason) {
        EmailFormatValidator.Result result = validator.validate(email);
        assertFalse(result.valid(), String.valueOf(email));
        assertEquals(reason, result.reason(), String.valueOf(email));
        assertNull(result.domain(), String.valueOf(email));
    }
}
