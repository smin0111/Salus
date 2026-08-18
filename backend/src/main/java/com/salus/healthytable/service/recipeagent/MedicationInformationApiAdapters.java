package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
class MfdsDrugProductPermitAdapter implements MfdsDrugProductPermitPort {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final Duration timeout;
    private final String listPath;
    private final String detailPath;
    private final String ingredientPath;
    private final int maxIngredientPages;
    private final int maxIngredientItems;

    @Autowired
    MfdsDrugProductPermitAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${recipe.agent.mfds-drug-product-permit-enabled:false}") boolean enabled,
            @Value("${recipe.agent.mfds-medication-api-key:}") String apiKey,
            @Value("${recipe.agent.mfds-drug-product-permit-base-url:https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService07}") String baseUrl,
            @Value("${recipe.agent.mfds-drug-product-permit-list-path:/getDrugPrdtPrmsnInq07}") String listPath,
            @Value("${recipe.agent.mfds-drug-product-permit-detail-path:/getDrugPrdtPrmsnDtlInq06}") String detailPath,
            @Value("${recipe.agent.mfds-drug-product-permit-ingredient-path:/getDrugPrdtMcpnDtlInq07}") String ingredientPath,
            @Value("${recipe.agent.mfds-medication-timeout-ms:5000}") long timeoutMs) {
        this(webClientBuilder, objectMapper, enabled, apiKey, baseUrl, listPath, detailPath, ingredientPath, timeoutMs, 5, 100);
    }

    MfdsDrugProductPermitAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            boolean enabled,
            String apiKey,
            String baseUrl,
            String listPath,
            String detailPath,
            String ingredientPath,
            long timeoutMs,
            int maxIngredientPages,
            int maxIngredientItems) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.listPath = normalizePath(listPath);
        this.detailPath = normalizePath(detailPath);
        this.ingredientPath = normalizePath(ingredientPath);
        this.maxIngredientPages = Math.max(1, maxIngredientPages);
        this.maxIngredientItems = Math.max(1, maxIngredientItems);
    }

    @Override
    public MfdsDrugProductSearchResult search(MedicationInput medication) {
        if (!enabled || apiKey.isBlank()) {
            return new MfdsDrugProductSearchResult(MedicationDataStatus.API_DISABLED, List.of(), List.of("MFDS drug product permit API is disabled."));
        }
        if (medication == null || medication.originalName() == null || medication.originalName().isBlank()) {
            return new MfdsDrugProductSearchResult(MedicationDataStatus.NOT_FOUND, List.of(), List.of("Medication query is empty."));
        }
        try {
            String body = webClient.get()
                    .uri(builder -> builder.path(listPath)
                            .queryParam("serviceKey", apiKey)
                            .queryParam("type", "json")
                            .queryParam("item_name", medication.originalName())
                            .queryParam("itemName", medication.originalName())
                            .queryParam("numOfRows", 10)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .onErrorResume(e -> {
                        log.warn("[MedicationInfo] MFDS product permit list request failed. category={}", e.getClass().getSimpleName());
                        return Mono.empty();
                    })
                    .block();
            if (body == null || body.isBlank()) {
                return new MfdsDrugProductSearchResult(MedicationDataStatus.API_FAILED, List.of(), List.of("MFDS product permit response was empty."));
            }
            MfdsDrugProductSearchResult parsed = parseSearchResponse(body, medication);
            if (parsed.status() != MedicationDataStatus.FOUND) {
                return parsed;
            }
            IngredientDiagnosticsBuilder diagnostics = new IngredientDiagnosticsBuilder()
                    .merge(parsed.ingredientDiagnostics());
            List<MfdsResponseFieldStructure> fieldStructures = new ArrayList<>(parsed.responseFieldStructures());
            List<MfdsDrugProductCandidate> enriched = new ArrayList<>();
            for (MfdsDrugProductCandidate candidate : parsed.candidates().stream().limit(10).toList()) {
                EnrichedMfdsCandidate enrichedCandidate = withDetails(candidate);
                enriched.add(enrichedCandidate.candidate());
                diagnostics.merge(enrichedCandidate.diagnostics());
                fieldStructures.addAll(enrichedCandidate.fieldStructures());
            }
            return new MfdsDrugProductSearchResult(enriched.size() > 1 ? MedicationDataStatus.MULTIPLE_RESULTS : MedicationDataStatus.FOUND,
                    enriched,
                    parsed.warnings(),
                    diagnostics.toDiagnostics(),
                    summarizeFieldStructures(fieldStructures));
        } catch (Exception e) {
            log.warn("[MedicationInfo] MFDS product permit request failed. category={}", e.getClass().getSimpleName());
            return new MfdsDrugProductSearchResult(MedicationDataStatus.API_FAILED, List.of(), List.of("MFDS product permit API failed."));
        }
    }

    MfdsDrugProductSearchResult parseSearchResponse(String body, MedicationInput input) {
        try {
            JsonNode root = objectMapper.readTree(body);
            MedicationDataStatus headerStatus = headerStatus(root);
            if (headerStatus == MedicationDataStatus.API_FAILED) {
                return new MfdsDrugProductSearchResult(MedicationDataStatus.API_FAILED, List.of(), List.of(headerMessage(root)));
            }
            List<JsonNode> items = itemNodes(root);
            if (items.isEmpty()) {
                return new MfdsDrugProductSearchResult(MedicationDataStatus.NOT_FOUND, List.of(), List.of());
            }
            List<MfdsDrugProductCandidate> candidates = items.stream()
                    .map(item -> parseCandidate(item, input, MfdsIngredientSourceType.PRODUCT_CANDIDATE_HINT))
                    .toList();
            MfdsIngredientMappingDiagnostics diagnostics = new IngredientDiagnosticsBuilder()
                    .recordProductCandidates(candidates)
                    .toDiagnostics();
            return new MfdsDrugProductSearchResult(candidates.size() > 1 ? MedicationDataStatus.MULTIPLE_RESULTS : MedicationDataStatus.FOUND,
                    candidates,
                    List.of(),
                    diagnostics,
                    summarizeFieldStructures(fieldStructures(items)));
        } catch (Exception e) {
            return new MfdsDrugProductSearchResult(MedicationDataStatus.PARSING_FAILED, List.of(), List.of("MFDS product permit JSON parsing failed."));
        }
    }

    MfdsDrugProductCandidate parseCandidate(JsonNode item, MedicationInput input) {
        return parseCandidate(item, input, MfdsIngredientSourceType.PRODUCT_CANDIDATE_HINT);
    }

    MfdsDrugProductCandidate parseCandidate(JsonNode item, MedicationInput input, MfdsIngredientSourceType ingredientSourceType) {
        List<MfdsActiveIngredient> activeIngredients = parseIngredientNodes(itemNodes(item), ingredientSourceType);
        String productName = clean(text(item, "ITEM_NAME", "itemName", "item_name", "PRDLST_NM"));
        double confidence = RecipeCandidate.normalize(productName).equals(RecipeCandidate.normalize(input == null ? "" : input.originalName()))
                ? 0.98
                : 0.72;
        return new MfdsDrugProductCandidate(
                clean(text(item, "ITEM_SEQ", "itemSeq", "item_seq", "ITEM_SEQ_NO")),
                productName,
                clean(text(item, "ENTP_NAME", "entpName", "manufacturerName", "BSSH_NM")),
                clean(text(item, "FORM_CODE_NAME", "formCodeName", "dosageForm", "CHART", "ITEM_FORM")),
                activeIngredients,
                clean(text(item, "ITEM_PERMIT_NO", "permitNumber", "PERMIT_NO")),
                clean(text(item, "ITEM_PERMIT_DATE", "permitDate", "PERMIT_DATE")),
                canceled(item),
                confidence);
    }

    List<MfdsActiveIngredient> parseIngredientResponse(String body) {
        return parseIngredientResponseResult(body).ingredients();
    }

    MfdsIngredientMappingDiagnostics parseIngredientDiagnostics(String body) {
        return parseIngredientResponseResult(body).diagnostics();
    }

    List<MfdsResponseFieldStructure> parseResponseFieldStructures(String body) {
        return parseIngredientResponseResult(body).fieldStructures();
    }

    private MfdsIngredientParseResult parseIngredientResponseResult(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (headerStatus(root) == MedicationDataStatus.API_FAILED) {
                return new MfdsIngredientParseResult(List.of(), MfdsIngredientMappingDiagnostics.empty(), List.of());
            }
            List<JsonNode> nodes = itemNodes(root);
            List<MfdsActiveIngredient> ingredients = parseIngredientNodes(nodes, MfdsIngredientSourceType.PRODUCT_INGREDIENT_ENDPOINT);
            IngredientDiagnosticsBuilder diagnostics = new IngredientDiagnosticsBuilder()
                    .recordIngredientResponse(
                            nodes.size(),
                            ingredients.size(),
                            intValue(root, "/body/totalCount", "/response/body/totalCount", "/totalCount"),
                            intValue(root, "/body/pageNo", "/response/body/pageNo", "/pageNo"),
                            intValue(root, "/body/numOfRows", "/response/body/numOfRows", "/numOfRows"));
            return new MfdsIngredientParseResult(
                    ingredients,
                    diagnostics.toDiagnostics(),
                    summarizeFieldStructures(fieldStructures(nodes)));
        } catch (Exception e) {
            return new MfdsIngredientParseResult(List.of(), MfdsIngredientMappingDiagnostics.empty(), List.of());
        }
    }

    MfdsDrugProductCandidate parseDetailResponse(String body, MfdsDrugProductCandidate fallback) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<JsonNode> items = itemNodes(root);
            if (items.isEmpty()) {
                return fallback;
            }
            MfdsDrugProductCandidate parsed = parseCandidate(items.get(0), new MedicationInput(fallback.productName(), "", ""), MfdsIngredientSourceType.PRODUCT_CANDIDATE_HINT);
            return mergeCandidate(fallback, parsed, parseDetailIngredients(items.get(0), fallback.itemSequence()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private EnrichedMfdsCandidate withDetails(MfdsDrugProductCandidate candidate) {
        IngredientDiagnosticsBuilder diagnostics = new IngredientDiagnosticsBuilder();
        List<MfdsResponseFieldStructure> fieldStructures = new ArrayList<>();
        if (candidate.itemSequence() == null || candidate.itemSequence().isBlank()) {
            diagnostics.addStatus(MfdsIngredientDiagnosticStatus.PRODUCT_CODE_MISSING);
            diagnostics.addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_REQUEST_NOT_EXECUTED);
            return new EnrichedMfdsCandidate(candidate, diagnostics.toDiagnostics(), List.of());
        }
        MfdsDrugProductCandidate detailed = candidate;
        long detailStarted = System.nanoTime();
        String detailBody = requestByItemSequence(detailPath, candidate.itemSequence(), "detail");
        diagnostics.recordDetailLatency((System.nanoTime() - detailStarted) / 1_000_000L);
        if (detailBody != null && !detailBody.isBlank()) {
            detailed = parseDetailResponse(detailBody, candidate);
            fieldStructures.addAll(parseResponseFieldStructures(detailBody));
        }
        MfdsIngredientParseResult ingredientResult = requestIngredientPages(detailed);
        List<MfdsActiveIngredient> ingredients = ingredientResult.ingredients();
        diagnostics.merge(ingredientResult.diagnostics());
        fieldStructures.addAll(ingredientResult.fieldStructures());
        if (ingredients.isEmpty()) {
            return new EnrichedMfdsCandidate(detailed, diagnostics.toDiagnostics(), summarizeFieldStructures(fieldStructures));
        }
        MfdsDrugProductCandidate merged = mergeCandidate(detailed, detailed, ingredients);
        diagnostics.recordIngredientMerged(ingredients.size());
        return new EnrichedMfdsCandidate(merged, diagnostics.toDiagnostics(), summarizeFieldStructures(fieldStructures));
    }

    private MfdsIngredientParseResult requestIngredientPages(MfdsDrugProductCandidate candidate) {
        List<MfdsActiveIngredient> ingredients = new ArrayList<>();
        List<MfdsResponseFieldStructure> fieldStructures = new ArrayList<>();
        IngredientDiagnosticsBuilder diagnostics = new IngredientDiagnosticsBuilder();
        if (candidate == null || candidate.productName() == null || candidate.productName().isBlank()) {
            diagnostics.addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_ENDPOINT_NOT_USABLE_FOR_PRODUCT_LOOKUP);
            return new MfdsIngredientParseResult(List.of(), diagnostics.toDiagnostics(), List.of());
        }
        int pageSize = Math.min(100, maxIngredientItems);
        int responseItemsSeen = 0;
        boolean truncated = false;
        for (int page = 1; page <= maxIngredientPages && ingredients.size() < maxIngredientItems; page++) {
            diagnostics.recordIngredientRequest();
            long ingredientStarted = System.nanoTime();
            String ingredientBody = requestIngredientByProductFilter(candidate, page, pageSize);
            diagnostics.recordIngredientLatency((System.nanoTime() - ingredientStarted) / 1_000_000L);
            MfdsIngredientParseResult pageResult = ingredientBody == null
                    ? new MfdsIngredientParseResult(List.of(), MfdsIngredientMappingDiagnostics.empty(), List.of())
                    : parseIngredientResponseResult(ingredientBody);
            diagnostics.merge(pageResult.diagnostics());
            fieldStructures.addAll(pageResult.fieldStructures());
            responseItemsSeen += pageResult.diagnostics().ingredientResponseActualItemCount();
            IngredientCodeAudit pageAudit = ingredientCodeAudit(candidate, pageResult.ingredients());
            diagnostics.recordIngredientProductCodeAudit(pageAudit.matchingCount(), pageAudit.mismatchingCount(), pageAudit.missingCount(), uniqueIngredientNameCount(pageAudit.matchingIngredients()));
            if (pageAudit.mismatchingCount() > 0 || (pageAudit.matchingCount() == 0 && pageResult.diagnostics().ingredientResponseActualItemCount() > 0)) {
                diagnostics.addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_ENDPOINT_NOT_USABLE_FOR_PRODUCT_LOOKUP);
                break;
            }
            int remaining = maxIngredientItems - ingredients.size();
            if (pageAudit.matchingIngredients().size() > remaining) {
                ingredients.addAll(pageAudit.matchingIngredients().subList(0, remaining));
                truncated = true;
            } else {
                ingredients.addAll(pageAudit.matchingIngredients());
            }
            if (!pageResult.diagnostics().ingredientResponseHasNextPage()) {
                break;
            }
            if (page == maxIngredientPages || ingredients.size() >= maxIngredientItems || responseItemsSeen >= maxIngredientItems) {
                truncated = true;
                break;
            }
        }
        if (truncated) {
            diagnostics.addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_RESULTS_TRUNCATED);
        }
        return new MfdsIngredientParseResult(ingredients, diagnostics.toDiagnostics(), summarizeFieldStructures(fieldStructures));
    }

    private List<MfdsActiveIngredient> matchingIngredients(
            MfdsDrugProductCandidate candidate,
            List<MfdsActiveIngredient> ingredients,
            IngredientDiagnosticsBuilder diagnostics) {
        IngredientCodeAudit audit = ingredientCodeAudit(candidate, ingredients);
        diagnostics.recordIngredientProductCodeAudit(audit.matchingCount(), audit.mismatchingCount(), audit.missingCount(), uniqueIngredientNameCount(audit.matchingIngredients()));
        return audit.matchingIngredients();
    }

    private IngredientCodeAudit ingredientCodeAudit(
            MfdsDrugProductCandidate candidate,
            List<MfdsActiveIngredient> ingredients) {
        String productCode = RecipeCandidate.normalize(candidate.itemSequence());
        List<MfdsActiveIngredient> matching = new ArrayList<>();
        int mismatching = 0;
        int missing = 0;
        for (MfdsActiveIngredient ingredient : ingredients == null ? List.<MfdsActiveIngredient>of() : ingredients) {
            String ingredientCode = RecipeCandidate.normalize(ingredient.itemSequence());
            if (ingredientCode.isBlank()) {
                missing++;
                continue;
            }
            if (ingredientCode.equals(productCode)) {
                matching.add(ingredient);
            } else {
                mismatching++;
            }
        }
        return new IngredientCodeAudit(matching, matching.size(), mismatching, missing);
    }

    private int uniqueIngredientNameCount(List<MfdsActiveIngredient> ingredients) {
        return (int) (ingredients == null ? List.<MfdsActiveIngredient>of() : ingredients).stream()
                .map(ingredient -> firstNonBlank(ingredient.koreanName(), ingredient.englishName()))
                .map(RecipeCandidate::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
    }

    private String requestByItemSequence(String path, String itemSequence, String category) {
        return requestByItemSequence(path, itemSequence, category, 1, 0);
    }

    private String requestIngredientByProductFilter(MfdsDrugProductCandidate candidate, int pageNo, int numOfRows) {
        try {
            return webClient.get()
                    .uri(builder -> builder.path(ingredientPath)
                            .queryParam("serviceKey", apiKey)
                            .queryParam("type", "json")
                            .queryParam("Prduct", candidate.productName())
                            .queryParamIfPresent("Entrps", candidate.manufacturerName() == null || candidate.manufacturerName().isBlank()
                                    ? java.util.Optional.empty()
                                    : java.util.Optional.of(candidate.manufacturerName()))
                            .queryParam("pageNo", Math.max(1, pageNo))
                            .queryParam("numOfRows", Math.max(1, numOfRows))
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .onErrorResume(e -> {
                        log.warn("[MedicationInfo] MFDS product permit ingredient request failed. category={}", e.getClass().getSimpleName());
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            log.warn("[MedicationInfo] MFDS product permit ingredient request failed. category={}", e.getClass().getSimpleName());
            return "";
        }
    }

    private String requestByItemSequence(String path, String itemSequence, String category, int pageNo, int numOfRows) {
        try {
            return webClient.get()
                    .uri(builder -> builder.path(path)
                            .queryParam("serviceKey", apiKey)
                            .queryParam("type", "json")
                            .queryParam("item_seq", itemSequence)
                            .queryParam("pageNo", Math.max(1, pageNo))
                            .queryParamIfPresent("numOfRows", numOfRows > 0 ? java.util.Optional.of(numOfRows) : java.util.Optional.empty())
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .onErrorResume(e -> {
                        log.warn("[MedicationInfo] MFDS product permit {} request failed. category={}", category, e.getClass().getSimpleName());
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            log.warn("[MedicationInfo] MFDS product permit {} request failed. category={}", category, e.getClass().getSimpleName());
            return "";
        }
    }

    private MfdsDrugProductCandidate mergeCandidate(
            MfdsDrugProductCandidate base,
            MfdsDrugProductCandidate detail,
            List<MfdsActiveIngredient> ingredients) {
        return new MfdsDrugProductCandidate(
                firstNonBlank(detail.itemSequence(), base.itemSequence()),
                firstNonBlank(detail.productName(), base.productName()),
                firstNonBlank(detail.manufacturerName(), base.manufacturerName()),
                firstNonBlank(detail.dosageForm(), base.dosageForm()),
                ingredients == null || ingredients.isEmpty() ? base.activeIngredients() : ingredients,
                firstNonBlank(detail.permitNumber(), base.permitNumber()),
                firstNonBlank(detail.permitDate(), base.permitDate()),
                detail.canceled() || base.canceled(),
                Math.max(base.matchConfidence(), detail.matchConfidence()));
    }

    private List<MfdsActiveIngredient> parseIngredientNodes(List<JsonNode> nodes) {
        return parseIngredientNodes(nodes, MfdsIngredientSourceType.PRODUCT_INGREDIENT_ENDPOINT);
    }

    private List<MfdsActiveIngredient> parseIngredientNodes(List<JsonNode> nodes, MfdsIngredientSourceType sourceType) {
        List<MfdsActiveIngredient> ingredients = new ArrayList<>();
        for (JsonNode node : nodes) {
            String korean = clean(text(node,
                    "MATERIAL_NAME",
                    "materialName",
                    "MATERIAL_NM",
                    "MTRAL_NAME",
                    "MTRAL_NM",
                    "MTRL_NAME",
                    "MTRL_NM",
                    "MAIN_ITEM_INGR",
                    "mainItemIngr",
                    "MAIN_ITEM_INGR_NAME",
                    "INGR_KOR_NAME",
                    "INGR_KOR_NM",
                    "INGR_NAME",
                    "INGR_NM",
                    "MCPN_NAME",
                    "MCPN_NM",
                    "ITEM_INGR_NAME",
                    "ITEM_INGR_NM",
                    "VALID_INGR_NM",
                    "koreanName"));
            String english = clean(text(node,
                    "MATERIAL_ENG_NAME",
                    "materialEngName",
                    "MATERIAL_NAME_ENG",
                    "MATERIAL_ENG_NM",
                    "MTRL_ENG_NAME",
                    "MTRL_ENG_NM",
                    "INGR_ENG_NAME",
                    "INGR_ENG_NM",
                    "MCPN_ENG_NAME",
                    "MCPN_ENG_NM",
                    "MAIN_INGR_ENG",
                    "englishName"));
            String amount = clean(text(node,
                    "MATERIAL_AMT",
                    "materialAmount",
                    "MATERIAL_AMOUNT",
                    "MTRL_AMT",
                    "MTRL_AMOUNT",
                    "INGR_AMT",
                    "INGR_AMOUNT",
                    "MCPN_AMT",
                    "AMOUNT",
                    "QNT",
                    "QTY",
                    "amount"));
            String unit = clean(text(node,
                    "MATERIAL_UNIT",
                    "materialUnit",
                    "MTRL_UNIT",
                    "INGR_UNIT",
                    "MCPN_UNIT",
                    "INGD_UNIT_CD",
                    "UNIT",
                    "unit"));
            if (!korean.isBlank() || !english.isBlank()) {
                ingredients.add(new MfdsActiveIngredient(
                        clean(text(node, "ITEM_SEQ", "itemSeq", "item_seq", "ITEM_SEQ_NO")),
                        korean,
                        english,
                        materialRole(text(node, "MAIN_INGR_YN", "VALID_INGR_YN", "MATERIAL_TYPE", "INGR_TYPE", "activeIngredientFlag")),
                        sourceType,
                        clean(text(node, "MAIN_INGR_YN", "VALID_INGR_YN", "MATERIAL_TYPE", "INGR_TYPE", "activeIngredientFlag")),
                        amount,
                        unit,
                        clean(text(node, "TOTAL_AMOUNT", "TOTAL_AMT", "TOT_AMOUNT", "TOT_AMT", "TOTAL_QTY", "TOT_QTY")),
                        clean(text(node, "TOTAL_UNIT", "TOT_UNIT", "TOTAL_QTY_UNIT", "TOT_QTY_UNIT")),
                        clean(text(node, "TOTAL_AMOUNT_SEQ", "TOTAL_AMT_SEQ", "TOT_AMOUNT_SEQ", "TOT_AMT_SEQ", "TOTAL_QTY_SEQ", "TOT_QTY_SEQ", "TAMT_SEQ")),
                        clean(text(node, "SEQ", "SERIAL_NO", "SERIAL_NUMBER", "MATERIAL_SEQ", "MTRAL_SN", "INGR_SEQ", "MCPN_SEQ", "NO"))));
            }
        }
        return ingredients.stream()
                .distinct()
                .toList();
    }

    private List<MfdsActiveIngredient> parseDetailIngredients(JsonNode item, String itemSequence) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return List.of();
        }
        List<MfdsActiveIngredient> ingredients = new ArrayList<>();
        String mainKorean = clean(text(item, "MAIN_ITEM_INGR", "ITEM_INGR_NAME"));
        String mainEnglish = clean(text(item, "MAIN_INGR_ENG"));
        if (structurableIngredientText(mainKorean) || structurableIngredientText(mainEnglish)) {
            ingredients.add(new MfdsActiveIngredient(
                    clean(itemSequence),
                    structurableIngredientText(mainKorean) ? mainKorean : "",
                    structurableIngredientText(mainEnglish) ? mainEnglish : "",
                    MfdsMaterialRole.ACTIVE_INGREDIENT,
                    MfdsIngredientSourceType.DETAIL_ACTIVE_INGREDIENT_TEXT,
                    "MAIN_ITEM_INGR",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""));
        }
        String other = clean(text(item, "INGR_NAME"));
        if (structurableIngredientText(other)) {
            ingredients.add(new MfdsActiveIngredient(
                    clean(itemSequence),
                    other,
                    "",
                    MfdsMaterialRole.OTHER_MATERIAL,
                    MfdsIngredientSourceType.DETAIL_OTHER_INGREDIENT_TEXT,
                    "INGR_NAME",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""));
        }
        return ingredients;
    }

    private boolean structurableIngredientText(String value) {
        String text = clean(value);
        return !text.isBlank()
                && text.length() <= 80
                && !text.matches(".*[,;；，/\\n\\r].*")
                && !text.contains("(")
                && !text.contains(")")
                && !text.contains("[")
                && !text.contains("]")
                && !text.contains("+");
    }

    private MfdsMaterialRole materialRole(String value) {
        String normalized = RecipeCandidate.normalize(value);
        if (normalized.isBlank()) {
            return MfdsMaterialRole.UNKNOWN_MATERIAL_ROLE;
        }
        if (normalized.equals("y") || normalized.contains("유효") || normalized.contains("주성분") || normalized.contains("active")) {
            return MfdsMaterialRole.ACTIVE_INGREDIENT;
        }
        if (normalized.equals("n") || normalized.contains("첨가") || normalized.contains("기타") || normalized.contains("inactive")) {
            return MfdsMaterialRole.OTHER_MATERIAL;
        }
        return MfdsMaterialRole.UNKNOWN_MATERIAL_ROLE;
    }

    private List<MfdsResponseFieldStructure> fieldStructures(List<JsonNode> nodes) {
        Map<String, FieldStructureCounter> counters = new LinkedHashMap<>();
        for (JsonNode node : nodes == null ? List.<JsonNode>of() : nodes) {
            if (node == null || !node.isObject()) {
                continue;
            }
            node.fields().forEachRemaining(entry -> counters
                    .computeIfAbsent(entry.getKey(), FieldStructureCounter::new)
                    .record(entry.getValue()));
        }
        List<MfdsResponseFieldStructure> structures = new ArrayList<>();
        for (FieldStructureCounter counter : counters.values()) {
            structures.add(counter.toStructure());
        }
        return structures;
    }

    private List<MfdsResponseFieldStructure> summarizeFieldStructures(List<MfdsResponseFieldStructure> structures) {
        Map<String, FieldStructureCounter> counters = new LinkedHashMap<>();
        for (MfdsResponseFieldStructure structure : structures == null ? List.<MfdsResponseFieldStructure>of() : structures) {
            counters.computeIfAbsent(structure.fieldName(), FieldStructureCounter::new)
                    .record(structure);
        }
        return counters.values().stream()
                .map(FieldStructureCounter::toStructure)
                .toList();
    }

    private List<JsonNode> itemNodes(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        JsonNode items = firstExisting(root,
                "/body/items",
                "/response/body/items",
                "/body/items/item",
                "/response/body/items/item",
                "/items",
                "/item");
        if (items.isMissingNode()) {
            items = root;
        }
        if (items.isObject() && items.has("item")) {
            items = items.get("item");
        }
        List<JsonNode> nodes = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(nodes::add);
        } else if (items.isObject() && !looksLikeEnvelope(items)) {
            nodes.add(items);
        }
        return nodes;
    }

    private boolean looksLikeEnvelope(JsonNode node) {
        return node.has("header") || node.has("body") || node.has("response");
    }

    private JsonNode firstExisting(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            JsonNode found = root.at(pointer);
            if (!found.isMissingNode() && !found.isNull()) {
                return found;
            }
        }
        return objectMapper.missingNode();
    }

    private MedicationDataStatus headerStatus(JsonNode root) {
        String code = text(root, "/header/resultCode", "/response/header/resultCode", "/resultCode");
        if (code.isBlank() || "00".equals(code) || "0000".equals(code)) {
            return MedicationDataStatus.FOUND;
        }
        return MedicationDataStatus.API_FAILED;
    }

    private String headerMessage(JsonNode root) {
        String message = text(root, "/header/resultMsg", "/response/header/resultMsg", "/resultMsg");
        return message.isBlank() ? "MFDS product permit resultCode was not successful." : clean(message);
    }

    private boolean canceled(JsonNode item) {
        String canceled = RecipeCandidate.normalize(text(item,
                "CANCEL_DATE",
                "cancelDate",
                "CANCEL_NAME",
                "cancelName",
                "CANCEL_YN",
                "cancelYn"));
        return !canceled.isBlank() && (canceled.contains("취소") || canceled.contains("취하") || canceled.contains("y") || canceled.matches("\\d{8,}"));
    }

    private int intValue(JsonNode node, String... fieldsOrPointers) {
        String value = text(node, fieldsOrPointers);
        if (value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String text(JsonNode node, String... fieldsOrPointers) {
        for (String field : fieldsOrPointers) {
            JsonNode value = field.startsWith("/") ? node.at(field) : node.get(field);
            if (value != null && !value.isMissingNode() && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText("").trim();
            }
        }
        return "";
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return secondary == null ? "" : secondary;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private record EnrichedMfdsCandidate(
            MfdsDrugProductCandidate candidate,
            MfdsIngredientMappingDiagnostics diagnostics,
            List<MfdsResponseFieldStructure> fieldStructures
    ) {
        private EnrichedMfdsCandidate {
            diagnostics = diagnostics == null ? MfdsIngredientMappingDiagnostics.empty() : diagnostics;
            fieldStructures = fieldStructures == null ? List.of() : List.copyOf(fieldStructures);
        }
    }

    private record MfdsIngredientParseResult(
            List<MfdsActiveIngredient> ingredients,
            MfdsIngredientMappingDiagnostics diagnostics,
            List<MfdsResponseFieldStructure> fieldStructures
    ) {
        private MfdsIngredientParseResult {
            ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
            diagnostics = diagnostics == null ? MfdsIngredientMappingDiagnostics.empty() : diagnostics;
            fieldStructures = fieldStructures == null ? List.of() : List.copyOf(fieldStructures);
        }
    }

    private record IngredientCodeAudit(
            List<MfdsActiveIngredient> matchingIngredients,
            int matchingCount,
            int mismatchingCount,
            int missingCount
    ) {
        private IngredientCodeAudit {
            matchingIngredients = matchingIngredients == null ? List.of() : List.copyOf(matchingIngredients);
        }
    }

    private static class IngredientDiagnosticsBuilder {
        private final LinkedHashSet<MfdsIngredientDiagnosticStatus> statuses = new LinkedHashSet<>();
        private final LinkedHashSet<MfdsIngredientExclusionReason> exclusionReasons = new LinkedHashSet<>();
        private int productCandidateCount;
        private int candidatesWithProductCode;
        private int ingredientRequestCount;
        private int ingredientResponseItemCount;
        private int ingredientMatchingProductCodeCount;
        private int ingredientMismatchingProductCodeCount;
        private int ingredientMissingProductCodeCount;
        private int ingredientParsingRejectedCount;
        private int ingredientOtherwiseExcludedCount;
        private int ingredientDtoCount;
        private int ingredientDomainCount;
        private int ingredientMergedCount;
        private int structuredIngredientCount;
        private int productCandidateIngredientHintCount;
        private int uniqueIngredientNameCount;
        private int ingredientResponseTotalCount;
        private int ingredientResponsePageNo;
        private int ingredientResponseNumOfRows;
        private int ingredientResponseActualItemCount;
        private boolean ingredientResponseHasNextPage;
        private long mfdsDetailLatencyMs;
        private long mfdsIngredientLatencyMs;

        IngredientDiagnosticsBuilder addStatus(MfdsIngredientDiagnosticStatus status) {
            if (status != null) {
                statuses.add(status);
            }
            return this;
        }

        IngredientDiagnosticsBuilder addExclusionReason(MfdsIngredientExclusionReason reason) {
            if (reason != null) {
                exclusionReasons.add(reason);
            }
            return this;
        }

        IngredientDiagnosticsBuilder recordProductCandidates(List<MfdsDrugProductCandidate> candidates) {
            List<MfdsDrugProductCandidate> safeCandidates = candidates == null ? List.of() : candidates;
            productCandidateCount += safeCandidates.size();
            int withCode = (int) safeCandidates.stream()
                    .filter(candidate -> candidate.itemSequence() != null && !candidate.itemSequence().isBlank())
                    .count();
            candidatesWithProductCode += withCode;
            productCandidateIngredientHintCount += safeCandidates.stream()
                    .flatMap(candidate -> candidate.activeIngredients().stream())
                    .filter(ingredient -> ingredient.sourceType() == MfdsIngredientSourceType.PRODUCT_CANDIDATE_HINT)
                    .mapToInt(ignored -> 1)
                    .sum();
            if (withCode < safeCandidates.size()) {
                addStatus(MfdsIngredientDiagnosticStatus.PRODUCT_CODE_MISSING);
            }
            return this;
        }

        IngredientDiagnosticsBuilder recordIngredientRequest() {
            ingredientRequestCount++;
            return this;
        }

        IngredientDiagnosticsBuilder recordDetailLatency(long latencyMs) {
            mfdsDetailLatencyMs += Math.max(0, latencyMs);
            return this;
        }

        IngredientDiagnosticsBuilder recordIngredientLatency(long latencyMs) {
            mfdsIngredientLatencyMs += Math.max(0, latencyMs);
            return this;
        }

        IngredientDiagnosticsBuilder recordIngredientResponse(
                int responseItemCount,
                int mappedIngredientCount,
                int totalCount,
                int pageNo,
                int numOfRows) {
            ingredientResponseItemCount += Math.max(0, responseItemCount);
            ingredientResponseActualItemCount += Math.max(0, responseItemCount);
            ingredientResponseTotalCount = Math.max(ingredientResponseTotalCount, Math.max(0, totalCount));
            ingredientResponsePageNo = Math.max(ingredientResponsePageNo, pageNo);
            ingredientResponseNumOfRows = Math.max(ingredientResponseNumOfRows, numOfRows);
            if (totalCount > 0 && pageNo > 0 && numOfRows > 0 && totalCount > pageNo * numOfRows) {
                ingredientResponseHasNextPage = true;
            }
            int rejected = Math.max(0, responseItemCount - mappedIngredientCount);
            ingredientParsingRejectedCount += rejected;
            if (rejected > 0) {
                addExclusionReason(MfdsIngredientExclusionReason.MATERIAL_NAME_MISSING);
            }
            ingredientDtoCount += Math.max(0, mappedIngredientCount);
            ingredientDomainCount += Math.max(0, mappedIngredientCount);
            if (responseItemCount > 0) {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_RESPONSE_ITEMS_FOUND);
            } else {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_API_SUCCESS_EMPTY);
            }
            if (mappedIngredientCount > 0) {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_DTO_MAPPED);
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_DOMAIN_MAPPED);
            } else if (responseItemCount > 0) {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_FIELD_UNRECOGNIZED);
            }
            return this;
        }

        IngredientDiagnosticsBuilder recordIngredientProductCodeAudit(int matching, int mismatching, int missing, int uniqueNames) {
            ingredientMatchingProductCodeCount += Math.max(0, matching);
            ingredientMismatchingProductCodeCount += Math.max(0, mismatching);
            ingredientMissingProductCodeCount += Math.max(0, missing);
            uniqueIngredientNameCount += Math.max(0, uniqueNames);
            if (matching > 0) {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_PRODUCT_CODE_MATCHED);
            }
            if (mismatching > 0) {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_PRODUCT_CODE_MISMATCH);
            }
            if (missing > 0) {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_PRODUCT_CODE_MISSING);
            }
            if (matching > 0 && mismatching > 0) {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_RESPONSE_MIXED_PRODUCTS);
            }
            return this;
        }

        IngredientDiagnosticsBuilder recordIngredientMerged(int count) {
            ingredientMergedCount += Math.max(0, count);
            structuredIngredientCount += Math.max(0, count);
            if (count > 0) {
                addStatus(MfdsIngredientDiagnosticStatus.INGREDIENT_MERGED);
            }
            return this;
        }

        IngredientDiagnosticsBuilder recordStructuredIngredients(List<MfdsActiveIngredient> ingredients) {
            structuredIngredientCount += (int) (ingredients == null ? List.<MfdsActiveIngredient>of() : ingredients).stream()
                    .filter(ingredient -> ingredient.sourceType() != MfdsIngredientSourceType.PRODUCT_CANDIDATE_HINT)
                    .count();
            return this;
        }

        IngredientDiagnosticsBuilder merge(MfdsIngredientMappingDiagnostics diagnostics) {
            if (diagnostics == null) {
                return this;
            }
            statuses.addAll(diagnostics.statuses());
            exclusionReasons.addAll(diagnostics.exclusionReasons());
            productCandidateCount += diagnostics.productCandidateCount();
            candidatesWithProductCode += diagnostics.candidatesWithProductCode();
            ingredientRequestCount += diagnostics.ingredientRequestCount();
            ingredientResponseItemCount += diagnostics.ingredientResponseItemCount();
            ingredientMatchingProductCodeCount += diagnostics.ingredientMatchingProductCodeCount();
            ingredientMismatchingProductCodeCount += diagnostics.ingredientMismatchingProductCodeCount();
            ingredientMissingProductCodeCount += diagnostics.ingredientMissingProductCodeCount();
            ingredientParsingRejectedCount += diagnostics.ingredientParsingRejectedCount();
            ingredientOtherwiseExcludedCount += diagnostics.ingredientOtherwiseExcludedCount();
            ingredientDtoCount += diagnostics.ingredientDtoCount();
            ingredientDomainCount += diagnostics.ingredientDomainCount();
            ingredientMergedCount += diagnostics.ingredientMergedCount();
            structuredIngredientCount += diagnostics.structuredIngredientCount();
            productCandidateIngredientHintCount += diagnostics.productCandidateIngredientHintCount();
            uniqueIngredientNameCount += diagnostics.uniqueIngredientNameCount();
            ingredientResponseTotalCount = Math.max(ingredientResponseTotalCount, diagnostics.ingredientResponseTotalCount());
            ingredientResponsePageNo = Math.max(ingredientResponsePageNo, diagnostics.ingredientResponsePageNo());
            ingredientResponseNumOfRows = Math.max(ingredientResponseNumOfRows, diagnostics.ingredientResponseNumOfRows());
            ingredientResponseActualItemCount += diagnostics.ingredientResponseActualItemCount();
            ingredientResponseHasNextPage = ingredientResponseHasNextPage || diagnostics.ingredientResponseHasNextPage();
            mfdsDetailLatencyMs += diagnostics.mfdsDetailLatencyMs();
            mfdsIngredientLatencyMs += diagnostics.mfdsIngredientLatencyMs();
            return this;
        }

        MfdsIngredientMappingDiagnostics toDiagnostics() {
            return new MfdsIngredientMappingDiagnostics(
                    List.copyOf(statuses),
                    List.copyOf(exclusionReasons),
                    productCandidateCount,
                    candidatesWithProductCode,
                    ingredientRequestCount,
                    ingredientResponseItemCount,
                    ingredientMatchingProductCodeCount,
                    ingredientMismatchingProductCodeCount,
                    ingredientMissingProductCodeCount,
                    ingredientParsingRejectedCount,
                    ingredientOtherwiseExcludedCount,
                    ingredientDtoCount,
                    ingredientDomainCount,
                    ingredientMergedCount,
                    structuredIngredientCount,
                    productCandidateIngredientHintCount,
                    uniqueIngredientNameCount,
                    ingredientResponseTotalCount,
                    ingredientResponsePageNo,
                    ingredientResponseNumOfRows,
                    ingredientResponseActualItemCount,
                    ingredientResponseHasNextPage,
                    mfdsDetailLatencyMs,
                    mfdsIngredientLatencyMs);
        }
    }

    private static class FieldStructureCounter {
        private final String fieldName;
        private String jsonType = "";
        private boolean array;
        private boolean object;
        private int nullCount;
        private int blankCount;
        private int existenceCount;

        FieldStructureCounter(String fieldName) {
            this.fieldName = fieldName == null ? "" : fieldName;
        }

        void record(JsonNode node) {
            existenceCount++;
            if (node == null || node.isNull()) {
                nullCount++;
                jsonType = joinType(jsonType, "null");
                return;
            }
            if (node.isArray()) {
                array = true;
            }
            if (node.isObject()) {
                object = true;
            }
            if (node.isTextual() && node.asText("").isBlank()) {
                blankCount++;
            }
            jsonType = joinType(jsonType, jsonType(node));
        }

        void record(MfdsResponseFieldStructure structure) {
            existenceCount += structure.existenceCount();
            nullCount += structure.nullCount();
            blankCount += structure.blankCount();
            array = array || structure.array();
            object = object || structure.object();
            jsonType = joinType(jsonType, structure.jsonType());
        }

        MfdsResponseFieldStructure toStructure() {
            return new MfdsResponseFieldStructure(fieldName, jsonType, array, object, nullCount, blankCount, existenceCount);
        }

        private String jsonType(JsonNode node) {
            if (node.isTextual()) {
                return "string";
            }
            if (node.isNumber()) {
                return "number";
            }
            if (node.isBoolean()) {
                return "boolean";
            }
            if (node.isArray()) {
                return "array";
            }
            if (node.isObject()) {
                return "object";
            }
            if (node.isNull()) {
                return "null";
            }
            return "unknown";
        }

        private String joinType(String existing, String next) {
            if (next == null || next.isBlank()) {
                return existing == null ? "" : existing;
            }
            if (existing == null || existing.isBlank()) {
                return next;
            }
            LinkedHashSet<String> values = new LinkedHashSet<>(List.of(existing.split("\\|")));
            values.add(next);
            return String.join("|", values);
        }
    }
}

@Slf4j
@Component
class MfdsEasyDrugInformationAdapter implements MfdsMedicationInformationPort {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final Duration timeout;

    MfdsEasyDrugInformationAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${recipe.agent.mfds-medication-enabled:false}") boolean enabled,
            @Value("${recipe.agent.mfds-medication-api-key:}") String apiKey,
            @Value("${recipe.agent.mfds-medication-timeout-ms:5000}") long timeoutMs) {
        this.webClient = webClientBuilder
                .baseUrl("https://apis.data.go.kr/1471000/DrbEasyDrugInfoService")
                .build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
    }

    @Override
    public MedicationInformationResult findMedicationInformation(NormalizedMedication medication) {
        if (!enabled || apiKey.isBlank()) {
            return empty(MedicationDataStatus.API_DISABLED, medication, "MFDS medication API is disabled.");
        }
        String query = queryName(medication);
        if (query.isBlank()) {
            return empty(MedicationDataStatus.NOT_FOUND, medication, "Medication query is empty.");
        }
        try {
            String body = webClient.get()
                    .uri(builder -> builder.path("/getDrbEasyDrugList")
                            .queryParam("serviceKey", apiKey)
                            .queryParam("type", "json")
                            .queryParam("itemName", query)
                            .queryParam("numOfRows", 5)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .onErrorResume(e -> {
                        log.warn("[MedicationInfo] MFDS request failed. category={}", e.getClass().getSimpleName());
                        return Mono.empty();
                    })
                    .block();
            if (body == null || body.isBlank()) {
                return empty(MedicationDataStatus.API_FAILED, medication, "MFDS response was empty.");
            }
            return parseMfds(body, medication);
        } catch (Exception e) {
            log.warn("[MedicationInfo] MFDS request failed. category={}", e.getClass().getSimpleName());
            return empty(MedicationDataStatus.API_FAILED, medication, "MFDS API failed.");
        }
    }

    MedicationInformationResult parseMfds(String body, NormalizedMedication medication) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode items = root.at("/body/items");
            if (items.isMissingNode()) {
                items = root.at("/response/body/items");
            }
            if (items.isObject() && items.has("item")) {
                items = items.get("item");
            }
            List<JsonNode> itemNodes = new ArrayList<>();
            if (items.isArray()) {
                items.forEach(itemNodes::add);
            } else if (items.isObject()) {
                itemNodes.add(items);
            }
            if (itemNodes.isEmpty()) {
                return empty(MedicationDataStatus.NOT_FOUND, medication, "MFDS medication not found.");
            }
            List<JsonNode> exact = itemNodes.stream()
                    .filter(item -> RecipeCandidate.normalize(text(item, "itemName", "ITEM_NAME", "item_name"))
                            .equals(RecipeCandidate.normalize(queryName(medication))))
                    .toList();
            if (itemNodes.size() > 1 && exact.size() != 1) {
                return empty(MedicationDataStatus.MULTIPLE_RESULTS, medication, "MFDS returned multiple medication candidates.");
            }
            JsonNode item = exact.isEmpty() ? itemNodes.get(0) : exact.get(0);
            String productName = text(item, "itemName", "ITEM_NAME", "item_name");
            List<String> ingredients = splitIngredients(text(item, "entpName", "materialName", "efcyQesitm", "ingredient"));
            String interaction = text(item, "intrcQesitm", "interactionText", "drugInteraction");
            String precautions = text(item, "atpnQesitm", "atpnWarnQesitm", "seQesitm", "precautionsText");
            String usage = text(item, "useMethodQesitm", "usageText", "dosage");
            return new MedicationInformationResult(
                    MedicationDataStatus.FOUND,
                    productName,
                    text(item, "entpName", "ENTP_NAME", "manufacturerName"),
                    ingredients,
                    interaction,
                    precautions,
                    usage,
                    text(item, "itemSeq", "ITEM_SEQ", "sourceItemSequence"),
                    LocalDateTime.now(),
                    sha256(body),
                    List.of());
        } catch (Exception e) {
            return empty(MedicationDataStatus.PARSING_FAILED, medication, "MFDS JSON parsing failed.");
        }
    }

    private MedicationInformationResult empty(MedicationDataStatus status, NormalizedMedication medication, String warning) {
        return new MedicationInformationResult(status, medication == null ? "" : medication.normalizedProductName(), "", List.of(),
                "", "", "", medication == null ? "" : medication.mfdsItemSequence(), LocalDateTime.now(), "", List.of(warning));
    }

    private String queryName(NormalizedMedication medication) {
        if (medication == null) {
            return "";
        }
        if (medication.normalizedProductName() != null && !medication.normalizedProductName().isBlank()) {
            return medication.normalizedProductName();
        }
        return medication.originalName() == null ? "" : medication.originalName();
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText("").trim();
            }
        }
        return "";
    }

    private List<String> splitIngredients(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("[,;/+]")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}

@Slf4j
@Component
class OpenFdaDrugLabelAdapter implements OpenFdaDrugLabelPort {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final Duration timeout;

    @Autowired
    OpenFdaDrugLabelAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${recipe.agent.openfda-medication-enabled:false}") boolean enabled,
            @Value("${recipe.agent.openfda-api-key:}") String apiKey,
            @Value("${recipe.agent.openfda-timeout-ms:5000}") long timeoutMs) {
        this(webClientBuilder, objectMapper, enabled, apiKey, timeoutMs, "https://api.fda.gov/drug/label.json");
    }

    OpenFdaDrugLabelAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            boolean enabled,
            String apiKey,
            long timeoutMs,
            String baseUrl) {
        this.webClient = webClientBuilder
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                        .build())
                .baseUrl(baseUrl)
                .build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
    }

    @Override
    public DrugLabelEvidenceResult findLabelEvidence(NormalizedMedication medication) {
        if (!enabled) {
            return new DrugLabelEvidenceResult(MedicationDataStatus.API_DISABLED, List.of(), List.of("openFDA medication label API is disabled."));
        }
        List<OpenFdaSearchPlan> plans = searchPlans(medication);
        if (plans.isEmpty()) {
            return new DrugLabelEvidenceResult(
                    MedicationDataStatus.NOT_FOUND,
                    List.of(),
                    List.of("openFDA search query is empty or contains no English/RxCUI normalized term."),
                    OpenFdaLabelMatchStatus.NO_MATCH,
                    List.of());
        }
        List<OpenFdaSearchAttempt> attempts = new ArrayList<>();
        try {
            for (OpenFdaSearchPlan plan : plans) {
                OpenFdaHttpResult http = request(plan.query());
                if (http == null) {
                    attempts.add(attempt(plan, MedicationDataStatus.API_FAILED, 0, false, "API_FAILED"));
                    continue;
                }
                if (http.statusCode() == 404) {
                    attempts.add(attempt(plan, MedicationDataStatus.NOT_FOUND, 0, false, "NOT_FOUND"));
                    continue;
                }
                if (http.statusCode() == 400) {
                    attempts.add(attempt(plan, MedicationDataStatus.API_FAILED, 0, false, "QUERY_REJECTED"));
                    return new DrugLabelEvidenceResult(
                            MedicationDataStatus.API_FAILED,
                            List.of(),
                            List.of("openFDA rejected the search query."),
                            OpenFdaLabelMatchStatus.QUERY_REJECTED,
                            attempts);
                }
                if (http.statusCode() == 429) {
                    attempts.add(attempt(plan, MedicationDataStatus.API_FAILED, 0, false, "RATE_LIMITED"));
                    return new DrugLabelEvidenceResult(
                            MedicationDataStatus.API_FAILED,
                            List.of(),
                            List.of("openFDA rate limit was reached."),
                            OpenFdaLabelMatchStatus.RATE_LIMITED,
                            attempts);
                }
                if (http.statusCode() < 200 || http.statusCode() >= 300) {
                    attempts.add(attempt(plan, MedicationDataStatus.API_FAILED, 0, false, "API_FAILED"));
                    continue;
                }
                DrugLabelEvidenceResult parsed = parseLabelResponse(http.body(), plan.matchStatus());
                if (parsed.status() == MedicationDataStatus.PARSING_FAILED) {
                    attempts.add(attempt(plan, MedicationDataStatus.PARSING_FAILED, 0, false, "PARSING_FAILED"));
                    return new DrugLabelEvidenceResult(
                            MedicationDataStatus.PARSING_FAILED,
                            List.of(),
                            parsed.warnings(),
                            OpenFdaLabelMatchStatus.PARSING_FAILED,
                            attempts);
                }
                List<DrugLabelEvidence> verified = verifiedLabels(parsed.labels(), plan);
                attempts.add(attempt(plan, parsed.status(), parsed.labels().size(), !verified.isEmpty(), verified.isEmpty() ? "NO_VERIFIED_LABEL" : "SUCCESS"));
                if (verified.size() == 1) {
                    return new DrugLabelEvidenceResult(MedicationDataStatus.FOUND, verified, List.of(), plan.matchStatus(), attempts);
                }
                if (verified.size() > 1) {
                    return new DrugLabelEvidenceResult(
                            MedicationDataStatus.INCOMPLETE,
                            verified,
                            List.of("openFDA returned multiple verified labels; manual review is required."),
                            OpenFdaLabelMatchStatus.MULTIPLE_LABEL_MATCHES,
                            attempts);
                }
            }
            return new DrugLabelEvidenceResult(
                    MedicationDataStatus.NOT_FOUND,
                    List.of(),
                    List.of("openFDA label not found."),
                    OpenFdaLabelMatchStatus.NO_MATCH,
                    attempts);
        } catch (Exception e) {
            log.warn("[MedicationInfo] openFDA request failed. category={}", e.getClass().getSimpleName());
            return new DrugLabelEvidenceResult(
                    MedicationDataStatus.API_FAILED,
                    List.of(),
                    List.of("openFDA API failed."),
                    OpenFdaLabelMatchStatus.API_FAILED,
                    attempts);
        }
    }

    DrugLabelEvidenceResult parseLabelResponse(String body) {
        return parseLabelResponse(body, OpenFdaLabelMatchStatus.EXACT_SUBSTANCE_MATCH);
    }

    DrugLabelEvidenceResult parseLabelResponse(String body, OpenFdaLabelMatchStatus matchStatus) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.has("results")) {
                return new DrugLabelEvidenceResult(MedicationDataStatus.NOT_FOUND, List.of(), List.of("openFDA label not found."), OpenFdaLabelMatchStatus.NO_MATCH, List.of());
            }
            List<DrugLabelEvidence> labels = new ArrayList<>();
            for (JsonNode result : root.get("results")) {
                labels.add(toLabel(result));
            }
            return new DrugLabelEvidenceResult(labels.isEmpty() ? MedicationDataStatus.NOT_FOUND : MedicationDataStatus.FOUND, labels, List.of(), labels.isEmpty() ? OpenFdaLabelMatchStatus.NO_MATCH : matchStatus, List.of());
        } catch (Exception e) {
            return new DrugLabelEvidenceResult(MedicationDataStatus.PARSING_FAILED, List.of(), List.of("openFDA JSON parsing failed."), OpenFdaLabelMatchStatus.PARSING_FAILED, List.of());
        }
    }

    DrugLabelEvidence toLabel(JsonNode result) {
        JsonNode openfda = result.get("openfda");
        String setId = text(result, "set_id");
        String sourceUrl = setId.isBlank() ? "" : "https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=" + setId;
        List<MedicationLabelSection> sections = new ArrayList<>();
        addSection(result, sections, MedicationLabelSectionType.DRUG_INTERACTIONS, "drug_interactions");
        addSection(result, sections, MedicationLabelSectionType.FOOD_SAFETY_WARNING, "food_safety_warning");
        addSection(result, sections, MedicationLabelSectionType.INFORMATION_FOR_PATIENTS, "information_for_patients");
        addSection(result, sections, MedicationLabelSectionType.DOSAGE_AND_ADMINISTRATION, "dosage_and_administration");
        addSection(result, sections, MedicationLabelSectionType.WARNINGS, "warnings");
        addSection(result, sections, MedicationLabelSectionType.PRECAUTIONS, "precautions");
        return new DrugLabelEvidence(
                text(result, "id"),
                setId,
                first(openfda, "brand_name"),
                first(openfda, "generic_name"),
                list(openfda, "substance_name"),
                list(openfda, "rxcui"),
                list(openfda, "dosage_form"),
                list(openfda, "route"),
                text(result, "effective_time"),
                sections,
                sourceUrl,
                LocalDateTime.now());
    }

    private void addSection(JsonNode result, List<MedicationLabelSection> sections, MedicationLabelSectionType type, String field) {
        for (String value : list(result, field)) {
            if (!value.isBlank()) {
                sections.add(new MedicationLabelSection(type, value));
            }
        }
    }

    private OpenFdaHttpResult request(String search) {
        return webClient.get()
                .uri(builder -> {
                    var uri = builder
                            .queryParam("search", search)
                            .queryParam("limit", 5);
                    if (!apiKey.isBlank()) {
                        uri.queryParam("api_key", apiKey);
                    }
                    return uri.build();
                })
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new OpenFdaHttpResult(response.statusCode().value(),
                                response.headers().contentType().map(Object::toString).orElse(""),
                                body)))
                .timeout(timeout)
                .onErrorResume(e -> {
                    log.warn("[MedicationInfo] openFDA request failed. category={}", e.getClass().getSimpleName());
                    return Mono.empty();
                })
                .block();
    }

    private OpenFdaSearchAttempt attempt(OpenFdaSearchPlan plan, MedicationDataStatus status, int labelCount, boolean verified, String failureCategory) {
        return new OpenFdaSearchAttempt(plan.stage(), plan.field(), plan.queryKind(), status, labelCount, verified, failureCategory);
    }

    private List<OpenFdaSearchPlan> searchPlans(NormalizedMedication medication) {
        if (medication == null) {
            return List.of();
        }
        List<OpenFdaSearchPlan> plans = new ArrayList<>();
        boolean hasRxcui = medication.rxcui() != null && !medication.rxcui().isBlank();
        if (hasRxcui) {
            plans.add(plan(OpenFdaSearchStage.RXCUI_EXACT, "openfda.rxcui.exact", medication.rxcui(), "exact", OpenFdaLabelMatchStatus.EXACT_RXCUI_MATCH, List.of()));
        }
        List<String> ingredientTerms = medication.normalizedIngredientName() == null || medication.normalizedIngredientName().isBlank()
                ? List.of()
                : englishTerms(medication.normalizedIngredientName(), medication.matchedAliases());
        for (String term : ingredientTerms) {
            plans.add(plan(OpenFdaSearchStage.GENERIC_EXACT, "openfda.generic_name.exact", term, "exact", OpenFdaLabelMatchStatus.EXACT_GENERIC_MATCH, ingredientTerms));
            plans.add(plan(OpenFdaSearchStage.SUBSTANCE_EXACT, "openfda.substance_name.exact", term, "exact", OpenFdaLabelMatchStatus.EXACT_SUBSTANCE_MATCH, ingredientTerms));
        }
        if (hasRxcui || !ingredientTerms.isEmpty()) {
            for (String term : englishProductTerms(medication)) {
                plans.add(plan(OpenFdaSearchStage.BRAND_EXACT, "openfda.brand_name.exact", term, "exact", OpenFdaLabelMatchStatus.EXACT_BRAND_MATCH, List.of()));
            }
        }
        for (String term : ingredientTerms) {
            plans.add(plan(OpenFdaSearchStage.GENERIC_TOKEN, "openfda.generic_name", term, "token", OpenFdaLabelMatchStatus.TOKEN_MATCH_REQUIRES_REVIEW, ingredientTerms));
            plans.add(plan(OpenFdaSearchStage.SUBSTANCE_TOKEN, "openfda.substance_name", term, "token", OpenFdaLabelMatchStatus.TOKEN_MATCH_REQUIRES_REVIEW, ingredientTerms));
        }
        return distinctPlans(plans);
    }

    private OpenFdaSearchPlan plan(OpenFdaSearchStage stage, String field, String value, String queryKind, OpenFdaLabelMatchStatus status, List<String> requiredIngredientTerms) {
        String query = queryKind.equals("exact") ? field + ":\"" + value + "\"" : field + ":" + value;
        return new OpenFdaSearchPlan(stage, field, value, queryKind, query, status, requiredIngredientTerms);
    }

    private List<OpenFdaSearchPlan> distinctPlans(List<OpenFdaSearchPlan> plans) {
        Set<String> seen = new LinkedHashSet<>();
        List<OpenFdaSearchPlan> distinct = new ArrayList<>();
        for (OpenFdaSearchPlan plan : plans) {
            if (seen.add(plan.field() + "|" + RecipeCandidate.normalize(plan.value()) + "|" + plan.queryKind())) {
                distinct.add(plan);
            }
        }
        return distinct;
    }

    private List<String> englishTerms(String ingredientName, List<String> aliases) {
        List<String> terms = new ArrayList<>();
        terms.addAll(splitTerms(ingredientName));
        if (aliases != null) {
            for (String alias : aliases) {
                terms.addAll(splitTerms(alias));
            }
        }
        return distinctEnglishTerms(terms);
    }

    private List<String> englishProductTerms(NormalizedMedication medication) {
        List<String> terms = new ArrayList<>();
        terms.add(medication.normalizedProductName());
        terms.add(medication.originalName());
        return distinctEnglishTerms(terms);
    }

    private List<String> splitTerms(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("[,;/+]")).stream()
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .toList();
    }

    private List<String> distinctEnglishTerms(List<String> terms) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String term : terms) {
            String clean = cleanSearchTerm(term);
            if (isEnglishSearchTerm(clean) && seen.add(clean.toLowerCase(Locale.ROOT))) {
                result.add(clean);
            }
        }
        return result;
    }

    private String cleanSearchTerm(String term) {
        if (term == null) {
            return "";
        }
        return term.replaceAll("\\([^)]*\\)", " ")
                .replaceAll("[^A-Za-z0-9 .'-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isEnglishSearchTerm(String term) {
        return term != null
                && term.matches(".*[A-Za-z].*")
                && !term.matches(".*[가-힣].*")
                && term.length() >= 2;
    }

    private List<DrugLabelEvidence> verifiedLabels(List<DrugLabelEvidence> labels, OpenFdaSearchPlan plan) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        return labels.stream()
                .filter(label -> verified(label, plan))
                .toList();
    }

    private boolean verified(DrugLabelEvidence label, OpenFdaSearchPlan plan) {
        String expected = RecipeCandidate.normalize(plan.value());
        String generic = RecipeCandidate.normalize(label.genericName());
        String brand = RecipeCandidate.normalize(label.brandName());
        if (!plan.requiredIngredientTerms().isEmpty() && !allRequiredIngredientsPresent(label, plan.requiredIngredientTerms())) {
            return false;
        }
        return switch (plan.stage()) {
            case RXCUI_EXACT -> label.rxcuis().stream()
                    .map(RecipeCandidate::normalize)
                    .anyMatch(expected::equals);
            case GENERIC_EXACT -> !generic.isBlank() && generic.equals(expected);
            case GENERIC_TOKEN -> !generic.isBlank() && (generic.contains(expected) || expected.contains(generic));
            case SUBSTANCE_EXACT, SUBSTANCE_TOKEN -> label.substanceNames().stream()
                    .map(RecipeCandidate::normalize)
                    .anyMatch(value -> value.equals(expected) || value.contains(expected) || expected.contains(value));
            case BRAND_EXACT -> !brand.isBlank() && brand.equals(expected);
        };
    }

    private boolean allRequiredIngredientsPresent(DrugLabelEvidence label, List<String> requiredTerms) {
        String generic = RecipeCandidate.normalize(label.genericName());
        List<String> substances = label.substanceNames().stream()
                .map(RecipeCandidate::normalize)
                .toList();
        for (String required : requiredTerms) {
            String expected = RecipeCandidate.normalize(required);
            boolean inGeneric = !generic.isBlank() && generic.contains(expected);
            boolean inSubstance = substances.stream().anyMatch(value -> value.equals(expected) || value.contains(expected) || expected.contains(value));
            if (!inGeneric && !inSubstance) {
                return false;
            }
        }
        return true;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String first(JsonNode node, String field) {
        return list(node, field).stream().findFirst().orElse("");
    }

    private List<String> list(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> values.add(item.asText("").trim()));
        } else {
            values.add(value.asText("").trim());
        }
        return values.stream().filter(item -> !item.isBlank()).toList();
    }

    private record OpenFdaHttpResult(int statusCode, String contentType, String body) {
    }

    private record OpenFdaSearchPlan(
            OpenFdaSearchStage stage,
            String field,
            String value,
            String queryKind,
            String query,
            OpenFdaLabelMatchStatus matchStatus,
            List<String> requiredIngredientTerms
    ) {
        private OpenFdaSearchPlan {
            requiredIngredientTerms = requiredIngredientTerms == null ? List.of() : List.copyOf(requiredIngredientTerms);
        }
    }
}

@Slf4j
@Component
class RxNormNormalizationAdapter implements RxNormMedicationNormalizationPort {

    private final WebClient webClient;
    private final boolean enabled;
    private final Duration timeout;

    RxNormNormalizationAdapter(
            WebClient.Builder webClientBuilder,
            @Value("${recipe.agent.rxnorm-normalization-enabled:false}") boolean enabled,
            @Value("${recipe.agent.rxnorm-timeout-ms:3000}") long timeoutMs) {
        this.webClient = webClientBuilder
                .baseUrl("https://rxnav.nlm.nih.gov/REST")
                .build();
        this.enabled = enabled;
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
    }

    @Override
    public RxNormNormalizationResult normalize(MedicationInput input) {
        if (!enabled) {
            return new RxNormNormalizationResult(MedicationNormalizationStatus.NOT_FOUND, "", "", List.of(), 0.0, null, List.of("RxNorm normalization is disabled."));
        }
        if (input == null || input.originalName() == null || input.originalName().isBlank()) {
            return new RxNormNormalizationResult(MedicationNormalizationStatus.NOT_FOUND, "", "", List.of(), 0.0, null, List.of("RxNorm query is empty."));
        }
        try {
            JsonNode root = webClient.get()
                    .uri(builder -> builder.path("/rxcui.json")
                            .queryParam("name", input.originalName())
                            .queryParam("search", 1)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .onErrorResume(e -> {
                        log.warn("[MedicationInfo] RxNorm request failed. category={}", e.getClass().getSimpleName());
                        return Mono.empty();
                    })
                    .block();
            JsonNode ids = root == null ? null : root.at("/idGroup/rxnormId");
            if (ids == null || !ids.isArray() || ids.isEmpty()) {
                return new RxNormNormalizationResult(MedicationNormalizationStatus.NOT_FOUND, "", "", List.of(), 0.0, null, List.of());
            }
            if (ids.size() > 1) {
                return new RxNormNormalizationResult(MedicationNormalizationStatus.MULTIPLE_MATCHES, "", "", List.of(), 0.0, null, List.of("RxNorm returned multiple candidates."));
            }
            String rxcui = ids.get(0).asText("");
            MedicationEvidenceSource source = new MedicationEvidenceSource(
                    MedicationEvidenceSourceType.RXNORM_NORMALIZATION,
                    rxcui,
                    input.originalName(),
                    "https://rxnav.nlm.nih.gov/REST/rxcui/" + rxcui,
                    null,
                    LocalDateTime.now());
            return new RxNormNormalizationResult(
                    MedicationNormalizationStatus.NORMALIZED_MATCH,
                    rxcui,
                    input.originalName(),
                    List.of(input.originalName()),
                    0.75,
                    source,
                    List.of("RxNorm was used only for name normalization, not interaction judgment."));
        } catch (Exception e) {
            log.warn("[MedicationInfo] RxNorm request failed. category={}", e.getClass().getSimpleName());
            return new RxNormNormalizationResult(MedicationNormalizationStatus.API_FAILED, "", "", List.of(), 0.0, null, List.of("RxNorm API failed."));
        }
    }
}
