package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.FridgeItem;
import com.mychefai.healthytable.domain.HealthCheckup;
import com.mychefai.healthytable.domain.HealthProfile;
import com.mychefai.healthytable.dto.HealthCheckupAnalysisDTO;
import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.repository.FridgeItemRepository;
import com.mychefai.healthytable.repository.HealthCheckupRepository;
import com.mychefai.healthytable.repository.HealthProfileRepository;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.service.GeminiService;
import com.mychefai.healthytable.service.HealthCheckupAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final GeminiService geminiService;
    private final JwtTokenProvider jwtTokenProvider;
    private final FridgeItemRepository fridgeItemRepository;
    private final HealthProfileRepository healthProfileRepository;
    private final HealthCheckupRepository healthCheckupRepository;
    private final HealthCheckupAnalysisService healthCheckupAnalysisService;

    @PostMapping("/message")
    public Mono<ChatDto.Response> chat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ChatDto.Request request) {

        String enhancedMessage = request.getMessage();
        StringBuilder systemContext = new StringBuilder();

        // Get user context if authenticated
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                if (jwtTokenProvider.validateToken(token)) {
                    String userId = jwtTokenProvider.getUserId(token);
                    Long userIdLong = Long.parseLong(userId);

                    // 1. ALWAYS include health profile for safety
                    healthProfileRepository.findByUserId(userIdLong).ifPresent(profile -> {
                        systemContext.append("\n\n=== 중요: 사용자 건강 정보 (반드시 준수) ===\n");

                        if (profile.getAllergies() != null && !profile.getAllergies().isEmpty()) {
                            systemContext.append("알레르기: ").append(String.join(", ", profile.getAllergies()))
                                    .append("\n");
                            systemContext.append("이 재료들은 절대 사용하지 마세요.\n");
                        }

                        if (profile.getChronicConditions() != null && !profile.getChronicConditions().isEmpty()) {
                            systemContext.append("만성질환: ").append(String.join(", ", profile.getChronicConditions()))
                                    .append("\n");
                        }

                        if (profile.getDietaryRestrictions() != null && !profile.getDietaryRestrictions().isEmpty()) {
                            systemContext.append("식단 제한: ")
                                    .append(String.join(", ", profile.getDietaryRestrictions())).append("\n");
                        }

                        if (profile.getMedications() != null && !profile.getMedications().isEmpty()) {
                            systemContext.append("복용 약물: ").append(String.join(", ", profile.getMedications()))
                                    .append("\n");
                            systemContext.append("약물과 상호작용할 수 있는 음식을 피해주세요.\n");
                        }

                        if (profile.getGoals() != null && !profile.getGoals().isEmpty()) {
                            systemContext.append("건강 목표: ").append(String.join(", ", profile.getGoals()))
                                    .append("\n");
                        }

                        systemContext.append("=====================================\n");
                    });

                    // 2. Latest health checkup context
                    healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userIdLong).ifPresent(checkup -> {
                        HealthCheckupAnalysisDTO analysis = healthCheckupAnalysisService.analyze(checkup);
                        systemContext.append("\n=== 최신 건강검진 기반 식단 정책 ===\n");
                        systemContext.append("검진일: ").append(checkup.getCheckupDate()).append("\n");
                        appendMetric(systemContext, "BMI", checkup.getBmi());
                        appendMetric(systemContext, "혈압", formatBloodPressure(checkup));
                        appendMetric(systemContext, "공복혈당", checkup.getFastingGlucose());
                        appendMetric(systemContext, "LDL", checkup.getLdl());
                        appendMetric(systemContext, "중성지방", checkup.getTriglyceride());
                        appendMetric(systemContext, "AST/ALT", formatLiverNumbers(checkup));
                        systemContext.append("분석 요약: ").append(analysis.getSummary()).append("\n");
                        if (!analysis.getRisks().isEmpty()) {
                            systemContext.append("주의 항목: ").append(String.join(", ", analysis.getRisks())).append("\n");
                        }
                        if (!analysis.getRecommendationPolicies().isEmpty()) {
                            systemContext.append("추천 정책: ")
                                    .append(String.join(" / ", analysis.getRecommendationPolicies())).append("\n");
                        }
                        systemContext.append("주의: 의료 진단처럼 단정하지 말고 식단 관리 참고 정보로 설명하세요.\n");
                        systemContext.append("====================================\n");
                    });

                    // 3. Fridge context (with strict enforcement)
                    List<FridgeItem> fridgeItems = fridgeItemRepository.findByUserIdOrderByExpiryDate(userIdLong);

                    if (request.isUseFridge()) {
                        // FRIDGE ON: Use fridge ingredients
                        systemContext.append("\n=== 냉장고 모드 ON ===\n");
                        if (!fridgeItems.isEmpty()) {
                            systemContext.append("현재 냉장고에 있는 재료:\n");
                            String fridgeInfo = fridgeItems.stream()
                                    .map(item -> String.format("- %s (%s, 유통기한: %s)",
                                            item.getName(), item.getQuantity(), item.getExpiryDate()))
                                    .collect(Collectors.joining("\n"));
                            systemContext.append(fridgeInfo).append("\n");
                            systemContext.append("\n지시사항: 위 재료들을 최대한 활용하여 레시피를 추천해주세요.");
                            systemContext.append(" 유통기한이 임박한 재료를 우선적으로 사용하세요.\n");
                        } else {
                            systemContext.append("냉장고에 등록된 재료가 없습니다.\n");
                            systemContext.append("일반적인 레시피를 추천해주세요.\n");
                        }
                        systemContext.append("========================\n");
                    } else {
                        // FRIDGE OFF: Do NOT use fridge ingredients
                        systemContext.append("\n=== 냉장고 모드 OFF ===\n");
                        systemContext.append("중요 지시사항: 사용자가 냉장고 재료를 사용하지 않기로 선택했습니다.\n");
                        systemContext.append("냉장고에 있는 재료를 언급하거나 사용하지 마세요.\n");
                        systemContext.append("다양한 재료로 자유롭게 레시피를 추천해주세요.\n");
                        systemContext.append("======================\n");
                    }
                }
            } catch (Exception e) {
                System.err.println("컨텍스트 추가 중 오류 (무시하고 계속): " + e.getMessage());
            }
        }

        // Combine user message with system context
        if (systemContext.length() > 0) {
            enhancedMessage = request.getMessage() + systemContext.toString();
            System.out.println(">>> AI에게 전달되는 컨텍스트 포함 메시지:");
            System.out.println(enhancedMessage);
        } else {
            System.out.println(">>> AI에게 전달되는 메시지 (비로그인):");
            System.out.println(enhancedMessage);
        }

        return geminiService.getChatResponse(enhancedMessage, request.getHistory())
                .map(ChatDto.Response::new);
    }

    @PostMapping("/stt") // STT Endpoint
    public Mono<Map<String, String>> speechToText(@RequestParam("audio") MultipartFile audioFile) {
        // Placeholder for VoiceService integration
        // In a real implementation, we would send this file to OpenAI Whisper API
        return Mono.just(Map.of("text", "음성 인식 기능은 아직 서버 키 설정이 필요합니다. (Mock Response)"));
    }

    private void appendMetric(StringBuilder builder, String label, Object value) {
        if (value != null) {
            builder.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    private String formatBloodPressure(HealthCheckup checkup) {
        if (checkup.getSystolicBp() == null && checkup.getDiastolicBp() == null) {
            return null;
        }
        return (checkup.getSystolicBp() != null ? checkup.getSystolicBp() : "?")
                + "/"
                + (checkup.getDiastolicBp() != null ? checkup.getDiastolicBp() : "?");
    }

    private String formatLiverNumbers(HealthCheckup checkup) {
        if (checkup.getAst() == null && checkup.getAlt() == null) {
            return null;
        }
        return (checkup.getAst() != null ? checkup.getAst() : "?")
                + "/"
                + (checkup.getAlt() != null ? checkup.getAlt() : "?");
    }
}
