---
name: salus-allergen-safety
description: Use for any Salus allergen or health-safety work - AllergenMatcher, AllergenDictionary, ko-allergens.yaml, allergen declaration parser, ChatSafetyContextService, allergy blocking, personalization policies, or any logic that decides safe or unsafe (알레르기, 알레르겐, 원재료, 함유 표시, 안전 판정).
---

# Salus allergen and health safety

## Read first
- `docs/ai/SAFETY_RULES.md` sections 1-3, and section 6 before finishing.
- `docs/ai/PROJECT_CONTEXT.md` section "Allergen flow".
- (WIP) `docs/allergen-declaration-parser.md` when touching declaration parsing.

## Entry points
- `service/allergen/AllergenDictionary` (loads `allergens/ko-allergens.yaml`), `service/allergen/AllergenMatcher` (`findConflicts`, `conflicts`, `matchesLiterally`).
- `service/ChatSafetyContextService` (`build`, `findAllergyConflicts`, `buildAllergyConflictReply`, `buildAllergyBlockedReply`, `buildRecipeSafetyNotes`).
- `service/recipeagent/RecipePersonalizationPolicies` (Recipe Agent allergy handling).
- (WIP) `service/allergen/AllergenDeclarationParser`, `AllergenRegistry`, `AllergenEvidence`, `DeclarationState`.

## Working rules
- Unknown, empty, unparsed or failed lookups stay unknown; never map them to safe or "none".
- One matching path; no new matcher and no allergen lists copied into Java.
- Changing token or derived-term behavior or YAML data needs tests for a true match and a false-positive guard (e.g. 밀 vs 밀크).
- Keep observation (label evidence) separate from decision (user conflict judgement).
- Never edit reference fixtures or expected values just to improve metrics; record each correction with its evidence.

## Verify
- `cd backend && mvn -Dtest='AllergenMatcherTest,AllergenRemovabilityTest,AllergenGapTest,ChatSafetyContextServiceTest,ChatServiceSafetyTest' test`
- (WIP) `mvn -Dtest='AllergenDeclarationParserTest,AllergenRegistryTest,DeclarationReferenceTest,DeclarationReferenceValidationTest' test`
- Then `mvn -q clean test`: allergen components are shared by chat, structured generation and Recipe Agent.
- Report behavior before -> after, tests run, and what remains unverified.
