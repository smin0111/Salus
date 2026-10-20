package com.salus.healthytable.eval;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 하네스 실행 설정. 전부 {@code -Dsalus.eval.*} 시스템 프로퍼티로 주입되고,
 * 실행 진입점인 {@code eval.sh}가 CLI 플래그를 이 프로퍼티로 옮겨준다.
 */
public record EvalConfig(
        Mode mode,
        List<String> models,
        String judgeModel,
        boolean warmup,
        Set<String> suites,
        Set<String> onlyCaseIds,
        String casesResource,
        int repeat,
        double minPassRate,
        boolean gate,
        Boolean repair,
        Path outputDirectory,
        String probeUrl) {

    public enum Mode {
        /** Ollama가 살아 있으면 live, 아니면 replay. */
        AUTO,
        /** 실제 Ollama를 호출한다. */
        LIVE,
        /** 녹화된 모델 출력을 프로덕션 파싱·검증 경로에 흘린다. */
        REPLAY
    }

    private static final String DEFAULT_CASES = "/eval/cases/salus-core.jsonl";

    /** replay 모드에는 모델 변수가 없다. 리포트에 쓰는 고정 이름. */
    public static final String REPLAY_MODEL = "replay-fixture";

    // -Dsalus.eval.* 시스템 프로퍼티를 읽어 설정 객체를 만듭니다. 값이 없으면 기본값을 씁니다.
    public static EvalConfig fromSystemProperties() {
        Mode mode = Mode.valueOf(property("salus.eval.mode", "auto").trim().toUpperCase());
        return new EvalConfig(
                mode,
                List.copyOf(csv(property("salus.eval.models", ""))),
                property("salus.eval.judge-model", ""),
                Boolean.parseBoolean(property("salus.eval.warmup", "true")),
                csv(property("salus.eval.suites", "")),
                csv(property("salus.eval.only", "")),
                property("salus.eval.cases", DEFAULT_CASES),
                Integer.parseInt(property("salus.eval.repeat", "1")),
                Double.parseDouble(property("salus.eval.min-pass-rate", "-1")),
                Boolean.parseBoolean(property("salus.eval.gate", "true")),
                booleanOrNull(System.getProperty("salus.eval.repair")),
                Paths.get(property("salus.eval.out", "target/eval")),
                property("salus.eval.probe-url", "http://localhost:11434/api/tags"));
    }

    /** AUTO일 때 Ollama 생존 여부로 실제 실행 모드를 확정한다. */
    public Mode resolveEffectiveMode() {
        if (mode != Mode.AUTO) {
            return mode;
        }
        return probeOllama() ? Mode.LIVE : Mode.REPLAY;
    }

    /**
     * 이번 실행에서 비교할 모델 목록.
     *
     * <p>replay는 녹화 응답을 쓰므로 모델 축이 없다. live에서 {@code --models}를 주지 않으면
     * 프로퍼티에 설정된 프로덕션 기본 모델 하나만 돈다.
     */
    public List<String> resolveModels(Mode effectiveMode, String configuredModel) {
        if (effectiveMode == Mode.REPLAY) {
            return List.of(REPLAY_MODEL);
        }
        return models.isEmpty() ? List.of(configuredModel) : models;
    }

    // Judge 모델 교차평가 사용 여부
    public boolean judgeEnabled() {
        return judgeModel != null && !judgeModel.isBlank();
    }

    // Ollama 서버가 응답하는지 확인합니다.
    public boolean probeOllama() {
        return fetchOllamaTags() != null;
    }

    /** {@code /api/tags} 원문. 실패하면 null. 모델 설치 여부 확인에도 쓴다. */
    public String fetchOllamaTags() {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(probeUrl).toURL().openConnection();
            connection.setConnectTimeout(1_500);
            connection.setReadTimeout(3_000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 400) {
                connection.disconnect();
                return null;
            }
            try (var stream = connection.getInputStream()) {
                return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // 케이스 ID/태그 필터 설정에 따라 이번 실행에 포함할 케이스인지 판단합니다.
    public boolean includes(EvalCase evalCase) {
        if (!suites.isEmpty() && !suites.contains(evalCase.suiteOrDefault())) {
            return false;
        }
        return onlyCaseIds.isEmpty() || onlyCaseIds.contains(evalCase.id());
    }

    /**
     * 통과율 게이트 기본값.
     *
     * <p>replay는 결정적이라 100%를 요구한다. live는 모델 표본 편차가 있어 기본 게이트를 걸지 않고
     * 지표만 보고한다. 안전 차원(알레르겐) 게이트는 모드와 무관하게 항상 적용된다.
     */
    public double effectiveMinPassRate(Mode effectiveMode) {
        if (minPassRate >= 0) {
            return minPassRate;
        }
        return effectiveMode == Mode.REPLAY ? 1.0 : 0.0;
    }

    /**
     * 프로덕션과 동일하게 1회 repair 재호출을 포함할지 여부.
     *
     * <p>replay는 같은 고정 응답이 다시 나와 의미가 없으므로 기본은 live에서만 켠다.
     */
    public boolean repairEnabled(Mode effectiveMode) {
        return repair != null ? repair : effectiveMode == Mode.LIVE;
    }

    private static Boolean booleanOrNull(String value) {
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.trim());
    }

    // 시스템 프로퍼티를 읽고, 비어 있으면 기본값을 반환합니다.
    private static String property(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // 쉼표로 구분된 문자열을 공백 없는 집합으로 바꿉니다.
    private static Set<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        List<String> parts = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
        return new LinkedHashSet<>(parts);
    }
}
