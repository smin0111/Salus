package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFdaDrugLabelAdapterTest {

    private TestOpenFdaServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void koreanProductNameIsNotSearchedDirectlyInOpenFda() throws Exception {
        server = TestOpenFdaServer.start();
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(new NormalizedMedication(
                "국내정",
                "국내정",
                "",
                "제약사",
                "ITEM1",
                "",
                MedicationNormalizationStatus.EXACT_PRODUCT_MATCH,
                0.98,
                List.of("국내정")));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.NOT_FOUND);
        assertThat(server.searches()).isEmpty();
    }

    @Test
    void englishBrandWithoutVerifiedIngredientOrRxcuiIsNotSearched() throws Exception {
        server = TestOpenFdaServer.start();
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(new NormalizedMedication(
                "ExampleBrand",
                "ExampleBrand",
                "",
                "",
                "",
                "",
                MedicationNormalizationStatus.NORMALIZED_MATCH,
                0.65,
                List.of("ExampleBrand")));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.NOT_FOUND);
        assertThat(server.searches()).isEmpty();
    }

    @Test
    void rxcuiExactSearchRunsFirst() throws Exception {
        server = TestOpenFdaServer.start()
                .respond("openfda.rxcui.exact:\"11289\"", 200, labelJson("L1", "11289", "Warfarin", "warfarin", List.of("warfarin")));
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(warfarinWithRxcui());

        assertThat(result.status()).isEqualTo(MedicationDataStatus.FOUND);
        assertThat(result.matchStatus()).isEqualTo(OpenFdaLabelMatchStatus.EXACT_RXCUI_MATCH);
        assertThat(server.searches()).first().isEqualTo("openfda.rxcui.exact:\"11289\"");
    }

    @Test
    void genericAndSubstanceFallbackRunAfterRxcuiNotFound() throws Exception {
        server = TestOpenFdaServer.start()
                .respond("openfda.rxcui.exact:\"11289\"", 404, "{}")
                .respond("openfda.generic_name.exact:\"warfarin\"", 404, "{}")
                .respond("openfda.substance_name.exact:\"warfarin\"", 200, labelJson("L2", "", "Warfarin", "warfarin sodium", List.of("warfarin")));
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(warfarinWithRxcui());

        assertThat(result.status()).isEqualTo(MedicationDataStatus.FOUND);
        assertThat(result.matchStatus()).isEqualTo(OpenFdaLabelMatchStatus.EXACT_SUBSTANCE_MATCH);
        assertThat(server.searches()).containsExactly(
                "openfda.rxcui.exact:\"11289\"",
                "openfda.generic_name.exact:\"warfarin\"",
                "openfda.substance_name.exact:\"warfarin\"");
    }

    @Test
    void exactSearchSuccessDoesNotRunTokenFallback() throws Exception {
        server = TestOpenFdaServer.start()
                .respond("openfda.generic_name.exact:\"warfarin\"", 200, labelJson("L3", "", "Warfarin", "warfarin", List.of("warfarin")));
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(warfarinWithoutRxcui());

        assertThat(result.status()).isEqualTo(MedicationDataStatus.FOUND);
        assertThat(server.searches()).containsExactly("openfda.generic_name.exact:\"warfarin\"");
        assertThat(server.searches()).noneMatch(search -> search.contains("openfda.generic_name:warfarin"));
    }

    @Test
    void tokenResultIsNotAcceptedWithoutIngredientVerification() throws Exception {
        server = TestOpenFdaServer.start()
                .defaultStatus(404)
                .respond("openfda.generic_name:warfarin", 200, labelJson("L4", "", "Other Drug", "other drug", List.of("other")));
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(warfarinWithoutRxcui());

        assertThat(result.status()).isEqualTo(MedicationDataStatus.NOT_FOUND);
        assertThat(result.searchAttempts()).anyMatch(attempt ->
                attempt.stage() == OpenFdaSearchStage.GENERIC_TOKEN
                        && !attempt.verified()
                        && attempt.failureCategory().equals("NO_VERIFIED_LABEL"));
    }

    @Test
    void differentIngredientLabelIsNotAutoSelected() throws Exception {
        server = TestOpenFdaServer.start()
                .respond("openfda.generic_name.exact:\"warfarin\"", 200, labelJson("L5", "", "Atorvastatin", "atorvastatin", List.of("atorvastatin")));
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(warfarinWithoutRxcui());

        assertThat(result.status()).isEqualTo(MedicationDataStatus.NOT_FOUND);
        assertThat(result.labels()).isEmpty();
    }

    @Test
    void compoundPartialIngredientMatchIsNotConfirmed() throws Exception {
        server = TestOpenFdaServer.start()
                .respond("openfda.generic_name.exact:\"drug a\"", 200, labelJson("L6", "", "Drug A", "drug a", List.of("drug a")));
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(new NormalizedMedication(
                "복합정",
                "복합정",
                "drug a, drug b",
                "제약사",
                "ITEM1",
                "",
                MedicationNormalizationStatus.EXACT_PRODUCT_MATCH,
                0.98,
                List.of("drug a", "drug b")));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.NOT_FOUND);
    }

    @Test
    void multipleVerifiedLabelsRequireReview() throws Exception {
        server = TestOpenFdaServer.start()
                .respond("openfda.generic_name.exact:\"warfarin\"", 200, labelsJson(
                        labelNode("L7", "", "Warfarin", "warfarin", List.of("warfarin")),
                        labelNode("L8", "", "Warfarin", "warfarin", List.of("warfarin"))));
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(warfarinWithoutRxcui());

        assertThat(result.status()).isEqualTo(MedicationDataStatus.INCOMPLETE);
        assertThat(result.matchStatus()).isEqualTo(OpenFdaLabelMatchStatus.MULTIPLE_LABEL_MATCHES);
    }

    @Test
    void openFdaHttpStatusIsClassified() throws Exception {
        server = TestOpenFdaServer.start()
                .respond("openfda.generic_name.exact:\"warfarin\"", 400, "{\"error\":\"bad\"}");
        OpenFdaDrugLabelAdapter adapter = adapter();

        DrugLabelEvidenceResult result = adapter.findLabelEvidence(warfarinWithoutRxcui());

        assertThat(result.status()).isEqualTo(MedicationDataStatus.API_FAILED);
        assertThat(result.matchStatus()).isEqualTo(OpenFdaLabelMatchStatus.QUERY_REJECTED);
    }

    private OpenFdaDrugLabelAdapter adapter() {
        return new OpenFdaDrugLabelAdapter(
                WebClient.builder(),
                new ObjectMapper().findAndRegisterModules(),
                true,
                "",
                1000,
                server.baseUrl());
    }

    private NormalizedMedication warfarinWithRxcui() {
        return new NormalizedMedication("warfarin", "warfarin", "warfarin", "", "", "11289",
                MedicationNormalizationStatus.NORMALIZED_MATCH, 0.9, List.of("warfarin"));
    }

    private NormalizedMedication warfarinWithoutRxcui() {
        return new NormalizedMedication("warfarin", "warfarin", "warfarin", "", "", "",
                MedicationNormalizationStatus.NORMALIZED_MATCH, 0.9, List.of("warfarin"));
    }

    private String labelJson(String id, String rxcui, String brand, String generic, List<String> substances) {
        return labelsJson(labelNode(id, rxcui, brand, generic, substances));
    }

    private String labelsJson(String... labels) {
        return "{\"results\":[" + String.join(",", labels) + "]}";
    }

    private String labelNode(String id, String rxcui, String brand, String generic, List<String> substances) {
        return """
                {
                  "id":"%s",
                  "set_id":"set-%s",
                  "effective_time":"20260720",
                  "drug_interactions":["Avoid grapefruit juice."],
                  "openfda":{
                    "brand_name":["%s"],
                    "generic_name":["%s"],
                    "substance_name":%s,
                    "rxcui":%s
                  }
                }
                """.formatted(id, id, brand, generic, jsonArray(substances), rxcui == null || rxcui.isBlank() ? "[]" : jsonArray(List.of(rxcui)));
    }

    private String jsonArray(List<String> values) {
        return "[" + values.stream()
                .map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("") + "]";
    }

    private static class TestOpenFdaServer {
        private final HttpServer server;
        private final List<String> searches = new ArrayList<>();
        private final List<ResponseRule> rules = new ArrayList<>();
        private int defaultStatus = 404;

        static TestOpenFdaServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestOpenFdaServer wrapper = new TestOpenFdaServer(server);
            server.createContext("/", exchange -> {
                String search = wrapper.searchParam(exchange.getRequestURI());
                wrapper.searches.add(search);
                ResponseRule rule = wrapper.rules.stream()
                        .filter(candidate -> candidate.search().equals(search))
                        .findFirst()
                        .orElse(new ResponseRule(search, wrapper.defaultStatus, "{}"));
                byte[] bytes = rule.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(rule.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return wrapper;
        }

        private TestOpenFdaServer(HttpServer server) {
            this.server = server;
        }

        TestOpenFdaServer respond(String search, int status, String body) {
            rules.add(new ResponseRule(search, status, body));
            return this;
        }

        TestOpenFdaServer defaultStatus(int status) {
            this.defaultStatus = status;
            return this;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> searches() {
            return searches;
        }

        void stop() {
            server.stop(0);
        }

        private String searchParam(URI uri) {
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return "";
            }
            for (String part : query.split("&")) {
                int index = part.indexOf('=');
                String name = index >= 0 ? part.substring(0, index) : part;
                if ("search".equals(URLDecoder.decode(name, StandardCharsets.UTF_8))) {
                    String value = index >= 0 ? part.substring(index + 1) : "";
                    return URLDecoder.decode(value, StandardCharsets.UTF_8);
                }
            }
            return "";
        }

        private record ResponseRule(String search, int status, String body) {
        }
    }
}
