package com.salus.healthytable.eval;

import com.salus.healthytable.service.OllamaLlmService;

import java.util.List;
import java.util.Locale;

/**
 * Judge 모델 교차평가. 생성 모델과 역할을 완전히 분리한다.
 *
 * <p>Judge 판정은 참고 신호일 뿐이며 프로덕션 검증기 판정을 덮어쓰지 않는다.
 * 결과는 {@code judgeVerdict}·{@code judgeAgreement}에만 기록되고, 통과율·게이트·모델별
 * 지표 집계에는 일절 반영되지 않는다.
 *
 * <p>한계: 판정에 프로덕션 채팅 경로({@link OllamaLlmService})를 그대로 쓰기 때문에 Salus
 * 어시스턴트 system instruction이 함께 들어간다. 전용 judge 프롬프트 채널이 아니라는 점을
 * 감안하고 읽어야 한다.
 */
public class EvalJudge {

    private final OllamaLlmService judgeChatService;
    private final String judgeModel;

    public EvalJudge(OllamaLlmService judgeChatService, String judgeModel) {
        this.judgeChatService = judgeChatService;
        this.judgeModel = judgeModel;
    }

    public String judgeModel() {
        return judgeModel;
    }

    // Judge 모델에게 케이스와 결과를 보여 주고 받은 판정을 결과에 참고용으로 덧붙입니다(프로덕션 판정은 그대로 유지).
    public EvalResult attachVerdict(EvalCase evalCase, EvalResult result) {
        String prompt = buildPrompt(evalCase, result);
        String reply;
        try {
            reply = judgeChatService.getChatResponse(prompt, List.of()).block();
        } catch (RuntimeException error) {
            return result.withJudge(judgeModel, null, "judge 호출 실패: " + error.getClass().getSimpleName());
        }
        if (reply == null || reply.isBlank()) {
            return result.withJudge(judgeModel, null, "judge 응답 없음");
        }
        String normalized = reply.toUpperCase(Locale.ROOT);
        String verdict = normalized.contains("FAIL") && !normalized.contains("PASS")
                ? EvalResult.VERDICT_FAIL
                : normalized.contains("PASS") && !normalized.contains("FAIL")
                ? EvalResult.VERDICT_PASS
                : null;
        return result.withJudge(judgeModel, verdict, reply.strip());
    }

    // Judge 모델에게 보낼 평가 프롬프트를 만듭니다.
    private String buildPrompt(EvalCase evalCase, EvalResult result) {
        return """
                아래는 요리 레시피 생성 결과에 대한 평가 요청입니다.
                조건을 모두 만족하면 PASS, 하나라도 위반하면 FAIL만 첫 줄에 쓰고,
                둘째 줄에 한 문장으로 이유를 쓰세요. 다른 말은 쓰지 마세요.

                [사용자 입력]
                %s

                [만족해야 하는 조건]
                %s

                [모델 출력 원문]
                %s
                """.formatted(
                evalCase.inputText(),
                String.join("\n", evalCase.expectedConditions().stream().map(c -> "- " + c).toList()),
                result.rawOutput() == null ? "" : result.rawOutput());
    }
}
