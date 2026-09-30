# Safety rules: allergens, health context, LLM output

Applies to any change in `service/allergen/`, `resources/allergens/`, `ChatSafetyContextService`, `ChatService` safety branches, `RecipeGenerationCoordinator`, `RecipeDraftValidator`, `RecipeValidator`, `RecipeResponseSanitizer`, `service/recipeagent/*Personalization*|*Medication*`, the eval harness, and their tests.
Code anchors verified at `7aaeefc`; `(WIP)` items come from uncommitted work and `docs/*.md` on 2026-09-17.

## 1. Principles
- Unknown != safe. Absence of evidence != evidence of absence.
- Never infer allergen safety without evidence. An empty result, a parse failure, a timeout or a missing profile is "unknown", not "no allergen".
- Fail closed: if an authenticated user's health context cannot be loaded, do not return personalized recipes (`ChatService.processChat`, `SafetyContext.healthContextAvailable`).
- Registered allergies outrank ad-hoc statements: phrases like "빼고" / "제외" in a message never switch off conflict detection (`ChatSafetyContextService.findAllergyConflicts`).
- LLM output is untrusted: parse -> validate -> bounded repair -> fail. Never show an unvalidated draft.
- Do not replace uncertain values with confident guesses (e.g. (WIP) calories without a base serving count are not assumed to be per-serving; menus absent from the approved catalog are not generated without reliable evidence).
- Do not silently weaken validators, lower thresholds, widen allow-lists or skip checks to make tests or evals pass. If a rule is wrong, change it explicitly with tests and say so.
- Safety-sensitive behavior changes require explicit verification: targeted tests plus a note in the report describing the behavior before and after.

## 2. Allergen matching invariants (verified)
- Shared conflict matcher for chat and Recipe Agent AllergyPolicy: `AllergenMatcher` + `AllergenDictionary`; do not add a parallel matcher. This is not yet a global single authority: RecommendationService uses its own substring filter, and Agent fridge/final validation has separate literal checks. Preserve these existing paths until an explicit migration. Actual wiring and ownership: [6a boundary](../allergen-architecture-boundary.md).
- Allergen data lives in `backend/src/main/resources/allergens/ko-allergens.yaml`; do not copy allergen lists into Java.
- Token rule (`AllergenMatcher.matches`): a one-character term (e.g. "밀", "게") must equal a whole token; longer terms may match inside a token. This prevents "밀" matching "밀크". Derived terms (밀가루, 게살) come from the dictionary, not from substring luck.
- `matchesLiterally` distinguishes "the allergen word itself appears" from "only a derived ingredient matched": removing the word "우유" does not remove 버터/치즈 (`RecipePersonalizationPolicies`).
- Guard tests: `AllergenMatcherTest`, `AllergenRemovabilityTest`, `AllergenGapTest`, `ChatSafetyContextServiceTest`, `ChatServiceSafetyTest`.

## 3. (WIP) Allergen declaration parser invariants
Source: `docs/allergen-declaration-parser.md` and `service/allergen/AllergenDeclarationParser` et al. Re-check both before editing.
- The parser records label evidence only; it never decides whether a user may eat something.
- States: `DECLARED_PRESENT`, `DECLARED_NONE`, `DECLARATION_NOT_FOUND`, `UNREADABLE`. Only the caller sets NOT_FOUND / UNREADABLE.
- Empty evidence never means `DECLARED_NONE`. Unregistered items stay in `unparsedTokens`; a parenthesized token with any unsupported part stays unparsed as a whole.
- Invalid input (empty, unbalanced parentheses, unsupported sentence form) throws `IllegalArgumentException`; never convert it to UNREADABLE or NOT_FOUND.
- No taxonomy inference: `SHELLFISH` does not add `OYSTER`; only explicitly written children count.
- The parser does not call `AllergenMatcher` (substring/derived rules are not certain evidence).
- Cross-contact uses `AllergenCrossContactParser` and the same registry, evidence and `AllergenLabelTokens` utility. Its CERTAIN means explicit manufacturer wording about possible contact, never certain ingredient presence.
- Cross-contact states are PRESENT / NOT_FOUND / UNREADABLE; absent or unverified wording never means no risk. Both parsers use `findDirectName`: 알류/난류 -> EGG_GROUP, 계란/달걀 -> EGG, 메추리알 -> QUAIL_EGG. Registry parents are metadata only, never inferred explicitChildren or safety decisions.
- Registry lexical lookup uses complete exact tokens, retains Alias/Relation/Confidence and never retries shorter substrings of unknown names. LEXICAL_HINT (우유향/콩) is POSSIBLE; DERIVED_FROM (탈지분유 etc.) is CERTAIN as an alias relationship, not ingredient evidence. Existing profile Matcher behavior is separate and unchanged.
- Reference fixtures are web-verified candidates, not physical gold labels; do not generalize their metrics. S020 remains unresolved due to a label-version conflict and is excluded from Ingredient accuracy/coverage denominators. Final safety resolution is not implemented yet.
- Ingredient flow: `IngredientTreeParser.parse` preserves raw nodes and bracket structure; `IngredientEvidenceExtractor.extractEvidence` uses exact Registry aliases after `IngredientNameNormalizer` separates narrowly supported origin annotations. `node.semanticName()` retains the original name and annotations; Evidence matchedText and structural normalized-name path retain origin text. DIRECT_NAME / DERIVED_FROM / LEXICAL_HINT retain confidence. No substring fallback, source merging or user matching. Unspecified lecithin/gelatin sources remain unresolved; flavor hints remain POSSIBLE even when a child independently yields CERTAIN evidence.
- Parenthesized text is structural, not automatically a child ingredient. Only exact Registry matches produce evidence. Unmatched general ingredients are not unsupported allergen candidates; offline Reference candidate counts require explicit reviewed paths. See `docs/allergen-evidence-pipeline.md`.
- Stage 4 Resolver groups existing positive Evidence by exact allergen ID, preserving each source's meaning and provenance. Only supported source/type/confidence tuples can form Facts; unsupported combinations (including currently unused LIKELY) fail explicitly. Cross-contact CERTAIN yields CROSS_CONTACT_ONLY; multiple POSSIBLE hints stay POSSIBLE_PRESENT. Positive presence is never cancelled by source silence or DECLARED_NONE. No absent/safe status, taxonomy expansion or unresolved ingredient backfill. `AllergenFact` is an evidence summary, not a user safety decision. Guards: `AllergenEvidenceResolverTest`, `ResolverReferenceTest`, `ResolverReferenceValidationTest`.
- Guard tests: `AllergenDeclarationParserTest`, `AllergenRegistryTest`, `DeclarationReferenceTest`, `DeclarationReferenceValidationTest`, `AllergenCrossContactParserTest`, `CrossContactReferenceTest`, `CrossContactReferenceValidationTest`.
- Registry/hierarchy guards: `AllergenLexicalRegressionTest`, `AllergenHierarchyTest`. Synthetic lexical fixtures never count toward product Reference accuracy or coverage.
- Ingredient guards: `IngredientTreeParserTest`, `IngredientEvidenceExtractorTest`, `IngredientReferenceTest`, `IngredientReferenceValidationTest`.
- Stage 5 declaration coverage is independent of presence. Only EFFECTIVE rules matching jurisdiction and supplied applicable date may be selected. PROPOSED additions never become effective based on announcement/deadline/current time. Missing applicable date/basis stays UNKNOWN; do not use Reference verification dates or reverse-calculate manufacture dates. Exemptions require query-specific verified name identity; no fuzzy product-name inference. Sulfite requires addition context and final SO2 threshold, not an Ingredient Fact. Regulatory hierarchy lookup never creates Facts. Guard config overlap/gaps and preserve all existing Evidence/Fact contracts. See `docs/allergen-regulatory-coverage.md`.
- Stage 6a/6b boundary: Evidence/Regulatory/Profile output models remain internal to the exact `service.allergen` package until explicit authority migration; `AllergenArchitectureBoundaryTest` guards production imports/FQCN usage. ChatSafetyContextService owns chat/profile sentence normalization; Profile Resolver consumes normalized terms only. UNRESOLVED != DROP or fake ID. Preserve legacy literal fallback only in free-text compatibility, not typed Evidence matching. Fact explicitChildren union is a summary; supporting children must be traced through each Evidence's normalizedAllergen. See [ownership and migration](../allergen-architecture-boundary.md).
- Stage 6b profile identity: only exact DIRECT_NAME rows are eligible; multiple distinct eligible IDs are AMBIGUOUS, no rows are REGISTRY_MISS, derived/hint-only rows are NO_PROFILE_ELIGIBLE_ALIAS. Keep each input/source occurrence and require explicit Complete/Partial handling (no common resolved() accessor). Shellfish parents are taxonomy only: no automatic parent profile, explicitChildren or user match expansion. No runtime ProfileResolver wiring in 6b. See [Profile Resolution](../allergen-profile-resolution.md).
- Stage 6c shadow: only ChatSafetyContextService may call the void ProfileResolutionShadowObserver bridge. All Profile result/resolver types and recorder types stay internal. Observe accepted source batches without re-normalizing, do not globally dedup sources, skip empty input, and never feed shadow results back into safety. Resolver/metric failures fail open without concealing real DB/safety failures. Only enum labels; no terms, allergen IDs, user/session identifiers, exception messages or stack traces in shadow telemetry. See [Shadow Observation](../allergen-profile-shadow.md).

## 4. LLM output and validators
- Gates in the structured recipe path: allergy conflict check -> `RecipeDraftValidator` -> repair -> `RecipeValidator` -> `RecipeResponseSanitizer`. Changing their order or skipping one is a safety change.
- Excluded ingredients must not survive in ingredients, step ingredient names, instructions or adjustments (`RecipeDraftValidator`).
- Keep the qwen3 thinking decision in `OllamaLlmService.thinkingSettingFor` only; a second decision point previously produced empty replies in production (`be415ea`).
- Audit rows (`generated_recipes`, `validator_version`) must reflect the validator that actually ran; bump the version when rule semantics change.
- (WIP) Approved catalog rules (README "승인 레시피 카탈로그"): AI/web results are audited but never auto-promoted to the approved catalog; recipes require two sources and kitchen verification before approval.
- (WIP) Eval harness: grading reuses production prompt, client, validators and allergen matcher; the allergen dimension must have 0 failures in every mode; do not add harness-only grading rules.

## 5. Health, medication and external evidence
- Recipe Agent, YouTube, MFDS medication, RxNorm and openFDA integrations are off by default (`recipe.agent.*`). Do not enable live calls in tests or defaults.
- External evidence can be stale or wrong; treat missing or failed lookups as unknown and never present results as clinical advice.

## 6. Change checklist
Before: read the relevant section above; locate guard tests with `rg -n "<ClassName>" backend/src/test`; note existing WIP in the files.
After:
1. Targeted guard tests pass (list them); run the full backend suite when a shared gate changed.
2. Add a test for every new or changed safety behavior, including the unknown/failure path.
3. Report: behavior before -> after, tests run, what remains unverified.
