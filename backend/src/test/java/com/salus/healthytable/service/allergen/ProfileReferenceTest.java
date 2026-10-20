package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.salus.healthytable.service.allergen.ProfileReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileReferenceTest {
    static final Dataset DATASET = load();
    static final List<Evaluation> EVALUATIONS = DATASET.cases().stream()
            .map(c -> evaluate(c, ProfileAllergenResolverTest.RESOLVER)).toList();

    private static Dataset load() {
        try (var input = ProfileReferenceTest.class.getResourceAsStream("/allergens/profile-reference.json")) {
            if (input == null) throw new IllegalStateException("Missing profile reference");
            Dataset dataset = new ObjectMapper().readValue(input, Dataset.class);
            List<String> errors = validate(dataset, ProfileAllergenResolverTest.REGISTRY);
            if (!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
            return dataset;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    static Stream<Evaluation> cases() { return EVALUATIONS.stream(); }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void resolvesAgainstIndependentProfileContractExpectations(Evaluation evaluation) {
        assertThat(evaluation.errors()).as(evaluation.reference().toString()).isEmpty();
        assertThat(evaluation.result()).isNotNull();
    }

    @Test
    void reportsResolutionSeparatelyFromContractAccuracyAndProductCoverage() throws IOException {
        assertThat(DATASET.cases()).hasSize(20);
        String report = report(EVALUATIONS);
        assertThat(report).contains("Total terms: 20", "Resolved: 14", "Unresolved: 6", "Resolution rate: 70.0%",
                "Exact cases: 20 / 20", "REGISTRY_MISS=2", "AMBIGUOUS=0", "NO_PROFILE_ELIGIBLE_ALIAS=4",
                "CHAT_MESSAGE=1", "CHAT_REQUEST_PROFILE=1", "STORED_HEALTH_PROFILE=18");
        Files.writeString(Path.of("target/profile-reference-report.txt"), report);
        System.out.println(report);
    }
}
