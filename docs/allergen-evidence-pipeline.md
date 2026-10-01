# Allergen Evidence Pipeline — 3 / 3.5 / 4 / 5단계

Declaration/Cross-contact/Registry의 설계와 1~2.5단계 이력:
[기존 문서](allergen-declaration-parser.md). 이 단계는 Ingredient 구조와 관찰 근거만 추가한다.
3.5단계의 24종 전수 검토·출처·보류 사유는 [Candidate triage](ingredient-candidate-triage.md)에 기록한다.
5단계는 Evidence/Fact와 독립된 [KR Regulatory Declaration Coverage](allergen-regulatory-coverage.md) 모듈이다.
적용 시점의 표시 범위만 평가하며 부재·사용자 안전을 판정하지 않는다.
6a의 [Architecture Boundary & Ownership](allergen-architecture-boundary.md)은 실제 production 호출 관계,
profile normalization 책임, literal fallback 및 Evidence/Regulatory 출력 모델의 임시 사용 경계를 정의한다.

## Ingredient Tree Parser

### Why Tree

`IngredientTreeParser.parse(IngredientInput)` → `IngredientTree` →
`IngredientEvidenceExtractor.extractEvidence(tree)`의 독립된 API다.
문법 파싱에는 Registry가 필요 없으며 Evidence 추출에는 기존 Registry를 주입한다.
전체 라벨 대신 호출자가 식별한 원재료란만 입력한다.

`IngredientInput`은 READABLE / NOT_FOUND / UNREADABLE을 구분한다.
읽을 수 있는 빈 문자열은 오류이며 NOT_FOUND나 정상 빈 Tree로 추정하지 않는다.
읽기 실패는 일부 원문을 보존할 수 있지만 Tree/Evidence를 만들지 않는다.
출처 충돌은 fixture의 CONFLICT 상태에서 제외하며 production parser에 한쪽 원문을 전달하지 않는다.

`IngredientNode(rawText, name, normalizedName, children)`는 불변 목록을 사용한다.
rawText에는 node의 원문 조각과 공백을 그대로 저장한다. name에는 괄호 앞의 이름·비율 표현을 보존한다.
normalizedName은 양끝 공백과 명시적인 후행 숫자 %만 정리한 구조 경로용 이름이다.
3.5단계부터 Registry 조회에는 `node.semanticName().normalizedName()`을 사용한다.
`IngredientSemanticName(raw, normalizedName, annotations)`는 node.name 원문, 의미 명칭, 제거한 주석을 보존한다.
전체 Tree의 rawText도 그대로 보존하고 normalizedText에서만 공백을 축약한다.

### Supported brackets

- `()`, `[]`, 혼합 nesting 지원.
- 원본 S007의 `{}`도 같은 규칙으로 지원.
- 전각 `（）`, `［］`, `｛｝`는 구문 판독 시 대응하며 rawText는 바꾸지 않는다.
- 자식 block은 node당 하나. 연속 block이나 괄호 뒤 일반 문장은 UNSUPPORTED_STRUCTURE다.
- 괄호 뒤의 숫자 %와 `/250mL` 같은 단위 표기는 원문에 보존한다.
- 64단계보다 깊은 nesting은 명시적인 UNSUPPORTED_STRUCTURE로 실패한다.

### Parsing algorithm

각 목록을 bracket stack으로 순회하며 stack이 비어 있는 쉼표에서만 분리한다.
닫는 괄호에 맞는 여는 괄호가 없으면 UNBALANCED_BRACKET,
종류가 다르면 MISMATCHED_BRACKET, 끝까지 닫히지 않아도 UNBALANCED_BRACKET이다.
빈 목록 항목·빈 이름·비율만 있는 이름은 EMPTY_NODE다.
문법 오류가 있으면 전체 parse가 실패하며 부분 Tree를 성공 결과로 반환하지 않는다.

분리한 node에서 괄호 앞 name과 내부 목록을 나누고 내부를 같은 규칙으로 재귀 파싱한다.
S003의 `1,000억`처럼 숫자 사이 세 자리 구분 쉼표는 목록 구분자로 사용하지 않는다.
일반적인 숫자/언어 전체 문법을 지원하는 것은 아니며 해당 경계 규칙은 테스트로 고정했다.
괄호 안의 국산·물·비율 등도 구조적 node로 보존하고 실제 하위원료라고 단정하지 않는다.

Declaration/Cross-contact의 기존 round-bracket tokenizer를 확장해 동작을 바꾸지 않았다.
Ingredient는 종류를 검증하는 stack이 필요하므로 별도 구조 파서를 사용하고,
기존 `AllergenLabelTokens.stripWhitespace`, Registry의 exact lookup과 Alias confidence를 재사용한다.

### Evidence extraction

모든 node의 semanticName().normalizedName()을 `AllergenRegistry.findExactAliases`로 조회한다.
부분 문자열 탐색이나 짧은 별칭 fallback, 새 알레르겐 사전은 없다.
3.5단계는 검토된 12종의 직접/파생 명칭과 3종 POSSIBLE 힌트만 기존 Registry에 추가한다.
`밀: 미국산`은 의미 명칭 밀과 주석 미국산으로 나누며 matchedText/path에는 기존 콜론 표현을 보존한다.
콜론 원산지 패턴은 실제 Reference에 있는 미국산/중국산/말레이시아산/필리핀산/외국산만 허용한다.
임의 설명·부정·역순·다중 콜론·전각 콜론은 해석하지 않는다. 원산지 자체는 alias가 아니다.
괄호 안 설명도 exact alias가 일치하는 경우에만 근거가 생기므로 문법 구조와 의미 해석은 별개다.

INGREDIENT의 직접 명칭을 DIRECT_DECLARATION으로 오인하지 않도록
`AllergenEvidence.EvidenceType.DIRECT_NAME`을 추가했다.
rawText는 root 원재료 전체 원문, matchedText는 실제 node.name이다.
예: `돼지고기 92.44%(국산)`의 matchedText는 `돼지고기 92.44%`,
정규화 ID는 기존 Registry의 PORK다.
normalizedAllergen.explicitChildren은 빈 목록이다. Ingredient 자식은 Tree/path에 보존하며
라벨의 명시적 알레르겐 하위 분류나 Safety Fact로 자동 변환하지 않는다.

### Evidence path

root에서 현재 node까지 normalizedName 목록으로 만든다.

```text
기타가공품[혼합제제[대두레시틴, 유화제], 정제소금], 설탕, 우유
SOY / DERIVED_FROM / CERTAIN
path = [기타가공품, 혼합제제, 대두레시틴]
MILK / DIRECT_NAME / CERTAIN
path = [우유]
```

같은 path + ID + relation은 최초 근거 하나로 중복 제거한다.
소스A → 대두유와 소스B → 대두레시틴처럼 다른 path는 보존한다.
동일 이름이 반복되는 형제는 이름 path가 같을 수 있으며 occurrence ID를 새로 만들지 않았다.
동일 alias의 서로 다른 등록 관계는 보존한다(예: 굴의 OYSTER 직접 명칭 / SHELLFISH 파생 관계).

### Relation / confidence

| Alias relation | Ingredient EvidenceType | Confidence |
|---|---|---|
| DIRECT_NAME | DIRECT_NAME | CERTAIN |
| DERIVED_FROM | DERIVED_FROM | CERTAIN |
| LEXICAL_HINT | LEXICAL_HINT | POSSIBLE |

우유향·콩의 POSSIBLE은 CERTAIN으로 승격하지 않는다.
CERTAIN은 Registry에 정의된 명칭/관계를 그대로 보존한다는 뜻이며 실제 함량·사용자 위험 확정이 아니다.
기존 Registry의 넓은 파생 관계도 그대로다. 예컨대 이 사전의 물엿 → SULFITE를
이번 단계에서 임의 제거하거나 규제/실제 함유 사실로 재해석하지 않았다.
이 관계 품질과 후속 Resolver 해석은 별도 검토가 필요하다.

### Synthetic examples

`IngredientTreeParserTest`는 flat, 한 단계, 깊은 nesting, 소괄호, 혼합 괄호,
중괄호/전각, 비율/설명, 숫자 쉼표, 원문 보존, malformed/mismatch와 깊이 제한을 검증한다.
`IngredientEvidenceExtractorTest`는 경로, 동일 경로 중복 제거, 서로 다른 경로 보존,
메추리알/알류, 탈지분유/우유향 및 범용 원료 처리 등을 검증한다.
기존 lexical fixture **25개를 그대로 Ingredient 파싱 경로에 재사용**하여 오탐과 관계를 확인한다.
실제 제품 지표에 합성 사례를 포함하지 않는다.

### S001-S020 validation

원본: `docs/reference/Salus_Strict_Web_Reference_20_2026-09-09.xlsx`,
`Accepted_20!G2:G21`. SHA-256:
`ad5e41e3c8366dd9b45571186f3ad95683e93395a48bb4c639b9cb283213d8fb`.
Fixture: `backend/src/test/resources/allergens/ingredient-reference.json`.

20개 ID/GTIN, 원본 전사, 출처 URL/메모, raw/normalized/note를 분리했다.
19개의 raw는 원본 G열과 동일하다. 작성자 해설은 입력에 넣지 않는다.
기대 root 목록, nested paths, Evidence 및 별도 검토 후보는
원본·기존 Registry를 보고 작성했으며 production parser 출력에서 정답을 생성하지 않았다.
명칭별 원문 조각도 테스트에서 부모 원문과 대조한다.

S020은 Excel/공개 이미지의 원재료·품목보고번호 버전 충돌이다.
원본의 들기름김/올리브김 component 텍스트는 source.originalIngredient에 보존하지만
raw=null, CONFLICT로 두고 두 출처 중 어느 쪽도 파싱 정답으로 선택하지 않았다.
별도 ProductComponent/DB 모델을 만들지 않았으며 모든 평가 분모에서 제외한다.

### 3.5단계 현재 Metrics

구조 **19/19**, coverage **13/19 (68.4%)**, Evidence **39**:
CERTAIN **35**, LIKELY **0**, POSSIBLE **4**.
DIRECT_NAME **15** (10개 제품), DERIVED_FROM **20** (9개), LEXICAL_HINT **4** (4개).
원본 reviewedCandidates 30회/24종을 보존한 채 semantic lookup 실패만 다시 세어 **7회/6종**이다.
유당(S008/S011), 레시틴(S011), 젤라틴(S014), 사골엑기스·오뚜기참치간장분말·쇠고기브이용(S017)이 남는다.
남은 6개 coverage 미달 제품을 안전으로 판정하지 않는다. S020 충돌 제외는 그대로다.

이하 Metrics·후보 표·검증 기록은 **3단계 변경 전 이력**이다.

### 3단계 Metrics (변경 전)

| 지표 | 결과 |
|---|---|
| 연결 제품 / PARSED / CONFLICT | 20 / 19 / 1 |
| Ingredient Structural Parsing Accuracy | **19 / 19 = 100.0%** |
| Ingredient Evidence Coverage | **11 / 19 = 57.9%** |
| 총 Ingredient Evidence | **16** |
| CERTAIN / LIKELY / POSSIBLE | **15 / 0 / 1** |
| 검토 지정 미정규화 후보 | **30회 / 고유 24종 / 9개 제품** |
| Parse errors | 5개 분류 모두 0, 발생 제품 없음 |

Structural Accuracy는 PARSED 상태, 순서/중복을 포함한 전체 root 이름 목록(따라서 root count),
fixture에 지정한 nested paths의 존재 및 원문/normalized 보존의 일치율이다.
모든 내부 node의 의미와 전체 Tree를 완전히 수작업 검증한 정답률이 아니다.
읽을 수 있는 원문에서 파싱 실패한 제품은 분모에서 제거하지 않는다.
Evidence 기대값은 구조 정확도와 별도로 모든 ID/relation/confidence/matchedText/path 및 중복을 검증한다.

Coverage 분자는 하나 이상의 INGREDIENT 근거가 있는 제품이며 POSSIBLE도 포함한다.
분모는 충돌을 제외한 평가 가능 제품 19개다. 나머지 8개를 안전/비알레르겐으로 판정하지 않는다.
다른 source와의 합집합·불일치 비교·자동 병합은 하지 않는다.

| Relation | Evidence 수 | 제품 수 |
|---|---|---|
| DIRECT_NAME | 4 | 4 |
| DERIVED_FROM | 11 | 7 |
| LEXICAL_HINT | 1 | 1 |
| 기타 | 0 | 0 |

제품 수는 relation 사이에 중복 가능하다.
실측 보고서: `backend/target/ingredient-reference-report.txt`.

#### Unsupported allergen candidates

자동으로 모든 unknown을 세지 않는다. fixture의 `reviewedCandidates`에 명시한 path만 대상으로
해당 node의 exact Registry 조회가 실패했을 때 집계한다(3.5단계는 semanticName을 조회).
이는 원문을 검토하며 지정한 후속 조사 범위이며 알레르겐 함유 확인 또는 완전한 누락 목록이 아니다.
일반 설탕/정제수/소금은 미등록이더라도 이 후보 통계에 포함하지 않는다.
레시틴/젤라틴/향·복합표현의 후보 지정도 특정 알레르겐에 연결하는 규칙이 아니다.

| 제품 | 미정규화 검토 표현 |
|---|---|
| S001 | 원유 |
| S002 | 원유 |
| S003 | 원유, 혼합탈지분유 |
| S007 | 소맥분, 밀: 미국산, 건새우분말, 새우: 중국산, 새우추출물분말, 새우: 미국산, 새우풍미유 |
| S008 | 밀: 미국산, 전지분유, 돼지, 전란액, 카제인나트륨, 유당 |
| S009 | 밀: 미국산, 버터향, 산성아황산나트륨 |
| S011 | 밀: 미국산, 유당, 유청, 레시틴 |
| S014 | 젤라틴 |
| S017 | 사골엑기스, 유크림, 오뚜기참치간장분말, 쇠고기브이용, 비프맛양념 |

보고서는 제품별 raw node/path/검토 이유와 token 빈도를 보존한다.
후보들은 REGISTRY_MISSING 또는 관계/주석 정규화 검토 대상으로 남기며
substring으로 강제 매칭하거나 숫자를 개선하기 위해 사전을 추가하지 않았다.

#### 검증 기록

2026-09-18, Java 17 / Maven 3.9. 전체 **787개, 실패 0, 오류 0, 기존 skip 1**.
추가 92개는 Parser 31, Evidence 29, Reference 23, 집계기 검증 9개다.
관련 allergen/안전 guard 테스트와 전체 backend 테스트를 실행했다.
집계기 테스트 작성 중 발생한 Java Error 타입 이름 충돌은 명시적 import로 해결했다.

```bash
# backend 디렉터리에서 순서대로 실행
mvn -q -Dtest='IngredientTreeParserTest,IngredientEvidenceExtractorTest' test
mvn -q -Dtest='IngredientTreeParserTest,IngredientEvidenceExtractorTest,IngredientReferenceTest' test
mvn -q -Dtest='Ingredient*Test,Allergen*Test,DeclarationReference*Test,CrossContactReference*Test,ChatSafetyContextServiceTest,ChatServiceSafetyTest' test
mvn -q clean test
# 저장소에서
git diff --check
```

신규/untracked 작업 파일도 별도 공백 검사를 통과했다.
Declaration **15/15**, Cross-contact **10/10**, Cross-contact 미지원 **0**,
기존 synthetic lexical **25/25**를 확인했다.
기존 Registry/YAML, Declaration/Cross-contact/lexical fixture는 작업 전후 byte 동일하다.
원본 Excel은 SHA-256 검증으로 보존을 확인했다.
DB/API/OCR, frontend, live LLM, 실제 사용자 판정과 실물 라벨 검증은 실행하지 않았다.

### Known limitations

- 괄호 내용의 원산지/설명/하위원료 의미를 완벽히 구분하지 않는다.
- `밀: 미국산`은 구조 node를 유지하면서 제한된 원산지 문법으로만 밀을 조회한다. 임의 콜론 문장은 해석하지 않는다.
- 명칭 끝 %만 보수적으로 제거한다. 모든 수량·단위·브랜드·가공 표현을 정규화하지 않는다.
- 레시틴·젤라틴처럼 출처가 없는 원료와 미검증 복합원료는 여전히 unresolved다.
- 파싱 가능한 문장이라는 결과가 모든 원재료·알레르겐이 인식됐다는 뜻이 아니다.
- 부모 계층 추론·규제 목록·사용자 알레르기 매칭·실물 라벨 검증은 하지 않는다.
- 웹 전사 Reference이며 GOLD_PHYSICAL이 아니다. S020은 버전 확정 전까지 제외한다.

### Not a Safety Decision

Tree node의 존재, CERTAIN, Evidence의 부재는 SAFE/DANGER/CAUTION 또는 섭취 가능 판단이 아니다.
Ingredient Parser/Extractor 자체는 Declaration/Cross-contact와 비교하거나 AllergenFact를 만들지 않는다.
4단계는 아래 별도 Resolver에서 이미 생성된 Evidence를 묶는다. commit / push / PR 없음.

## Evidence Resolver

### Responsibility

4단계(2026-09-19): `AllergenEvidenceResolver.resolve(List<AllergenEvidence>)` → `List<AllergenFact>`.
호출자는 **한 제품**의 Declaration / Ingredient / Cross-contact Evidence를 전달한다.
입력에 제품 ID가 없으므로 여러 제품의 Evidence를 혼합하면 안 된다.
Parser/Registry/YAML은 바꾸지 않고 이미 정규화된 ID별로만 positive 관찰을 구조화한다.
DB/Redis/HTTP/LLM/현재 날짜/사용자 Context/Registry 의존성은 없다.

`AllergenFact(String allergen, PresenceStatus status, List<AllergenEvidence> evidences,
EvidenceSource strongestPresenceSource)`는 불변 record다.
현재 Evidence/Registry의 ID 계약은 String이므로 새로운 AllergenId enum이나 중복 사전을 도입하지 않았다.
`explicitChildren()`은 Evidence에 명시된 자식의 union을 제공한다.
비어 있는 ID, 빈 Evidence, 다른 ID가 섞인 Fact, 실제 Evidence에 없는 strongest source는 생성할 수 없다.
이 Fact를 API/DB/사용자 안전 흐름에 연결하지 않았다.

### PresenceStatus

| Source | EvidenceType | Confidence | Status |
|---|---|---|---|
| DECLARATION | DIRECT_DECLARATION | CERTAIN | CONFIRMED_PRESENT |
| INGREDIENT | DIRECT_NAME | CERTAIN | CONFIRMED_PRESENT |
| INGREDIENT | DERIVED_FROM | CERTAIN | CONFIRMED_PRESENT |
| INGREDIENT | LEXICAL_HINT | POSSIBLE | POSSIBLE_PRESENT |
| CROSS_CONTACT | CROSS_CONTACT | CERTAIN | CROSS_CONTACT_ONLY |

ABSENT / SAFE / NO_RISK / MISMATCH / INCONSISTENT는 만들지 않는다.
지원하지 않는 source/type/confidence 조합은 전체 resolve 호출에서 IllegalArgumentException으로 실패한다.
현재 생성되지 않는 LIKELY를 임의로 CERTAIN 또는 POSSIBLE로 해석하지 않는다.
null 입력/항목과 빈 ID도 조용히 버리지 않는다.

### Source semantics

Declaration은 명시적 함유 표시, Ingredient는 원재료 명칭 관계, Cross-contact는 교차접촉 문구 관찰이다.
Cross-contact CERTAIN은 문구 존재의 확신이며 실제 원료 함유 확정이 아니다.
Resolver는 confidence를 변경하지 않으며 다른 출처로 Ingredient의 빈 출처를 보충하지 않는다.
DECLARED_NONE / NOT_FOUND / UNREADABLE 같은 source 상태는 Resolver 입력의 negative Evidence가 아니다.
호출자는 원래 ParseResult 상태와 오류, 미해석 토큰을 별도로 보존해야 한다.
Parser 오류를 빈 Evidence로 바꿔 정상 제품처럼 평가하지 않도록 통합 평가에서도 실패를 기록한다.

### Resolution priority

`CONFIRMED_PRESENT > POSSIBLE_PRESENT > CROSS_CONTACT_ONLY`.
하나의 지원되는 positive Evidence만으로 해당 상태를 지지할 수 있고, 약한 Evidence는 삭제하지 않는다.
POSSIBLE 여러 개를 합쳐 CERTAIN으로 올리는 개수 기반 점수는 없다.
Ingredient CERTAIN + Cross-contact CERTAIN은 CONFIRMED_PRESENT이며 두 근거를 모두 보존한다.
POSSIBLE + Cross-contact CERTAIN은 POSSIBLE_PRESENT다.
빈 입력은 빈 Fact 목록이며 전체 제품의 부재/안전을 뜻하지 않는다.
Declaration silence 및 DECLARED_NONE도 기존 positive Ingredient Evidence를 취소하지 않는다.

### Strongest source

우선 현재 status를 가장 강하게 지지하는 Evidence를 고른다.
동일 presence 수준에서는 `DECLARATION > INGREDIENT > CROSS_CONTACT`다.
따라서 Possible + Cross-contact에서 strongest는 INGREDIENT다.
단순 confidence enum 순서나 source 개수, 위험 점수를 쓰지 않는다.

### Deduplication

AllergenEvidence record 전체 equality로만 제거한다:
source / rawText / matchedText / normalizedAllergen(ID와 explicitChildren) /
evidenceType / confidence / path.
서로 다른 path, 원문, matchedText, 관계, 자식 목록은 각각 남는다.
Fact 및 그 안의 Evidence는 입력의 첫 등장 순서를 유지한다.
같은 입력은 같은 결과이며 입력 순서를 바꾸어도 상태/strongest/근거 집합은 바뀌지 않는다.
입력 목록을 수정하지 않고 반환 목록과 내부 목록을 불변 복사한다.

### Hierarchy non-expansion

QUAIL_EGG와 EGG_GROUP은 다른 Fact다. QUAIL_EGG에서 EGG_GROUP/EGG를 자동 추가하지 않는다.
OYSTER에서 SHELLFISH를 추론하지 않는다.
기존 Registry가 굴에 대해 이미 만든 복수 Evidence는 그대로 받지만 Resolver가 새 Evidence/ID를 만들지는 않는다.

### Explicit children

Evidence별 normalizedAllergen.explicitChildren을 그대로 보존한다.
Fact.explicitChildren()은 첫 등장 순서의 중복 없는 union이다.
SHELLFISH의 OYSTER + ABALONE/MUSSEL을 합쳐도 독립 OYSTER/ABALONE/MUSSEL Fact는 만들지 않는다.
실제 S019의 SHELLFISH Declaration은 OYSTER 자식을 유지하며,
S007/S011/S017 교차접촉의 SHELLFISH는 OYSTER/ABALONE/MUSSEL을 유지한다.

### Unresolved behavior

유당·레시틴·젤라틴·사골엑기스·오뚜기참치간장분말·쇠고기브이용은 여전히 **7회/6종**이다.
이 후보들은 정규화 Evidence가 없으므로 Resolver 입력이 아니다.
S014 PORK Fact는 Declaration만으로 생성되며 젤라틴의 relation/source는 바뀌지 않는다.
S011 SOY Fact도 Declaration만으로 생성되며 레시틴을 SOY Ingredient로 바꾸지 않는다.
모든 Fact의 Evidence를 입력 record 집합과 대조하여 추가 추론·유실·변조·중복을 검증한다.

### S001-S019 metrics

`resolver-reference.json`은 기존 세 Reference의 **독립 expected Evidence**를 ID별로 대조한 고정 기대값이다.
production Resolver 출력에서 정답을 생성하지 않았다. 제품 ID/GTIN을 확인해 실제 세 Parser 출력을 결합한다.
기대값에는 allergen/status/source composition/strongest source/explicit children/Evidence count가 있다.
원래 Reference·후보 목록·YAML을 수정하지 않았다. S020 충돌 버전은 선택하지 않고 제외한다.

Resolver Fact Accuracy는 평가 가능한 제품 중 **전체 Fact의 allergen + status + source composition**이
누락·추가·중복 없이 모두 일치하는 제품 비율이다.
strongest/children/count/전체 Evidence 보존은 별도의 추가 strict 검증으로 모두 통과해야 한다.
파싱/Resolver 오류가 나도 평가 가능한 제품은 분모에 남는다.
빈 기대값과 빈 출력이 일치한 제품도 정확도에는 포함되지만 coverage에는 포함되지 않는다.

| 지표 | 결과 |
|---|---|
| 평가 / 제외 | 19 / S020 1개 |
| Resolver Fact Accuracy | **19/19 (100.0%)** |
| Resolved Allergen Fact Coverage | **16/19 (84.2%)** |
| Fact 합계 | **116** |
| CONFIRMED_PRESENT | **40 Fact / 15개 제품** |
| POSSIBLE_PRESENT | **0 Fact / 0개 제품** |
| CROSS_CONTACT_ONLY | **76 Fact / 10개 제품** |
| Evidence per Fact | min **1**, max **7**, average **1.319** |
| 보존된 Evidence | **153** |

| Source composition | Fact 수 |
|---|---:|
| DECLARATION_ONLY | 15 |
| INGREDIENT_ONLY | 2 |
| CROSS_CONTACT_ONLY | 76 |
| DECLARATION+INGREDIENT | 22 |
| DECLARATION+CROSS_CONTACT | 0 |
| INGREDIENT+CROSS_CONTACT | 1 |
| ALL_THREE | 0 |

Strongest source: DECLARATION **37**, INGREDIENT **3**, CROSS_CONTACT **76**.
status별 제품 수는 중복 가능하다. Fact 수는 제품마다 같은 ID를 따로 센다.
실제 Reference의 POSSIBLE Evidence 4개는 모두 같은 ID의 확정 근거와 함께 존재하므로
POSSIBLE_PRESENT Fact가 0개다. 이 근거 4개 자체는 삭제되거나 confidence가 승격되지 않았다.
실제 데이터에 없는 ALL_THREE와 Declaration+Cross-contact 등은 합성 조합 테스트에서 별도로 검증한다.
S004/S010/S015의 빈 Fact는 NO_POSITIVE_FACTS로 보고하며 ABSENT/SAFE로 해석하지 않는다.
원료 APPLE의 Declaration silence 예시는 Registry를 확장하지 않고 합성 Evidence로 검증한다.
제품 데이터는 기존 웹 전사 Reference이며 실물 Gold나 새 알레르겐 ontology 검증이 아니다.

실측 보고서: `backend/target/resolver-reference-report.txt`.
기존 회귀: Declaration 15/15, Cross-contact 10/10 (unsupported 0), Ingredient 구조 19/19,
Ingredient Evidence 39 (CERTAIN 35 / POSSIBLE 4), lexical 25/25, candidate 24/24.
Resolver가 새로 추론한 unresolved 항목은 **0**이다.

### Not Safety Decision

CONFIRMED_PRESENT는 현재 입력 Evidence 계약이 확정 함유를 지지한다는 요약이며
독립적인 실물 원료 검사 결과나 사용자 위험도·섭취 가능 판단이 아니다.
특히 기존 Registry의 파생 관계 품질은 그대로 상속하며 이번 단계에서 재판정하지 않았다.
Regulatory Coverage Resolver, 부재 판정, 사용자 프로필/계층 매칭, DANGER/CAUTION/SAFE,
약물/질환, OCR/API, LLM, frontend, DB 리팩터링은 구현하지 않았다.
4단계 이후의 Regulatory Coverage 모듈은 [5단계 문서](allergen-regulatory-coverage.md)에 기록한다.

### Stage 4 verification — 2026-09-19

Java 17 / Maven에서 순서대로 실행했다(backend 기준).

```bash
mvn -q -Dtest=AllergenEvidenceResolverTest test
mvn -q -Dtest='AllergenEvidenceResolverTest,ResolverReferenceTest' test
mvn -q -Dtest='Allergen*Test,*ReferenceTest,*ReferenceValidationTest,Ingredient*Test,ChatSafetyContextServiceTest,ChatServiceSafetyTest' test
mvn -q clean test
```

전체 **883개, failures 0, errors 0, skipped 1**.
skip은 기존 opt-in `RecipeAccuracyLiveTest`이며 live LLM을 실행하지 않았다.
추가 **49개**: Resolver 단위 21개(합성 조합 12개 포함), 제품 통합 24개, 평가기 검증 4개.
필수 10개 조합에 Declaration+Possible와 Ingredient DIRECT_NAME을 추가하고 입력 순열별 결과도 확인했다.
역추론/부재 추론/계층 확장/자식 손실/서로 다른 경로 dedup/입력 변조를 방어한다.
source×type×confidence 45개 중 현재 계약 5개만 지원하며 나머지 40개는 명시 실패하는 테스트를 통과했다.
지정된 실패 분류에 해당하는 실제 회귀는 발견되지 않았다. 지원되지 않는 confidence 등의
SOURCE_SEMANTIC_COLLISION 가능성은 조용히 보정하지 않고 예외로 드러낸다.

`git diff --check` 및 신규/untracked 작업 파일의 별도 공백 검사를 통과했다.
작업 전 snapshot과 대조한 기존 allergen production 파일, Registry YAML, 기존 모든 allergen fixture는
**byte 동일**하다. S020과 7회/6종 unresolved를 그대로 유지한다.
API/DB 통합, frontend 빌드, live LLM, OCR/외부 API, 실물 라벨 검증은 수행하지 않았다.
commit / push / PR 없음.

### Stage 4 changed files

```text
backend/src/main/java/com/salus/healthytable/service/allergen/AllergenFact.java
backend/src/main/java/com/salus/healthytable/service/allergen/PresenceStatus.java
backend/src/main/java/com/salus/healthytable/service/allergen/AllergenEvidenceResolver.java
backend/src/test/java/com/salus/healthytable/service/allergen/AllergenEvidenceResolverTest.java
backend/src/test/java/com/salus/healthytable/service/allergen/ResolverReferenceTest.java
backend/src/test/java/com/salus/healthytable/service/allergen/ResolverReferenceValidation.java
backend/src/test/java/com/salus/healthytable/service/allergen/ResolverReferenceValidationTest.java
backend/src/test/resources/allergens/resolver-reference.json
docs/allergen-evidence-pipeline.md
docs/ai/PROJECT_CONTEXT.md
docs/ai/SAFETY_RULES.md
```
