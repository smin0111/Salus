package com.salus.healthytable.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 실행 결과 집계와 리포트 파일 출력. 관측값에서 결정적으로 계산되는 것만 담는다. */
public record EvalReport(
        String runId,
        String startedAt,
        String mode,
        long durationMs,
        Environment environment,
        Dataset dataset,
        List<String> requestedModels,
        List<String> evaluatedModels,
        List<String> unavailableModels,
        String judgeModel,
        int totalRuns,
        int passedRuns,
        int hardFailRuns,
        double passRate,
        Map<String, ModelSummary> modelSummaries,
        List<CaseComparison> caseComparisons,
        List<HardFail> hardFails,
        List<RepeatStat> repeatStats,
        List<String> skippedCases,
        List<String> unresolved,
        List<EvalResult> results) {

    /** 실행 환경. 전부 실제 설정값에서 읽는다. */
    public record Environment(
            String javaVersion,
            String os,
            String ollamaEndpoint,
            String promptSource,
            String validatorSource,
            String allergenSource,
            String repairPolicy,
            Map<String, String> samplingParameters) {
    }

    // 데이터셋 정보(리소스 경로, 전체/실행 가능 케이스 수, 반복 횟수)
    public record Dataset(String resource, int totalCases, int runnableCases, int repeat) {
    }

    // 지연 시간 통계(평균, 중앙값, p95, 최소, 최대)
    public record Latency(long avgMs, long medianMs, long p95Ms, long minMs, long maxMs) {
    }

    // 모델별 집계 결과
    public record ModelSummary(
            String model,
            boolean available,
            int runs,
            int passed,
            int hardFails,
            double passRate,
            int validatorApplicableRuns,
            int firstDraftPassed,
            double firstDraftPassRate,
            int repairAttempted,
            int repairPassed,
            double afterRepairPassRate,
            int validatorFailures,
            double validatorFailureRate,
            int allergenFailures,
            int schemaFailures,
            int timeouts,
            double averageScore,
            Latency latency,
            Map<String, Integer> failureTypes) {
    }

    // 케이스 비교표에서 한 모델의 결과
    public record ModelOutcome(
            String model,
            int iteration,
            String verdict,
            double score,
            long latencyMs,
            boolean timeout,
            List<String> problems,
            String rawOutput,
            String rawOutputFile,
            String judgeVerdict,
            Boolean judgeAgreement) {
    }

    // 케이스 하나를 모델별로 비교한 결과
    public record CaseComparison(
            String caseId,
            String suite,
            String input,
            List<String> expectedConditions,
            String ragCondition,
            List<ModelOutcome> outcomes) {
    }

    // 알레르겐 안전 차원이 실패한 실행 기록
    public record HardFail(String model, String caseId, int iteration, List<String> allergenConflicts) {
    }

    // 같은 케이스를 반복 실행했을 때의 결과 분산 통계
    public record RepeatStat(
            String model,
            String caseId,
            int runs,
            int passed,
            double meanScore,
            double scoreStdDev,
            double meanLatencyMs,
            double latencyStdDev) {
    }

    /** 외부(Notion 등)에서 바로 읽을 수 있는 평면 레코드. */
    public record NotionRecord(
            String runId,
            String runDate,
            String caseId,
            String suite,
            String model,
            int iteration,
            String input,
            String rawOutput,
            double score,
            String verdict,
            List<String> problemTypes,
            long latencyMs,
            String ragCondition,
            String rawOutputFile) {
    }

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MARKDOWN_RAW_LIMIT = 20000;

    // 전체 실행 결과로 모델 요약, 케이스 비교, 안전 실패, 반복 통계를 계산해 리포트를 만듭니다.
    public static EvalReport build(
            String runId,
            EvalConfig.Mode mode,
            Environment environment,
            Dataset dataset,
            List<String> requestedModels,
            List<String> evaluatedModels,
            List<String> unavailableModels,
            String judgeModel,
            Instant startedAt,
            List<String> skippedCases,
            List<EvalResult> results) {

        int passed = (int) results.stream().filter(EvalResult::isPass).count();
        int hardFail = (int) results.stream().filter(EvalResult::isHardFail).count();

        Map<String, ModelSummary> summaries = new LinkedHashMap<>();
        for (String model : evaluatedModels) {
            summaries.put(model, summarize(model, !unavailableModels.contains(model),
                    results.stream().filter(result -> result.model().equals(model)).toList()));
        }

        return new EvalReport(
                runId,
                LocalDateTime.ofInstant(startedAt, ZoneId.systemDefault()).toString(),
                mode.name().toLowerCase(),
                Duration.between(startedAt, Instant.now()).toMillis(),
                environment,
                dataset,
                requestedModels,
                evaluatedModels,
                unavailableModels,
                judgeModel,
                results.size(),
                passed,
                hardFail,
                rate(passed, results.size()),
                summaries,
                buildComparisons(results),
                buildHardFails(results),
                buildRepeatStats(results, dataset.repeat()),
                List.copyOf(skippedCases),
                buildUnresolved(dataset, unavailableModels, skippedCases, judgeModel, results),
                results);
    }

    // 한 모델의 통과율, 차원별 실패 수, 지연 시간 등을 집계합니다.
    private static ModelSummary summarize(String model, boolean available, List<EvalResult> runs) {
        int passed = (int) runs.stream().filter(EvalResult::isPass).count();
        int hardFails = (int) runs.stream().filter(EvalResult::isHardFail).count();
        List<EvalResult> validatorRuns = runs.stream()
                .filter(result -> result.dimensionApplicable(EvalResult.VALIDATOR))
                .toList();
        int firstDraftPassed = (int) validatorRuns.stream().filter(EvalResult::firstDraftValid).count();
        int repairAttempted = (int) runs.stream().filter(EvalResult::repairAttempted).count();
        int repairPassed = (int) runs.stream().filter(EvalResult::repairPassed).count();
        int validatorFailures = (int) validatorRuns.stream()
                .filter(result -> result.dimensionFailed(EvalResult.VALIDATOR)).count();
        int allergenFailures = (int) runs.stream()
                .filter(result -> result.dimensionFailed(EvalResult.ALLERGEN_SAFETY)).count();
        int schemaFailures = (int) runs.stream()
                .filter(result -> result.dimensionFailed(EvalResult.SCHEMA)).count();
        int timeouts = (int) runs.stream().filter(EvalResult::timeout).count();

        Map<String, Integer> failureTypes = new TreeMap<>();
        runs.stream()
                .filter(result -> !result.isPass())
                .flatMap(result -> result.problemTypes().stream())
                .forEach(type -> failureTypes.merge(type, 1, Integer::sum));

        List<Long> latencies = runs.stream().map(EvalResult::latencyMs).sorted().toList();
        double averageScore = runs.isEmpty() ? 0.0
                : Math.round(runs.stream().mapToDouble(EvalResult::score).average().orElse(0) * 10) / 10.0;

        return new ModelSummary(
                model, available, runs.size(), passed, hardFails, rate(passed, runs.size()),
                validatorRuns.size(), firstDraftPassed, rate(firstDraftPassed, validatorRuns.size()),
                repairAttempted, repairPassed, rate(repairPassed, repairAttempted),
                validatorFailures, rate(validatorFailures, validatorRuns.size()),
                allergenFailures, schemaFailures, timeouts,
                averageScore, latency(latencies), failureTypes);
    }

    private static List<CaseComparison> buildComparisons(List<EvalResult> results) {
        Map<String, List<EvalResult>> byCase = new LinkedHashMap<>();
        results.forEach(result -> byCase.computeIfAbsent(result.caseId(), key -> new ArrayList<>()).add(result));

        List<CaseComparison> comparisons = new ArrayList<>();
        byCase.forEach((caseId, runs) -> {
            EvalResult sample = runs.get(0);
            List<ModelOutcome> outcomes = runs.stream()
                    .sorted(Comparator.comparing(EvalResult::model).thenComparingInt(EvalResult::iteration))
                    .map(result -> new ModelOutcome(
                            result.model(),
                            result.iteration(),
                            result.verdict(),
                            result.score(),
                            result.latencyMs(),
                            result.timeout(),
                            result.problemTypes(),
                            result.rawOutput(),
                            rawOutputFile(result),
                            result.judgeVerdict(),
                            result.judgeAgreement()))
                    .toList();
            comparisons.add(new CaseComparison(
                    caseId, sample.suite(), sample.input(),
                    sample.expectedConditions(), sample.ragCondition(), outcomes));
        });
        return comparisons;
    }

    private static List<HardFail> buildHardFails(List<EvalResult> results) {
        return results.stream()
                .filter(EvalResult::isHardFail)
                .map(result -> new HardFail(
                        result.model(), result.caseId(), result.iteration(), result.allergenConflicts()))
                .toList();
    }

    private static List<RepeatStat> buildRepeatStats(List<EvalResult> results, int repeat) {
        if (repeat < 2) {
            return List.of();
        }
        Map<String, List<EvalResult>> grouped = new LinkedHashMap<>();
        results.forEach(result -> grouped
                .computeIfAbsent(result.model() + " " + result.caseId(), key -> new ArrayList<>())
                .add(result));

        List<RepeatStat> stats = new ArrayList<>();
        grouped.forEach((key, runs) -> {
            String[] parts = key.split(" ", 2);
            List<Double> scores = runs.stream().map(EvalResult::score).toList();
            List<Double> latencies = runs.stream().map(result -> (double) result.latencyMs()).toList();
            stats.add(new RepeatStat(
                    parts[0], parts[1], runs.size(),
                    (int) runs.stream().filter(EvalResult::isPass).count(),
                    round(mean(scores)), round(stdDev(scores)),
                    round(mean(latencies)), round(stdDev(latencies))));
        });
        return stats;
    }

    /**
     * 이번 실행 데이터로는 판단할 수 없는 항목. 추측을 적지 않기 위해 명시적으로 남긴다.
     * 항목은 전부 실행 조건에서 기계적으로 유도한다.
     */
    private static List<String> buildUnresolved(
            Dataset dataset,
            List<String> unavailableModels,
            List<String> skippedCases,
            String judgeModel,
            List<EvalResult> results) {

        List<String> unresolved = new ArrayList<>();
        if (!unavailableModels.isEmpty()) {
            unresolved.add("설치되지 않아 실제 호출 없이 실패로만 기록한 모델: " + String.join(", ", unavailableModels));
        }
        if (!skippedCases.isEmpty()) {
            unresolved.add("이번 실행에서 제외된 케이스 " + skippedCases.size() + "건 - 해당 케이스의 모델 차이는 알 수 없음");
        }
        if (dataset.repeat() < 2) {
            unresolved.add("--repeat 1 실행이라 같은 케이스의 표본 편차(재현성)를 판단할 수 없음");
        }
        if (judgeModel == null || judgeModel.isBlank()) {
            unresolved.add("Judge 교차평가 미실행 - 검증기가 보지 않는 품질 축은 측정되지 않음");
        }
        if (results.stream().anyMatch(result -> EvalCase.SUITE_CHAT.equals(result.suite()))) {
            unresolved.add("chat_reply 스위트는 구조화 검증기 대상이 아니므로 레시피 정확도는 채점하지 않음");
        }
        unresolved.add("맛, 영양 정확도, 사용자 선호는 이 하네스가 측정하지 않음");
        return unresolved;
    }

    // 결과 하나의 원문을 저장할 파일 이름(모델/케이스/회차 기반)
    public static String rawOutputFile(EvalResult result) {
        return "raw/" + EvalModelBinder.slug(result.model())
                + "/" + result.caseId() + "__iter" + result.iteration() + ".txt";
    }

    // 전체 실행에서 특정 차원의 통과율(해당되는 실행만 분모에 포함)
    public double dimensionPassRate(String dimension) {
        List<EvalResult.Dimension> applicable = results.stream()
                .flatMap(result -> result.dimensions().stream())
                .filter(candidate -> candidate.name().equals(dimension))
                .filter(EvalResult.Dimension::applicable)
                .toList();
        long passed = applicable.stream().filter(EvalResult.Dimension::passed).count();
        return rate((int) passed, applicable.size());
    }

    public int dimensionFailures(String dimension) {
        return (int) results.stream().filter(result -> result.dimensionFailed(dimension)).count();
    }

    /** JSON 전문, Markdown 리포트, 평면 레코드, raw output 파일을 함께 남긴다. 반환값은 Markdown 경로. */
    public Path write(Path outputDirectory, ObjectMapper objectMapper) {
        try {
            Files.createDirectories(outputDirectory);
            String stamp = LocalDateTime.now().format(FILE_STAMP);
            ObjectMapper writer = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);

            byte[] json = writer.writeValueAsBytes(this);
            Files.write(outputDirectory.resolve("report-" + stamp + ".json"), json);
            Files.write(outputDirectory.resolve("latest.json"), json);

            byte[] records = writer.writeValueAsBytes(notionRecords());
            Files.write(outputDirectory.resolve("records-" + stamp + ".json"), records);
            Files.write(outputDirectory.resolve("latest-records.json"), records);

            writeRawOutputs(outputDirectory);

            String markdown = toMarkdown();
            Files.writeString(outputDirectory.resolve("report-" + stamp + ".md"), markdown, StandardCharsets.UTF_8);
            Path latestMarkdown = outputDirectory.resolve("latest.md");
            Files.writeString(latestMarkdown, markdown, StandardCharsets.UTF_8);
            Files.writeString(outputDirectory.resolve("latest.txt"),
                    toConsoleSummary() + System.lineSeparator(), StandardCharsets.UTF_8);
            return latestMarkdown;
        } catch (IOException e) {
            throw new UncheckedIOException("평가 리포트 기록 실패", e);
        }
    }

    /** 모델 응답 원문을 파일로 그대로 남긴다. 가공하거나 요약하지 않는다. */
    private void writeRawOutputs(Path outputDirectory) throws IOException {
        for (EvalResult result : results) {
            Path file = outputDirectory.resolve(rawOutputFile(result));
            Files.createDirectories(file.getParent());
            Files.writeString(file, result.rawOutput() == null ? "" : result.rawOutput(),
                    StandardCharsets.UTF_8);
            String base = file.getFileName().toString();
            if (result.rawEnvelope() != null && !result.rawEnvelope().isBlank()) {
                Files.writeString(file.resolveSibling(base.replace(".txt", ".envelope.json")),
                        result.rawEnvelope(), StandardCharsets.UTF_8);
            }
            if (result.rawOutputFirstDraft() != null && !result.rawOutputFirstDraft().isBlank()) {
                Files.writeString(file.resolveSibling(base.replace(".txt", ".first-draft.txt")),
                        result.rawOutputFirstDraft(), StandardCharsets.UTF_8);
            }
        }
    }

    // 결과를 외부 도구에서 읽기 쉬운 평면 레코드 목록으로 변환합니다.
    public List<NotionRecord> notionRecords() {
        return results.stream()
                .map(result -> new NotionRecord(
                        result.runId(), startedAt, result.caseId(), result.suite(), result.model(),
                        result.iteration(), result.input(), result.rawOutput(), result.score(),
                        result.verdict(), result.problemTypes(), result.latencyMs(),
                        result.ragCondition(), rawOutputFile(result)))
                .toList();
    }

    // 사람이 읽는 Markdown 리포트를 만듭니다. 아래 append* 메서드가 섹션을 하나씩 추가합니다.
    public String toMarkdown() {
        StringBuilder out = new StringBuilder();
        out.append("# Salus LLM Evaluation Report\n\n");
        out.append("- run id: `").append(runId).append("`\n");
        out.append("- 실행 시각: ").append(startedAt).append('\n');
        out.append("- 모드: `").append(mode).append("`  소요: ").append(durationMs / 1000).append("초\n");
        out.append("- 전체: **").append(passedRuns).append(" / ").append(totalRuns)
                .append("** 통과 (").append(percent(passRate)).append("), Hard Fail ")
                .append(hardFailRuns).append("건\n\n");

        appendEnvironment(out);
        appendModels(out);
        appendDataset(out);
        appendModelResults(out);
        appendLatency(out);
        appendFailureTypes(out);
        appendCaseComparisons(out);
        appendHardFails(out);
        appendModelNotes(out);
        appendUnresolved(out);
        return out.toString();
    }

    private void appendEnvironment(StringBuilder out) {
        out.append("## 1. 실행 환경\n\n");
        out.append("| 항목 | 값 |\n|---|---|\n");
        out.append("| Java | ").append(environment.javaVersion()).append(" |\n");
        out.append("| OS | ").append(environment.os()).append(" |\n");
        out.append("| Ollama | ").append(environment.ollamaEndpoint()).append(" |\n");
        out.append("| 프롬프트 | ").append(environment.promptSource()).append(" |\n");
        out.append("| 검증기 | ").append(environment.validatorSource()).append(" |\n");
        out.append("| 알레르겐 | ").append(environment.allergenSource()).append(" |\n");
        out.append("| repair 정책 | ").append(environment.repairPolicy()).append(" |\n");
        environment.samplingParameters().forEach((key, value) ->
                out.append("| ").append(key).append(" | ").append(value).append(" |\n"));
        out.append("\n모든 모델이 위 표의 동일한 프롬프트, 샘플링, 검증기, repair 정책에서 실행된다. ")
                .append("모델 이름만 바뀐다.\n\n");
    }

    private void appendModels(StringBuilder out) {
        out.append("## 2. 평가 모델\n\n");
        out.append("- 요청: ").append(String.join(", ", requestedModels)).append('\n');
        out.append("- 실행: ").append(String.join(", ", evaluatedModels)).append('\n');
        if (!unavailableModels.isEmpty()) {
            out.append("- 미설치(호출 없이 실패 기록): ").append(String.join(", ", unavailableModels)).append('\n');
        }
        out.append("- Judge 모델: ")
                .append(judgeModel == null || judgeModel.isBlank() ? "미사용" : judgeModel).append('\n');
        out.append('\n');
    }

    private void appendDataset(StringBuilder out) {
        out.append("## 3. 평가 데이터셋\n\n");
        out.append("- 파일: `").append(dataset.resource()).append("`\n");
        out.append("- 전체 케이스 ").append(dataset.totalCases())
                .append("건 중 실행 ").append(dataset.runnableCases()).append("건")
                .append(", 반복 ").append(dataset.repeat()).append("회\n");
        if (!skippedCases.isEmpty()) {
            out.append("- 제외: ").append(String.join(", ", skippedCases)).append('\n');
        }
        out.append('\n');
    }

    private void appendModelResults(StringBuilder out) {
        out.append("## 4. 모델별 종합 결과\n\n");
        out.append("| 모델 | 실행 | 통과 | Pass Rate | Hard Fail | 초안 통과율 | repair 후 통과 |")
                .append(" 검증기 실패율 | 알레르겐 실패 | Schema 실패 | Timeout | 평균 점수 |\n");
        out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        modelSummaries.forEach((model, summary) -> out.append("| `").append(model).append("` | ")
                .append(summary.runs()).append(" | ")
                .append(summary.passed()).append(" | ")
                .append(percent(summary.passRate())).append(" | ")
                .append(summary.hardFails()).append(" | ")
                .append(summary.validatorApplicableRuns() == 0 ? "-"
                        : percent(summary.firstDraftPassRate()) + " (" + summary.firstDraftPassed()
                        + "/" + summary.validatorApplicableRuns() + ")").append(" | ")
                .append(summary.repairAttempted() == 0 ? "-"
                        : summary.repairPassed() + "/" + summary.repairAttempted()
                        + " (" + percent(summary.afterRepairPassRate()) + ")").append(" | ")
                .append(summary.validatorApplicableRuns() == 0 ? "-" : percent(summary.validatorFailureRate()))
                .append(" | ")
                .append(summary.allergenFailures()).append(" | ")
                .append(summary.schemaFailures()).append(" | ")
                .append(summary.timeouts()).append(" | ")
                .append(summary.averageScore()).append(" |\n"));

        out.append("\n점수는 차원 가중치(transport 20, schema 20, validator 30, constraint 15, noise 15) ")
                .append("중 해당 차원의 통과 비율이다. 알레르겐은 점수에 넣지 않고 Hard Fail로만 다룬다.\n\n");

        if (!repeatStats.isEmpty()) {
            out.append("### 반복 실행 편차\n\n");
            out.append("| 모델 | 케이스 | 실행 | 통과 | 평균 점수 | 점수 표준편차 | 평균 지연(ms) | 지연 표준편차 |\n");
            out.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
            repeatStats.forEach(stat -> out.append("| `").append(stat.model()).append("` | ")
                    .append(stat.caseId()).append(" | ")
                    .append(stat.runs()).append(" | ")
                    .append(stat.passed()).append(" | ")
                    .append(stat.meanScore()).append(" | ")
                    .append(stat.scoreStdDev()).append(" | ")
                    .append(stat.meanLatencyMs()).append(" | ")
                    .append(stat.latencyStdDev()).append(" |\n"));
            out.append('\n');
        }
    }

    private void appendLatency(StringBuilder out) {
        out.append("## 5. Latency 비교\n\n");
        out.append("| 모델 | 평균 | 중앙값 | p95 | 최소 | 최대 |\n|---|---:|---:|---:|---:|---:|\n");
        modelSummaries.forEach((model, summary) -> {
            Latency value = summary.latency();
            out.append("| `").append(model).append("` | ")
                    .append(value.avgMs()).append("ms | ")
                    .append(value.medianMs()).append("ms | ")
                    .append(value.p95Ms()).append("ms | ")
                    .append(value.minMs()).append("ms | ")
                    .append(value.maxMs()).append("ms |\n");
        });
        out.append("\n지연은 repair 재호출을 포함한 케이스 전체 소요다. ")
                .append("모델 로딩 편향을 줄이려고 모델마다 워밍업 호출을 한 번 하고 그 호출은 집계에서 뺀다.\n\n");
    }

    private void appendFailureTypes(StringBuilder out) {
        out.append("## 6. 실패 유형 비교\n\n");
        modelSummaries.forEach((model, summary) -> {
            out.append("### ").append(model).append("\n\n");
            if (summary.failureTypes().isEmpty()) {
                out.append("실패 없음\n\n");
                return;
            }
            out.append("```text\n");
            summary.failureTypes().entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .forEach(entry -> out.append(String.format("%-42s %d", entry.getKey(), entry.getValue()))
                            .append('\n'));
            out.append("```\n\n");
        });
    }

    private void appendCaseComparisons(StringBuilder out) {
        out.append("## 7. Case별 입력 / Raw Output / 문제점\n\n");
        for (CaseComparison comparison : caseComparisons) {
            boolean repeated = comparison.outcomes().stream().anyMatch(outcome -> outcome.iteration() > 1);
            out.append("### ").append(comparison.caseId())
                    .append("  `").append(comparison.suite()).append("`\n\n");
            out.append("#### Input\n\n");
            out.append("```text\n").append(comparison.input()).append("\n```\n\n");
            out.append("#### Expected Conditions\n\n");
            comparison.expectedConditions().forEach(condition ->
                    out.append("- ").append(condition).append('\n'));
            out.append("\nRAG 조건: ").append(comparison.ragCondition()).append("\n\n");

            for (ModelOutcome outcome : comparison.outcomes()) {
                out.append("#### ").append(outcome.model());
                if (repeated) {
                    out.append(" (회차 ").append(outcome.iteration()).append(')');
                }
                out.append("\n\n");
                out.append("- 판정: **").append(outcome.verdict()).append("**")
                        .append(", 점수 ").append(outcome.score())
                        .append(", 지연 ").append(outcome.latencyMs()).append("ms")
                        .append(outcome.timeout() ? ", TIMEOUT" : "").append('\n');
                if (outcome.judgeVerdict() != null) {
                    out.append("- Judge(").append(judgeModel).append("): ")
                            .append(outcome.judgeVerdict())
                            .append(", 프로덕션 판정과 일치=").append(outcome.judgeAgreement()).append('\n');
                }
                out.append("\nRaw Output (`").append(outcome.rawOutputFile()).append("`):\n\n");
                out.append("````text\n").append(limitRaw(outcome.rawOutput())).append("\n````\n\n");
                out.append("문제점:\n\n");
                if (outcome.problems().isEmpty()) {
                    out.append("- 없음 (프로덕션 검증기 기준)\n\n");
                } else {
                    outcome.problems().forEach(problem -> out.append("- `").append(problem).append("`\n"));
                    out.append('\n');
                }
            }
        }
    }

    private void appendHardFails(StringBuilder out) {
        out.append("## 8. Hard Fail 목록\n\n");
        if (hardFails.isEmpty()) {
            out.append("없음\n\n");
            return;
        }
        out.append("안전(알레르겐) 실패는 총점과 무관하게 실패로 표시한다.\n\n");
        out.append("| 모델 | 케이스 | 회차 | 충돌 알레르겐 |\n|---|---|---:|---|\n");
        hardFails.forEach(hardFail -> out.append("| `").append(hardFail.model()).append("` | ")
                .append(hardFail.caseId()).append(" | ")
                .append(hardFail.iteration()).append(" | ")
                .append(String.join(", ", hardFail.allergenConflicts())).append(" |\n"));
        out.append('\n');
    }

    /** 측정값만 문장으로 옮긴다. 관측되지 않은 특성은 쓰지 않는다. */
    private void appendModelNotes(StringBuilder out) {
        out.append("## 9. 모델별 장점과 한계 (측정 데이터 기반)\n\n");
        modelSummaries.forEach((model, summary) -> {
            out.append("### ").append(model).append("\n\n");
            if (!summary.available()) {
                out.append("- 설치되지 않아 실제 호출을 하지 못했다. 이 모델에 대한 판단 근거 없음.\n\n");
                return;
            }
            out.append("- 측정: 실행 ").append(summary.runs()).append("건, 통과 ")
                    .append(summary.passed()).append("건(").append(percent(summary.passRate()))
                    .append("), 평균 점수 ").append(summary.averageScore())
                    .append(", 중앙값 지연 ").append(summary.latency().medianMs()).append("ms\n");
            if (summary.validatorApplicableRuns() > 0) {
                out.append("- 첫 초안이 검증기를 통과한 비율: ")
                        .append(percent(summary.firstDraftPassRate()))
                        .append(" (").append(summary.firstDraftPassed()).append("/")
                        .append(summary.validatorApplicableRuns()).append(")\n");
            }
            if (summary.repairAttempted() > 0) {
                out.append("- repair 1회 후 통과: ").append(summary.repairPassed())
                        .append("/").append(summary.repairAttempted()).append('\n');
            }
            summary.failureTypes().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(top -> out.append("- 최빈 실패 유형: `").append(top.getKey())
                            .append("` ").append(top.getValue()).append("건\n"));
            if (summary.allergenFailures() > 0) {
                out.append("- 안전 실패(알레르겐) ").append(summary.allergenFailures()).append("건 관측\n");
            }
            if (summary.timeouts() > 0) {
                out.append("- Timeout ").append(summary.timeouts()).append("건 관측\n");
            }
            out.append('\n');
        });
        out.append("위 항목은 이번 실행에서 관측된 수치만 옮긴 것이다. ")
                .append("표본이 작으면 그대로 일반화할 수 없다.\n\n");
    }

    private void appendUnresolved(StringBuilder out) {
        out.append("## 10. 아직 판단할 수 없는 항목\n\n");
        unresolved.forEach(item -> out.append("- ").append(item).append('\n'));
        out.append('\n');
    }

    /** 터미널 한 화면에 들어가는 요약. */
    public String toConsoleSummary() {
        List<String> lines = new ArrayList<>();
        lines.add("Salus LLM Eval v2  mode=" + mode + "  run=" + runId
                + "  pass=" + passedRuns + "/" + totalRuns + " (" + percent(passRate) + ")"
                + (hardFailRuns > 0 ? "  HARD_FAIL=" + hardFailRuns : ""));
        lines.add(String.format("  %-18s %5s %5s %8s %7s %9s  %s",
                "model", "pass", "runs", "rate", "score", "p50(ms)", "top failure"));
        modelSummaries.forEach((model, summary) -> {
            String topFailure = summary.failureTypes().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(entry -> entry.getKey() + " x" + entry.getValue())
                    .orElse("-");
            lines.add(String.format("  %-18s %5d %5d %8s %7s %9d  %s",
                    model, summary.passed(), summary.runs(), percent(summary.passRate()),
                    String.valueOf(summary.averageScore()), summary.latency().medianMs(), topFailure));
        });
        if (!skippedCases.isEmpty()) {
            lines.add("  제외 " + skippedCases.size() + "건: " + String.join(", ", skippedCases));
        }
        results.stream().filter(result -> !result.isPass()).forEach(result ->
                lines.add("  " + result.verdict() + " [" + result.model() + "] " + result.caseId()
                        + "#" + result.iteration() + " -> " + String.join(" / ", result.failureDetails())));
        return String.join("\n", lines);
    }

    // 아래 static 메서드들은 평균, 표준편차, 백분위수, 비율 표시 같은 통계 계산 도우미입니다.
    private static Latency latency(List<Long> sortedValues) {
        if (sortedValues.isEmpty()) {
            return new Latency(0, 0, 0, 0, 0);
        }
        long sum = sortedValues.stream().mapToLong(Long::longValue).sum();
        return new Latency(
                sum / sortedValues.size(),
                percentile(sortedValues, 0.50),
                percentile(sortedValues, 0.95),
                sortedValues.get(0),
                sortedValues.get(sortedValues.size() - 1));
    }

    private static String limitRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return "(원문 없음 - 호출 실패 또는 빈 응답)";
        }
        if (raw.length() <= MARKDOWN_RAW_LIMIT) {
            return raw;
        }
        return raw.substring(0, MARKDOWN_RAW_LIMIT)
                + "\n... (여기서 잘림. 전체 원문은 raw 파일 참조. 원본 길이 " + raw.length() + "자)";
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double stdDev(List<Double> values) {
        if (values.size() < 2) {
            return 0.0;
        }
        double average = mean(values);
        double variance = values.stream()
                .mapToDouble(value -> (value - average) * (value - average))
                .sum() / values.size();
        return Math.sqrt(variance);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static String percent(double rate) {
        return String.format("%.1f%%", rate * 100);
    }

    private static double rate(int passed, int total) {
        return total == 0 ? 0.0 : (double) passed / total;
    }

    private static long percentile(List<Long> sortedValues, double quantile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(quantile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
