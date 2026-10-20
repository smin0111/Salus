---
name: salus-llm-recipe-pipeline
description: Use when changing, debugging or evaluating Salus chat-to-recipe generation - RecipeGenerationCoordinator, RecipePromptFactory, Ollama clients, parsers, RecipeDraftValidator, RecipeValidator, repair, audit, eval harness (레시피 생성, 프롬프트, 검증기, LLM 평가). For allergen matching rules use salus-allergen-safety.
---

# Salus LLM recipe pipeline

## Read first (only the part you need)
- `docs/ai/PROJECT_CONTEXT.md` section "LLM recipe flow" (call order, settings, WIP deltas).
- `docs/ai/SAFETY_RULES.md` section 4 before touching validators, repair, sanitizer or audit.
- (WIP) `docs/llm-eval-harness.md` for evaluation work.

## Entry points
- `service/ChatService.processChat` -> `service/RecipeGenerationCoordinator` (`buildCreationRequest`, `buildStructuredRecipeResponse`, `repairOrFail`).
- LLM I/O: `OllamaRecipeGenerationClient`, `RecipePromptFactory`, `OllamaLlmService` (chat path and `thinkingSettingFor`); settings `ollama.*` in `application.properties`.
- Gates: `RecipeDraftValidator`, `RecipeValidator`, `RecipeResponseSanitizer`. Audit: `GeneratedRecipeLifecycleService`. Session: `RecipeWorkSessionService`.
- Follow-ups: `ChatFollowUpService`, `RecipeReplyParser`.

## Working rules
- Locate call sites with `rg -n "<symbol>" backend/src/main` and read methods by line range; these classes are 300-650 lines.
- Keep a single qwen3 thinking decision point: `OllamaLlmService.thinkingSettingFor`.
- Prompt or sampling changes: keep chat and recipe paths consistent; no per-model prompt branches unless explicitly requested.
- Validator changes are safety changes: never loosen a rule to make a case pass; update tests in the same change.
- Unit tests must not call a live Ollama; reuse existing mocks and fixtures.

## Verify (narrowest first)
- Pick relevant: `cd backend && mvn -Dtest='RecipeDraftValidatorTest,RecipeValidatorStructuredTest,RecipePromptFactoryTest,OllamaRecipeGenerationClientTest,OllamaThinkingSettingTest' test`
- If gates or flow order changed: `mvn -Dtest='ChatServiceSafetyTest,ChatSafetyContextServiceTest' test`, then `mvn -q clean test`.
- (WIP) Deterministic eval regression: `./eval.sh --mode replay`. Live eval only when asked.

## Do not
- Return or persist a draft that failed validation.
- Turn on Recipe Agent or external source flags by default.
- Load eval `results/`, fixtures or recorded responses wholesale; search them.
