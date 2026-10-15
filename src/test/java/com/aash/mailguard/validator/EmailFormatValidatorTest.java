package com.aash.mailguard.validator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EmailFormatValidatorTest {
    private final EmailFormatValidator validator = new EmailFormatValidator();

    @ParameterizedTest
    @ValueSource(strings = {"a\r@example.test", "a\n@example.test", "a\0@example.test", "a\177@example.test"})
    void rejectsControlCharacters(String email) {
        assertFalse(validator.isFormatValid(email));
    }
}
