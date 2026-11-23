package io.github._98woonho.dbscout.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/** 고정 검증 세트의 정답을 실제 서버 조회 경로와 내장 Sakila로 확인한다. LLM은 호출하지 않는다. */
class EvaluationReferenceTest {
    static final Path REFERENCE = Path.of("docs/evaluation/holdout-v1.json");
    @TempDir
    static Path dir;
    static QueryExecutor executor;

    @BeforeAll
    static void setUp() {
        executor = SakilaFixture.executor(dir);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> references() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(REFERENCE));
        return (List<Map<String, Object>>) document.get("queries");
    }

    static Stream<Arguments> queries() throws IOException {
        return references().stream().map(q -> Arguments.argumentSet(q.get("id").toString(), q));
    }

    @ParameterizedTest
    @MethodSource("queries")
    @SuppressWarnings("unchecked")
    void answersMatchActualReadOnlyMaskedResults(Map<String, Object> reference) {
        QueryResult actual = executor.run(null, reference.get("sql").toString(), 100, null);
        assertThat(actual.columns()).containsExactlyElementsOf((List<String>) reference.get("columns"));
        assertThat(actual.truncated()).isFalse();
        // 개수까지 개인정보로 가리는 회귀도 이 경로에서 검출한다.
        assertThat(actual.maskedColumns()).isEmpty();
        List<List<Object>> expected = (List<List<Object>>) reference.get("rows");
        assertThat(actual.rows()).hasSize(expected.size());
        for (int row = 0; row < expected.size(); row++) {
            assertThat(actual.rows().get(row)).hasSize(expected.get(row).size());
            for (int col = 0; col < expected.get(row).size(); col++) {
                assertThat(new BigDecimal(actual.rows().get(row).get(col).toString()))
                        .isEqualByComparingTo(expected.get(row).get(col).toString());
            }
        }
    }

    @Test
    void eachFrozenQuestionHasExactlyOneReference() throws IOException {
        Set<String> cases;
        try (Stream<Path> paths = Files.walk(Path.of("plugin/evals-holdout"))) {
            cases = paths.filter(p -> p.getFileName().toString().equals("case.yaml"))
                    .map(p -> p.getParent().getFileName().toString()).collect(Collectors.toSet());
        }
        assertThat(references()).extracting(q -> q.get("id").toString())
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(cases);
    }
}
