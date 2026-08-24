package com.simon.ledger.concurrency;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionOperationUniquenessContractTests {

    private static final String GENERATED_SLOT =
            "active_operation_slot TINYINT GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END) STORED";
    private static final String UNIQUE_KEY =
            "UNIQUE KEY uk_ledger_transaction_active_operation "
                    + "(ledger_id, created_by_user_id, client_operation_id, active_operation_slot)";

    @Test
    void freshSchemaUsesNullableGeneratedSlotForActiveOnlyOperationUniqueness() throws Exception {
        String transactionTable = transactionTable(Files.readString(Path.of("sql", "001_init_schema.sql")));

        assertContainsNormalized(transactionTable, "client_operation_id VARCHAR(128) NULL");
        assertContainsNormalized(transactionTable, GENERATED_SLOT);
        assertContainsNormalized(transactionTable, UNIQUE_KEY);
        assertEquals(1, occurrences(normalize(transactionTable), normalize(GENERATED_SLOT)));
        assertEquals(1, occurrences(normalize(transactionTable), normalize(UNIQUE_KEY)));
    }

    @Test
    void incrementalMigrationOnlyAddsGeneratedSlotAndOrderedUniqueKey() throws Exception {
        String migration = Files.readString(Path.of("sql", "005_add_transaction_operation_uniqueness.sql"));
        String normalized = normalize(stripComments(migration));

        assertTrue(normalized.startsWith("use simon_ledger; alter table ledger_transaction"));
        assertContainsNormalized(normalized, "add column " + GENERATED_SLOT);
        assertContainsNormalized(normalized, "add " + UNIQUE_KEY);
        assertEquals(1, occurrences(normalized, "alter table ledger_transaction"));
        assertFalse(normalized.contains("delete from"));
        assertFalse(normalized.contains("update ledger_transaction"));
        assertFalse(normalized.contains("select "));
    }

    @Test
    void versionMigrationDoesNotMixInOperationUniqueness() throws Exception {
        String migration = normalize(Files.readString(Path.of("sql", "004_add_optimistic_versions.sql")));

        assertFalse(migration.contains("active_operation_slot"));
        assertFalse(migration.contains("client_operation_id"));
        assertFalse(migration.contains("uk_ledger_transaction_active_operation"));
    }

    @Test
    void readmeDocumentsPreflightManualCleanupOrderAndUnverifiedMysqlExecution() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        String normalized = normalize(readme);

        assertContainsNormalized(normalized,
                "where deleted_at is null and client_operation_id is not null");
        assertContainsNormalized(normalized,
                "group by ledger_id, created_by_user_id, client_operation_id having count(*) > 1");
        assertTrue(normalized.contains("人工"));
        assertTrue(normalized.contains("不自动删除") || normalized.contains("不会自动删除"));
        assertTrue(normalized.indexOf("sql/004_add_optimistic_versions.sql")
                < normalized.indexOf("sql/005_add_transaction_operation_uniqueness.sql"));
        assertTrue(normalized.contains("一次性执行"));
        assertTrue(normalized.contains("mysql 8"));
        assertTrue(normalized.contains("尚未完成") || normalized.contains("未完成"));
        assertContainsNormalized(normalized, "fresh 001");
        assertTrue(normalized.contains("不要再") || normalized.contains("不得再"));
    }

    private String transactionTable(String schema) {
        Matcher matcher = Pattern.compile(
                "(?s)CREATE TABLE IF NOT EXISTS ledger_transaction\\s*\\(.*?\\) ENGINE\\s*=\\s*InnoDB")
                .matcher(schema);
        assertTrue(matcher.find(), "ledger_transaction table missing");
        return matcher.group();
    }

    private void assertContainsNormalized(String actual, String expected) {
        assertTrue(normalize(actual).contains(normalize(expected)),
                () -> "missing SQL/doc contract: " + expected);
    }

    private String stripComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }
}
