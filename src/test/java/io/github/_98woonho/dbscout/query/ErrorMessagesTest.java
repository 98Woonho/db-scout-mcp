package io.github._98woonho.dbscout.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorMessagesTest {

    @Test
    void hidesValuesThatCameFromTheDatabase() {
        String msg = "ERROR: invalid input syntax for type integer: \"MARY.SMITH@sakilacustomer.org\"";

        String out = ErrorMessages.sanitize(msg, "SELECT CAST(email AS INTEGER) FROM customer");

        assertThat(out).doesNotContain("MARY").contains("\"(값 숨김)\"");
    }

    @Test
    void keepsNamesWrittenInTheSql() {
        String msg = "ERROR: column \"emial\" does not exist";

        assertThat(ErrorMessages.sanitize(msg, "SELECT emial FROM customer")).isEqualTo(msg);
    }

    @Test
    void hidesSingleQuotedValuesToo() {
        String msg = "Truncated incorrect DOUBLE value: 'PATRICIA.JOHNSON@sakilacustomer.org'";

        assertThat(ErrorMessages.sanitize(msg, "SELECT email + 0 FROM customer")).doesNotContain("PATRICIA");
    }

    @Test
    void limitsLength() {
        assertThat(ErrorMessages.sanitize("x".repeat(2000), "")).hasSize(ErrorMessages.MAX_LENGTH + 1);
    }
}
