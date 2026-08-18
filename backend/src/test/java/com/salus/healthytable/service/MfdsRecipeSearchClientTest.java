package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MfdsRecipeSearchClientTest {

    @Test
    void officialRecipeResponseBecomesStructuredSearchEvidence() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                        {
                          "COOKRCP01": {
                            "row": [{
                              "RCP_NM": "김치찌개",
                              "RCP_WAY2": "끓이기",
                              "RCP_PAT2": "국&찌개",
                              "INFO_WGT": "400g",
                              "INFO_ENG": "350",
                              "RCP_PARTS_DTLS": "김치 200g, 돼지고기 150g, 두부 100g, 물 500ml",
                              "MANUAL01": "김치와 돼지고기를 중불에서 5분 볶는다.",
                              "MANUAL02": "물을 붓고 15분 끓인 뒤 두부를 넣는다."
                            }]
                          }
                        }
                        """)
                .build());
        MfdsRecipeSearchClient client = new MfdsRecipeSearchClient(
                WebClient.builder().exchangeFunction(exchange),
                true,
                "test-key",
                "https://official.example",
                3);

        SearchEngine.SearchResponse response = client.search("김치찌개").block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(SearchEngine.SearchStatus.SUCCESS);
        assertThat(response.source()).isEqualTo("식품의약품안전처 레시피 DB");
        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.title()).contains("김치찌개", "공식 레시피");
            assertThat(result.snippet())
                    .contains("재료: 김치 200g")
                    .contains("조리 단계 1")
                    .contains("중불에서 5분");
        });
    }

    @Test
    void missingApiKeySkipsNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            return Mono.error(new IllegalStateException("호출되면 안 됩니다."));
        };
        MfdsRecipeSearchClient client = new MfdsRecipeSearchClient(
                WebClient.builder().exchangeFunction(exchange),
                true,
                "",
                "https://official.example",
                3);

        SearchEngine.SearchResponse response = client.search("김치찌개").block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(SearchEngine.SearchStatus.EMPTY);
        assertThat(calls).hasValue(0);
    }
}
