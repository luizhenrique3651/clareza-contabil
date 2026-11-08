package br.com.mouraoclareza.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste de arquitetura e alinhamento de schema (FIX-07):
 * 1. Garante que nenhuma classe em pacotes "..domain.." dependa de Spring Framework ou Jakarta Servlet (ADR-001).
 * 2. Garante que nenhuma classe em src/main/java referencie tabelas SQL paralelas ou inexistentes nas migrations V1..V8.
 */
class HexagonalArchitectureTest {

    private static final Set<String> FORBIDDEN_SQL_TABLES = Set.of(
            "parties",
            "financial_records",
            "financial_settlements",
            "financial_events",
            "bank_accounts",
            "bank_statement_imports",
            "bank_reconciliations"
    );

    @Test
    @DisplayName("Pacotes ..domain.. devem ser puros e não importar org.springframework.. ou jakarta.servlet..")
    void domainPackagesMustNotDependOnSpringOrServlet() throws IOException {
        Path mainJava = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(mainJava)) {
            List<Path> domainFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/domain/"))
                    .toList();

            assertFalse(domainFiles.isEmpty(), "Deve existir ao menos uma classe de domínio para validação.");

            for (Path file : domainFiles) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("import org.springframework.")
                            || line.startsWith("import jakarta.servlet.")
                            || line.startsWith("import javax.servlet.")) {
                        violations.add(file + ":" + (i + 1) + " -> " + line);
                    }
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                "Violações de isolamento de domínio (ADR-001) encontradas:\n" + String.join("\n", violations)
        );
    }

    @Test
    @DisplayName("Nenhuma query SQL em src/main/java pode referenciar tabelas paralelas fora de V1..V8")
    void sqlQueriesMustMatchFlywayMigrationsSchema() throws IOException {
        Path mainJava = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        Pattern sqlTableRefPattern = Pattern.compile(
                "\\b(?:FROM|INTO|UPDATE|JOIN)\\s+([a-z_][a-z0-9_]*)\\b",
                Pattern.CASE_INSENSITIVE
        );

        try (Stream<Path> paths = Files.walk(mainJava)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();

            for (Path file : javaFiles) {
                String content = Files.readString(file);
                Matcher matcher = sqlTableRefPattern.matcher(content);
                while (matcher.find()) {
                    String tableName = matcher.group(1).toLowerCase();
                    if (FORBIDDEN_SQL_TABLES.contains(tableName)) {
                        violations.add(file + " referencia tabela SQL inexistente: " + tableName);
                    }
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                "Queries SQL referenciando tabelas inexistentes nas migrations V1..V8:\n" + String.join("\n", violations)
        );
    }
}
