# Allergen Label Evidence Parsers — Declaration / Cross-contact

공식 알레르기 함유 표시란과 교차접촉 표시란을 각각 구조화하는 Java 17 / Spring 컴포넌트다.
호출자는 라벨 원문임을 확인한 문자열을 전달한다. Evidence는 라벨의 관찰 기록이며
사용자의 알레르기와 비교하거나 섭취 가능 여부를 결정하지 않는다.

3단계 원재료 Tree/Evidence 구현과 최신 검증은
[Ingredient Tree Parser](allergen-evidence-pipeline.md)에 기록한다. 이 문서의 1~2.5단계 검증 이력은 유지한다.

## 기존 구현과의 관계

- 기존 `service/allergen/AllergenDictionary.java`가 읽는 `allergens/ko-allergens.yaml`과 문자열 ID를 재사용한다.
- 기존 `AllergenMatcher`는 채팅/레시피에서 사용자 알레르기와 재료의 충돌을 탐지한다.
  그 부분 문자열/파생 재료 규칙은 공식 함유 표시의 CERTAIN 근거에 적합하지 않아 Parser에서 호출하지 않는다.
- `AllergenDictionary.registryAliases()`는 같은 사전에서 Alias → Relation → Allergen 정보를 제공한다.
  `AllergenRegistry`는 이 데이터의 읽기 전용 조회 뷰다. 독립된 알레르겐 목록을 Java에 복제하지 않았다.
- 기존 `aliases`/`derived`와 Matcher의 동작은 그대로 유지한다. 직접 명칭은 `name`과
  `declarationAliases`에서만 얻는다. `derived`/`registryDerived`는 DERIVED_FROM,
  `lexicalHints`는 LEXICAL_HINT로 보존한다. `registryDerived`는 Registry 전용 확장이다.
- `declarationAllergens`의 OYSTER/ABALONE/MUSSEL은 괄호에 명시된 굴/전복/홍합을 보존하기 위한 ID다.
  기존 Matcher의 루트는 추가하지 않는다. 법정 알레르겐 수를 가정하지 않는다.

## 입력과 결과

```java
// Spring이 생성한 AllergenDeclarationParser를 주입받아 호출한다.
var result = parser.parse("토마토, 대두, 밀, 게, 조개류(굴 포함) 함유");
// state: DECLARED_PRESENT
// evidence: TOMATO, SOY, WHEAT, CRAB, SHELLFISH(explicitChildren=[OYSTER])
// 각 근거: DECLARATION / DIRECT_DECLARATION / CERTAIN / path=[]

parser.parse("해당사항 없음");
// DECLARED_NONE, evidence=[]
parser.parse(DeclarationInput.notFound());
// DECLARATION_NOT_FOUND, evidence=[]
parser.parse(DeclarationInput.unreadable("우유 ..."));
// UNREADABLE, rawText="우유 ...", evidence=[]
```

`AllergenEvidence`에는 source, rawText, matchedText, normalizedAllergen,
evidenceType, confidence, path가 있다. `NormalizedAllergenRef`는 ID와 explicitChildren을 보존한다.
하위 항목은 라벨의 괄호 안에 실제로 적힌 항목만 의미하며, 분류 체계에 따른 자식 추론은 하지 않는다.
SHELLFISH만 적혀 있으면 OYSTER를 자동으로 추가하지 않는다.

`DeclarationParseResult`는 state, rawText, normalizedText, evidence, unparsedTokens를 가진다.
원문과 매칭된 토큰의 내부 공백/개행은 그대로 두고, normalizedText의 공백만 정규화한다.
NOT_FOUND는 원문이 없고, UNREADABLE은 일부 원문을 보존할 수 있다. 두 경우 normalizedText는 null이다.
모든 결과 리스트는 변경할 수 없는 복사본이다.

## 지원 문법과 실패 처리

- 지원 문법은 `쉼표로 구분한 직접 명칭 목록 + 공백 + 함유`, 또는 정확한 `해당사항 없음`이다.
  공백/개행 변형은 허용한다. `함유`가 없는 목록이나 전체 라벨 문장은 이 단계의 입력 문법이 아니다.
- 괄호 깊이가 0일 때만 쉼표로 구분한다. `조개류(굴, 전복, 홍합 포함)`은 하나의 최상위 토큰이다.
- 괄호 안의 직접 명칭 목록과 선택적 마지막 `포함`을 지원한다. 괄호 안의 중첩 수식어는 아직 해석하지 않는다.
- 직접 명칭은 전체 토큰이 일치해야 한다. `메밀`은 BUCKWHEAT, `땅콩`은 PEANUT이며,
  `밀크초콜릿`/`굴비`에서 WHEAT/OYSTER를 추출하지 않는다. 별칭 조회 목록은 최장 일치 우선 순서로 제공한다.
- 문법상 함유 목록이 있으나 미등록 항목이 있으면 `DECLARED_PRESENT`와 `unparsedTokens`를 반환한다.
  예: `우유, 키위 함유` → MILK Evidence 및 미지원 `키위`. Evidence가 비었다고 표시 없음이나 알레르겐 없음으로 바꾸지 않는다.
- 괄호 토큰의 일부가 미등록이거나 `굴 제외` 등 미지원 수식이면 해당 토큰 전체를 unparsedTokens로 남긴다.
  지원하는 부분만 골라 확실한 하위 항목으로 승격하지 않는다.
- 빈 입력, 괄호 불균형, 빈 목록 항목, 미지원 문장 형식은 `IllegalArgumentException`이다.
  입력 오류를 `UNREADABLE`이나 `DECLARATION_NOT_FOUND`로 바꾸지 않는다. 이 관찰 상태는 호출자만 지정한다.

## 1단계 검증 이력

2026-09-12의 8개 fixture는 요청 예시 7개와 S008-type 합성 사례 1개였다.
당시 8/8 정확도와 7/8 coverage는 실제 20종 제품 검증 지표가 아니다.
1.5단계에서 아래 원본 Reference 20종으로 대체했으며, S010의 근거 없는
명시적 없음 기대값과 S008-type의 합성 목록을 더 이상 제품 정답으로 사용하지 않는다.
기존 Parser/Registry 단위 테스트는 유지한다.

## S001-S020 Reference Validation

### Dataset

- **20 products**, S001~S020 각각 한 레코드. 15개 DECLARED_PRESENT,
  0개 DECLARED_NONE, 5개 DECLARATION_NOT_FOUND, 0개 UNREADABLE.
- 사용자 제공 `Salus_Strict_Web_Reference_20_2026-09-09.xlsx`의
  `Accepted_20!A2:T21`을 사용한다. 원본 검증일은 2026-09-09,
  이번 선언 상태 검토일은 2026-09-17이다. Excel 자체는 변경하지 않았다.
- Source type: **WEB_VERIFIED_STRICT**, 웹 라벨/전사 Reference, **Gold candidate**.
  원본의 등급을 기록한 것이며 이번 실행이 20개 실물이나 현재 모든 URL의 재검증을 뜻하지 않는다.
- fixture: `backend/src/test/resources/allergens/declaration-reference.json`.
  파일명과 SHA-256, 제품별 GTIN/규격, 원본 셀 위치·선언 셀 값·검증 메모,
  라벨 URL·독립 GTIN URL·가능한 이미지 URL을 보존한다.
- `declaration.raw`에는 Reference의 함유 선언 전사만 넣고 설명은 `note`로 분리한다.
  상태 재검토 5개는 원문 선언이 없어 `raw=null`, `availability=NOT_FOUND`다.
  원본 H열의 설명/상품고시 문구는 `source.originalDeclaration`에 감사용으로 남기며 Parser에 전달하지 않는다.
- 입력 `availability`는 관찰 근거에서 지정하고 `expectedState`와 독립적으로 보관한다.
  기대 ID와 explicitChildren은 Parser 출력에서 생성하지 않았다.
  원재료 G열과 교차접촉 I열은 입력하지 않는다.

| ID | 제품 | 기대 상태 | 기대 allergen set | explicitChildren |
| --- | --- | --- | --- | --- |
| S001 | 서울우유 | DECLARED_PRESENT | MILK | ∅ |
| S002 | 서울 초코우유 | DECLARED_PRESENT | MILK | ∅ |
| S003 | 매일 바이오 PROBIOTIC 드링킹요거트 플레인 | DECLARED_PRESENT | MILK | ∅ |
| S004 | 햇반 | DECLARATION_NOT_FOUND | ∅ | ∅ |
| S005 | 스팸 클래식 | DECLARED_PRESENT | PORK | ∅ |
| S006 | 특등급 국산콩 두부 2입 기획 | DECLARED_PRESENT | SOY | ∅ |
| S007 | 새우깡 | DECLARED_PRESENT | WHEAT, SHRIMP, SOY, MILK | ∅ |
| S008 | 초코파이 정 | DECLARED_PRESENT | EGG, WHEAT, MILK, SOY, BEEF, PORK | ∅ |
| S009 | 빠다코코낫 | DECLARED_PRESENT | WHEAT, SOY, MILK, SULFITE | ∅ |
| S010 | 제주삼다수 | DECLARATION_NOT_FOUND | ∅ | ∅ |
| S011 | 오레오 화이트크림 | DECLARED_PRESENT | WHEAT, SOY, MILK | ∅ |
| S012 | 토마토 케찹 | DECLARED_PRESENT | TOMATO | ∅ |
| S013 | 해표 맑고 신선한 식용유(콩기름) | DECLARED_PRESENT | SOY | ∅ |
| S014 | 참쌀설병 | DECLARED_PRESENT | PORK, SOY | ∅ |
| S015 | 백설 하얀설탕 | DECLARATION_NOT_FOUND | ∅ | ∅ |
| S016 | 코카-콜라 | DECLARATION_NOT_FOUND | ∅ | ∅ |
| S017 | 3분 카레 순한맛 | DECLARED_PRESENT | MILK, SOY, WHEAT, TOMATO, BEEF | ∅ |
| S018 | 포스트 콘푸라이트 | DECLARED_PRESENT | SOY | ∅ |
| S019 | 동원 고추참치 | DECLARED_PRESENT | TOMATO, SOY, WHEAT, CRAB, SHELLFISH | SHELLFISH → OYSTER |
| S020 | 양반 들기름&올리브 혼합김 | DECLARATION_NOT_FOUND | ∅ | ∅ |

### Metrics

`DeclarationReferenceTest`의 동일 parameterized test에서 20개를 전부 실행한다.
`DeclarationReferenceValidation`은 테스트 전용 집계 코드이며 production Parser를 수정하지 않는다.
제품별 성공은 상태·정확한 allergen set(누락/추가/중복 없음)·명시적 하위 항목 집합·
예상하지 않은 파싱 오류 없음이 모두 충족되어야 한다. 추가로 원문/normalized/matchedText,
Evidence 속성과 예상 미지원 토큰(이번 20개 모두 빈 목록)을 검사한다.
순서 차이는 allergen/child 정답을 바꾸지 않는다.

| 지표 | 2026-09-17 결과 | 분모와 해석 |
| --- | --- | --- |
| Declaration Parsing Accuracy | **15 / 15 (100.0%)** | 읽을 수 있는 실제 함유 선언 fixture. DECLARED_NONE이 있으면 포함하지만 이번 자료에는 0개 |
| 전체 상태 포함 strict fixture 일치 | **20 / 20 (100.0%)** | 15개 선언 파싱 + 5개 관찰 상태 전달 검증. 이 수치를 선언 문자열 파싱 정확도로 표시하지 않음 |
| Declaration Coverage | **15 / 20 (75.0%)** | 최소 한 개의 DECLARATION / DIRECT_DECLARATION / CERTAIN Evidence를 생성한 제품 / 전체 20개 |
| Unsupported Tokens | **총 발생 0 / 고유 0 / 목록 및 발생 제품 없음** | unparsedTokens의 발생 횟수와 제품별 횟수를 집계. 중복도 발생 횟수에 포함 |
| 괄호 불균형 | **0** | Parser가 반환한 예외로 관측한 오류 |
| 빈 token | **0** | 동일 |
| 잘못된 선언 형태 | **0** | 동일 |
| 예상하지 않은 exception | **0** | 알려진 문법 오류 이외의 RuntimeException |
| 예상된 오류 / 예상하지 않은 오류 합계 | **0 / 0** | 실제 Reference에는 예외를 기대하는 사례가 없음 |

S004/S010/S015/S016/S020은 별도 선언 문자열이 없으므로 파싱 정확도 분모에서 제외한다.
5개 모두 실행하여 상태/빈 Evidence/빈 미지원 토큰을 검증한다. UNREADABLE도 같은 방식으로
파싱 분모에서 제외하되 상태 전달은 테스트한다. 읽을 수 있는 입력에서 예외가 발생하면
분모에 남기고 실패로 계산한다. 평가 가능한 입력이 0개면 N/A이며 100%로 표시하지 않는다.

오류는 실패한 제품마다 Parser가 처음 보고한 한 범주로 센다. Parser에 구조화 오류 코드가 없어
기존의 알려진 예외 메시지를 테스트 집계기에서만 분류하고, 모르는 메시지는
UNEXPECTED_EXCEPTION으로 남긴다. 하나의 문장 안에 있는 모든 문법 오류 개수를 추정하지 않는다.
미지원 토큰은 토큰별 총 횟수·고유 수·발생 제품별 횟수를 출력한다. 미래의 실패에서도
다른 제품 실행을 중단하지 않고 보고서를 먼저 저장한 후 테스트를 실패시킨다.

집계기 회귀 테스트의 합성 입력(반복 키위, 잘못된 괄호/빈 토큰/잘못된 형태/강제 exception,
explicit-none, unreadable 등)은 위 20종 수치에 포함하지 않는다.

S017에서 MILK/SOY/WHEAT/TOMATO/BEEF를 정확하게 파싱해도 원재료의 사과/배는 처리하지 않는다.
**Declaration Coverage는 Salus Safety Coverage 또는 전체 알레르기 판정 Coverage가 아니다.**
NOT_FOUND는 SAFE나 NO_ALLERGEN을 의미하지 않는다.

### Reference review and corrections

A=Reference 전사 오류, B=Reference 주석/원문 혼입, C=Registry 누락,
D=Parser 버그, E=실제 미지원 라벨 표현으로 구분한다. 출처 버전 차이로 원인을 확정할 수 없으면
A로 단정하지 않고 별도 보류 문제로 남긴다.

| 제품 | 분류 / 발견 내용 | 근거와 처리 |
| --- | --- | --- |
| S004 햇반 | B: `별도 알레르기 함유 표시 없음(확인 라벨 기준)`은 작성자 관찰 설명 | 원본 H5와 [공개 표시사항](https://emile.emarteveryday.co.kr/product/ProductView?skuCode=8801007037783&strCode=4006) 검토. 명시적 없음 선언 없음. raw=null, NOT_FOUND |
| S010 제주삼다수 | B: `해당사항 없음(먹는샘물 라벨 기준)`을 제조사 선언으로 오인할 위험. 기존 fixture의 DECLARED_NONE도 근거 부족 | 원본 H11/검증 메모와 [공개 상품 표시사항](https://emile.emarteveryday.co.kr/product/ProductView?skuCode=8808244201045)에는 명시적 없음 선언이 확인되지 않음. raw=null, NOT_FOUND. 현재 이미지가 여러 규격 공통 상품설명인 점을 note에 기록 |
| S015 백설 하얀설탕 | B: [롯데마트 상품고시](https://lottemartzetta.com/products/OS8801007100838/details)의 `알레르기 정보 : 해당사항 없음`은 제조사 라벨 원문과 다름 | [추가 공개 라벨](https://images.emarteveryday.co.kr/images/app/webapps/evd_web2/share/SKU/mall/38/08/8801007100838_3.png)에서 GTIN 8801007100838, 원당 100%, 품목보고번호 19730190015-29 확인. 라벨에 별도 함유/명시적 없음 선언 없음. H16 값은 source에 보존, raw=null, NOT_FOUND |
| S016 코카-콜라 | B: 표시 없음 관찰 설명을 raw에 넣지 않음 | 원본 H17 및 [공개 표시사항](https://emile.emarteveryday.co.kr/product/ProductView?skuCode=8801094012403&strCode=4006) 검토. raw=null, NOT_FOUND. 같은시설 문구는 직접 함유 선언으로 승격하지 않음 |
| S020 혼합김 | B: 표시 없음 관찰 설명을 raw에 넣지 않음 | 원본 H21 및 [공개 라벨](https://images.emarteveryday.co.kr/images/product/8801047224617/OKZaYwtw5EOIxtcT.jpg)에서 별도 함유/명시적 없음 선언 없음. raw=null, NOT_FOUND |
| S008 초코파이 정 | 기존 fixture의 합성 자료를 제품 정답으로 사용할 수 없음(원본 Reference 전사 오류 아님) | Accepted_20!H9에는 달걀/밀/우유/대두/쇠고기/돼지고기 6종. S008-type의 4종을 실제 S008·GTIN 및 6종으로 교체 |

**S020 별도 보류 문제 — 출처/라벨 버전 불일치:** 현재 공개 이미지에는 재래김 48.4%,
들기름 9.7%, 올리브유 2.5%, 품목보고번호 19900415013158/19900415013160 및 같은시설 문구가 보인다.
Excel G21/K21/I21과 이미지 대체텍스트는 재래김 44.8%, 들기름 10.4%, 올리브유 5.2%,
품목보고번호 1990041501371/1990041501356 및 교차접촉 표시 없음으로 기록되어 있다.
2026-09-09 전사 오류인지 이후 이미지 교체인지 현재 근거만으로 확정할 수 없다.
두 자료 모두 별도 직접 함유 선언은 찾지 못해 NOT_FOUND를 유지하되, 실제 구매 라벨 버전 재확인이 필요하다.
이 단계에서는 원재료/교차접촉 값을 고치거나 해석하지 않았다.
S010/S016 역시 현재 이미지가 상품설명으로 제공되어 기존 대체텍스트와 표현 차이가 있으므로
원본의 과거 검증과 현재 실물 동일성을 혼동하지 않는다.

이 20종 declaration 범위에서 C(Registry 누락), D(Parser 버그), E(미지원 표현)는 발견되지 않았다.
Reference 정리 때문에 Parser가 테스트를 통과하도록 production 코드를 바꾸지 않았다.

### Validation commands

```sh
cd backend
mvn -Dtest=AllergenDeclarationParserTest,AllergenRegistryTest,DeclarationReferenceTest,DeclarationReferenceValidationTest test
mvn test
cd ..
git diff --check
```

콘솔과 `backend/target/declaration-metrics.txt`에 지표, 제품별 결과, 미지원 토큰 및 예외 상세를 저장한다.
원본 Excel 경로나 웹 접속 없이 저장소 fixture만으로 재실행할 수 있다.

2026-09-17 Java 17 / Maven 3.9.9 검증:

| 검증 | 결과 |
| --- | --- |
| 변경 전 `mvn test` | 537개, 실패 0, 오류 0, 기존 건너뜀 1 |
| 변경 후 `mvn test` | 561개, 실패 0, 오류 0, 기존 건너뜀 1 |
| 관련 테스트 | 96개 통과: 기존 Parser 60 + Registry 2 + Reference 24 + 집계기 회귀 10 |
| 순증 실행 사례 | 24개: fixture 확장 12 + Reference 보강 2 + 집계기 회귀 10 |
| `git diff --check` | 통과; 기존부터 untracked인 작업 파일 5개도 별도 공백 검사 통과 |

Production Java/resources 내용은 작업 시작 시점과 동일하다. commit / push / PR은 수행하지 않았다.

### Known limitations

- **not Physical Gold**: 현재 자료는 WEB_VERIFIED_STRICT Reference fixture / Gold candidate다.
  실물 동일 GTIN 라벨과 문자 단위 대조가 완료된 해당 라벨 버전만 향후 **GOLD_PHYSICAL**로 승격한다.
- **Declaration 지표의 범위**: 같은시설/혼입가능 문구는 위 Declaration 지표에 포함하지 않는다.
  교차접촉은 아래 2단계에서 별도 측정한다. 바다생물 일반 주의 문구는 지원하지 않는다.
- **1.5단계 당시 Ingredient 미평가**: 3단계 구조/Evidence 검증은 별도 문서에 기록한다.
- **no Safety Fact resolution yet**: Evidence Resolver, Regulatory Coverage Resolver와 최종 안전 판정은 없다.
- 20종에서 문법/미지원 토큰 오류가 0이라는 결과를 모든 라벨 표현의 지원으로 일반화하지 않는다.
- 웹 이미지/대체텍스트/상품고시는 변경되거나 다른 포장 버전일 수 있다. S020의 출처 차이는 위에 보류 기록했다.

## Cross-contact Parser

### 목적

2단계(2026-09-18)는 라벨의 같은 제조시설/혼입 가능 문구를 구조화한다.
`AllergenCrossContactParser`는 기존 `AllergenEvidence`, Registry, 명시적 하위 항목 ID를 재사용한다.
Declaration에서 `AllergenLabelTokens`로 괄호 depth 분리, 양끝 공백 제거, 괄호의 선택적
`포함` 및 explicitChildren 처리를 추출했다. Declaration 문법과 Evidence 생성은 그대로다.
기존 예외 메시지도 유지하여 Declaration 오류 분류의 호환성을 보존한다.

```java
crossContactParser.parse("우유를 사용한 제품과 같은 제조시설");
// CROSS_CONTACT_PRESENT, MILK, source=CROSS_CONTACT, type=CROSS_CONTACT, CERTAIN
crossContactParser.parse("조개류(굴, 전복, 홍합 포함) 혼입 가능");
// SHELLFISH, explicitChildren=[OYSTER, ABALONE, MUSSEL]
crossContactParser.parse(CrossContactInput.notFound());
// CROSS_CONTACT_NOT_FOUND, evidence=[]
crossContactParser.parse(CrossContactInput.unreadable("우유..."));
// UNREADABLE, rawText="우유...", evidence=[]
```

`CrossContactInput`은 READABLE / NOT_FOUND / UNREADABLE을 구분한다.
결과는 `CrossContactParseResult(state, rawText, normalizedText, evidence, unparsedTokens)`다.
빈 READABLE, 잘못된 괄호, 빈 token, 미지원 문장 형식은 예외다. 실패를 NOT_FOUND로 바꾸지 않는다.
원문과 matchedText의 내부 공백/개행은 보존하고 normalizedText만 공백을 정리한다.
Unknown token만 있는 문구도 PRESENT와 해당 unparsedTokens를 반환한다.

### 지원 문구 패턴

전체 입력이 다음 세 문법 중 하나와 일치해야 한다.

- 목록 + `을/를 사용한 제품과 같은 제조시설`, 또는 조사 없는 ` 사용한 제품과 같은 제조시설`
- 목록 + `와/과 같은 제조시설`
- 목록 + ` 혼입가능` 또는 ` 혼입 가능`

공백/개행, Unicode 공백, 조사 뒤 붙임을 처리한다. 첫 패턴에서 실패한
`사용하지 않은 제품과 같은 제조시설`을 짧은 목록 문법으로 오인하지 않는다.
전체 라벨, 작성자 설명, 부정문, 근거 없는 후속 문장/종결어 확장은 지원하지 않는다.
현재 Excel의 실제 10개 문구는 첫째와 셋째 문법이다. 짧은 둘째 문법과 공백 변형은
최소 요구 문법 단위 테스트로 검증하며 실제품 Accuracy의 분자/분모에는 넣지 않는다.

### Evidence 의미

모든 교차접촉 근거는 `source=CROSS_CONTACT`, `evidenceType=CROSS_CONTACT`,
`confidence=CERTAIN`, `path=[]`다.
CERTAIN은 **제조사가 교차접촉 가능성을 명시했다는 관찰의 확실성**이다.
실제 제품에 해당 알레르겐이 들어 있다는 확률이나 확정 함유를 뜻하지 않는다.
Declaration의 DIRECT_DECLARATION과 합치거나 서로 대체하지 않는다.

Registry의 직접 명칭만 정확히 일치시킨다. 파생어/부분 문자열/향은 승격하지 않는다.
2단계에서는 알류/난류를 UNRESOLVED_GROUP으로 보존했으나, **2.5단계에서 EGG_GROUP으로 해결**했다.
두 Parser 모두 `findDirectName`으로 계란/달걀(EGG), 메추리알(QUAIL_EGG),
알류/난류(EGG_GROUP)를 구분한다. 임시 `unresolvedGroupAliases`와 별도
`findSpecificDirectName` 조회는 제거했다. 레시피 Matcher의 프로필 별칭은 유지한다.

### S001-S020 결과

다음 지표 표는 **2단계 검증 이력**이다. 2.5단계의 미지원 해소 및 최신 지표는
아래 `Allergen Registry / Hierarchy` 섹션에 기록한다.

원본은 `docs/reference/Salus_Strict_Web_Reference_20_2026-09-09.xlsx`다.
사용자가 제공한 Downloads 파일을 바이트 변경 없이 이 경로로 복사했다.
SHA-256은 기존 Declaration fixture와 동일한
`ad5e41e3c8366dd9b45571186f3ad95683e93395a48bb4c639b9cb283213d8fb`다.
`backend/src/test/resources/allergens/cross-contact-reference.json`은
`Accepted_20!I2:I21`의 전사 원문, 출처 URL, ID/GTIN, 기대값을 보존한다.
작성자 설명은 note 및 originalCrossContact로 분리하고 raw에 넣지 않는다.
원본 Excel과 기존 declaration-reference.json은 수정하지 않았다.

| 측정값 | 결과 |
|---|---|
| 전체 / PRESENT / NOT_FOUND / UNREADABLE | 20 / 10 / 10 / 0 |
| Cross-contact Parsing Accuracy | **10 / 10 = 100.0%** |
| 미지원 token 없이 완전 정규화 | **8 / 10 = 80.0%** |
| 관찰 상태를 포함한 strict fixture 통과 | 20 / 20 |
| Cross-contact Coverage | **10 / 20 = 50.0%** |
| Label Evidence Coverage | **16 / 20 = 80.0%** |
| Declaration Regression | **15 / 15 = 100.0%**, 회귀 없음 |
| Unsupported Tokens | 총 2회, 고유 2개 |
| Parse Errors | 괄호 0 / 빈 token 0 / 미지원 형식 0 / 예상 밖 exception 0 |

Accuracy 분모는 READABLE이면서 기대 상태가 PRESENT인 10개다.
상태, allergen multiset(추가/누락/중복 포함), explicitChildren, 기대 unsupported token,
raw/normalized/matchedText, source/type/confidence/path가 모두 일치하고 예외가 없어야 통과한다.
예상 미지원 token을 정확히 보존한 부분 파싱도 strict 성공이므로 100%를 완전 정규화로 해석하지 않는다.
NOT_FOUND 10개는 Accuracy에서 제외하지만 모두 상태와 빈 Evidence를 검증한다.
오류가 발생한 READABLE 사례는 분모에서 빠지지 않는다.

Coverage는 실제 결과에서 CERTAIN CROSS_CONTACT 근거가 하나 이상 있는 제품 수 / 20이다.
Declaration도 실제 CERTAIN DIRECT_DECLARATION 근거를 기준으로 동일 ID/GTIN을 조인한다.

| 분포 | 수 / 비율 | IDs |
|---|---|---|
| Declaration-only | 6 / 20, 30.0% | S001, S002, S006, S009, S013, S014 |
| Cross-contact-only | 1 / 20, 5.0% | S016 |
| Both | 9 / 20, 45.0% | S003, S005, S007, S008, S011, S012, S017, S018, S019 |
| Neither | 4 / 20, 20.0% | S004, S010, S015, S020 |

Label Evidence Coverage는 위 세 유근거 그룹의 합집합이며 안전 Coverage가 아니다.
집계는 `CrossContactReferenceTest`에서 매번 재계산하고
`backend/target/cross-contact-reference-report.txt`에 제품별 결과·미지원 원문·오류를 기록한다.
`CrossContactReferenceValidationTest`는 누락/추가/하위 항목/근거 속성의 잘못된 결과,
오류 분모 유지, 미판독 제외와 ID 불일치도 검증한다.

### Known Issues

| 제품 | 분류 | 처리 |
|---|---|---|
| S005 | HIERARCHY_GAP → 2.5단계 해결 | 당시 unparsedTokens 보존, 현재 EGG_GROUP으로 구분 |
| S017 | REGISTRY_MISSING → 2.5단계 해결 | 당시 unparsedTokens 보존, 현재 QUAIL_EGG으로 구분 |
| S020 | REFERENCE_VERSION_CONFLICT | 위 1.5단계 출처 충돌 유지. raw=null, NOT_FOUND 및 note로 검증 가능한 문구 미확보를 표현 |
| S010 | 전사 근거 한계 | Excel의 '해당사항 없음'은 작성자 설명으로 취급. raw=null, NOT_FOUND |

2단계 당시 미지원 token 전체 원문(2.5단계에서도 원문은 그대로 유지):

- **알류 — S005**: `알류, 대두, 밀, 우유, 닭고기, 쇠고기를 사용한 제품과 같은 제조시설`
- **메추리알 — S017**: `계란, 새우, 돼지고기, 닭고기, 오징어, 조개류(굴, 전복, 홍합 포함), 메추리알을 사용한 제품과 같은 제조시설`

S020의 Excel/공개 이미지 어느 쪽도 최신 정답으로 선택하지 않았다.
NOT_FOUND 10개 중 S020은 출처 충돌 보류이며 확정된 문구 부재와 구분해 읽어야 한다.
별도 UNVERIFIED enum 대신 note/issue를 사용한다. 동일 GTIN의 포장 버전 확인은 후속 과제다.
이 자료는 WEB_VERIFIED_STRICT 전사 Reference이며 GOLD_PHYSICAL 검증이 아니다.

### Not Safety Decision

PRESENT는 명시 문구의 존재이고, NOT_FOUND/UNREADABLE 및 Evidence 부재는 안전을 뜻하지 않는다.
Parser는 SAFE/DANGER/CAUTION, AllergenFact, 사용자 알레르기 최종 판정을 반환하지 않는다.
10개 문구의 정확도 100%를 모든 한국 식품 라벨 문법 지원으로 일반화하지 않는다.

### 검증 및 다음 단계

2단계 검증 기록(2026-09-18, JDK 17 / Maven 3.9):

| 검증 | 결과 |
|---|---|
| 변경 전 Declaration 4개 클래스 | 96개 통과 |
| 관련 테스트 및 안전 guard | 전부 통과 |
| 전체 `mvn -q clean test` | **652개, 실패 0, 오류 0, 기존 skip 1** |
| 신규 실행 사례 | **91개**: Parser 47 + Reference 24 + 집계기 검증 20 |
| `git diff --check` 및 신규 파일 별도 공백 검사 | 통과 |
| 원본 보존 | Excel SHA-256 일치, declaration-reference.json 변경 전후 byte 동일 |

관련 테스트 명령(backend 디렉터리):

```bash
mvn -q -Dtest='AllergenCrossContactParserTest,CrossContactReferenceTest,CrossContactReferenceValidationTest,AllergenDeclarationParserTest,AllergenRegistryTest,DeclarationReferenceTest,DeclarationReferenceValidationTest,AllergenMatcherTest,AllergenRemovabilityTest,AllergenGapTest,ChatSafetyContextServiceTest,ChatServiceSafetyTest' test
mvn -q clean test
```

프론트엔드 빌드, 실제 DB/API/OCR, 실물 라벨 검증은 실행하지 않았다.

Ingredient Tree Parser, Evidence Resolver, AllergenFact, Regulatory Coverage Resolver,
OCR/식품안전나라 API, Product/Package/LabelVersion 전체 모델, 최종 Safety Decision,
프론트엔드/LLM/복용약/만성질환 변경은 구현하지 않았다. commit / push / PR 없음.
2단계 이후 계획이던 Synthetic lexical regression은 아래 2.5단계에서 수행했다.

## Allergen Registry / Hierarchy

2.5단계(2026-09-18)는 실제 Reference의 두 gap과 Ingredient Parser 전 어휘 정책을 다룬다.
별도 ontology나 새 matcher를 만들지 않고 기존 YAML/Dictionary/Registry/Alias를 확장했다.

### QUAIL_EGG

`메추리알 → DIRECT_NAME → QUAIL_EGG → CERTAIN`으로 보존한다.
S017에서 실제 관찰된 이름이며 EGG로 평탄화하지 않는다.
YAML `declarationAllergens`에 라벨 관찰 ID를 추가하고 `parent: EGG_GROUP`을 명시했다.

### EGG_GROUP

`알류/난류 → DIRECT_NAME → EGG_GROUP → CERTAIN`이다.
기존 EGG는 계란/달걀 라벨 ID로 유지하고 다음 최소 부모 관계만 저장한다.

```text
EGG_GROUP (알류/난류)
  ├─ EGG (계란/달걀, 기존 ID 유지)
  └─ QUAIL_EGG (메추리알)
```

`AllergenDictionary.registryParents()`와 `AllergenRegistry.parentOf(id)`는 읽기 전용
메타데이터다. 미등록 부모/자식과 순환 관계는 Registry 초기화 시 실패한다.
부모를 Evidence로 자동 추가하거나 자식을 `explicitChildren`에 자동 채우지 않는다.
예를 들어 `알류 혼입 가능`의 explicitChildren은 빈 목록이며,
`알류(계란, 메추리알 포함) 혼입 가능`에만 EGG/QUAIL_EGG를 명시적 자식으로 보존한다.

**의도한 변경:** Declaration의 합성 입력 `알류 함유`도 EGG에서 EGG_GROUP으로 바뀐다.
요청한 관찰 정보 보존을 위한 변경이며 실제 Declaration 15개 기대값/원문에는 변경이 없다.
기존 프로필 Matcher의 `aliases`/`derived`는 유지하므로 사용자 알레르기 판단을 변경하지 않는다.

### Longest Match

`aliasesLongestFirst()`는 긴 별칭 우선 정렬을 유지한다.
`findExactAliases(token)`는 호출자가 전달한 **하나의 전체 lexical unit**만 정확히 조회한다.
메밀/땅콩처럼 등록된 긴 명칭은 밀/콩으로 재해석하지 않는다.
완두콩/강낭콩/땅콩호박/굴비처럼 미등록인 긴 명칭은 미인식으로 남으며,
짧은 substring을 찾는 fallback을 하지 않는다. 이 전체 토큰 경계 규칙이 예외 표현을 보호한다.
별도의 식품별 blacklist나 `String.contains` 순회는 추가하지 않았다.
문장 분할이나 복합 원재료 경계 인식은 아직 구현하지 않았다.

### Alias Relation

`AllergenAlias.confidence()`는 기존 MatchConfidence enum을 사용한다.
조회 결과는 Alias/Relation/Allergen/Confidence이며 Ingredient Evidence가 아니다.

| 입력 | ID | Relation | Confidence |
|---|---|---|---|
| 우유 | MILK | DIRECT_NAME | CERTAIN |
| 탈지분유 | MILK | DERIVED_FROM | CERTAIN |
| 우유향 | MILK | LEXICAL_HINT | POSSIBLE |
| 대두 | SOY | DIRECT_NAME | CERTAIN |
| 콩기름, 대두유, 분리대두단백, 대두레시틴 | SOY | DERIVED_FROM | CERTAIN |
| 콩 | SOY | LEXICAL_HINT | POSSIBLE |

`콩`을 삭제하지 않았으며 Registry에서는 낮은 확신의 후보로만 조회한다.
우유향/콩/파생어는 두 공식 라벨 Parser의 DIRECT_NAME 필터를 통과하지 못하므로
함유 또는 교차접촉 Evidence로 승격되지 않는다.
탈지분유/분리대두단백/대두레시틴은 YAML `registryDerived`로 추가하여
기존 프로필 Matcher의 substring 규칙을 확장하지 않았다.
동일한 전체 별칭에 여러 관계가 있으면 모두 보존한다.
예: 굴은 OYSTER의 DIRECT_NAME과 SHELLFISH의 기존 DERIVED_FROM을 보존하며,
공식 표시 Parser는 직접 명칭 OYSTER만 선택한다.

### Synthetic Lexical Regression

`backend/src/test/resources/allergens/allergen-lexical-regression.json`은
scope=SYNTHETIC_LEXICAL_REGRESSION, ID=SYN-*인 **25개 합성 사례**다.
S001~S020 실제 Reference 및 Accuracy/Coverage 집계에 포함하지 않는다.
`AllergenLexicalRegressionTest`는 기대 ID/Relation/Confidence와 금지 ID를 검증하고,
동일 토큰을 두 라벨 Parser에 전달해 힌트/파생어가 직접 명칭으로 승격되지 않는지도 확인한다.
별도 7개 긴 토큰/짧은 별칭 비교, 다중 관계, 문장 입력 거부, 읽기 전용 조회도 검증한다.
`AllergenHierarchyTest`는 종/그룹 구분, 부모 관계, 명시적 자식 및 잘못된 계층 로드를 검증한다.

보고서: `backend/target/allergen-lexical-regression-report.txt`.
실제 Reference 보고서: `backend/target/cross-contact-reference-report.txt`.

### False-positive cases

| 토큰 | Registry 기대 결과 | 금지 오탐 |
|---|---|---|
| 메밀 | BUCKWHEAT | WHEAT |
| 땅콩 | PEANUT | SOY |
| 밀크초콜릿 | 미인식 | WHEAT; 문자열만으로 MILK 확정도 하지 않음 |
| 굴비 | 미인식 | OYSTER / SHELLFISH |
| 완두콩 / 강낭콩 | 미인식 | SOY |
| 땅콩호박 | 미인식 | PEANUT / SOY |
| 우유향 | MILK / LEXICAL_HINT / POSSIBLE | 직접 함유 확정 |
| 탈지분유 | MILK / DERIVED_FROM / CERTAIN | DIRECT_NAME으로 관계 변경 |

유화제/향료/기타가공품/혼합제제는 단독으로 어떤 알레르겐에도 연결하지 않는다.
미인식은 안전 또는 비알레르겐 판정을 의미하지 않는다.

### 실제 Reference 재평가

S005의 `알류`와 S017의 `메추리알`은 원본 Excel I열의 실제 전사 이름이다.
2.5단계 요구사항과 위 Registry 정의를 근거로 fixture의 기대 ID/matchedText/unsupported만 갱신하고,
기존 gap의 해결 경위를 note에 기록했다. source.issue는 해결됐으므로 null로 바꿨다.
20개 raw/normalized/원본 전사/GTIN/출처 URL과 S020 전체 항목은 변경 전후 동일하다.
Declaration fixture 역시 byte 동일하다. Excel 수정, 제품 ID별 production 예외는 없다.

| 2.5단계 실측 지표 | 결과 |
|---|---|
| Synthetic lexical fixture | **25 / 25**, 실패 0 |
| Declaration Parsing Accuracy | **15 / 15 = 100.0%** |
| Cross-contact structural Accuracy | **10 / 10 = 100.0%** |
| Fully normalized Cross-contact | **8 / 10 → 10 / 10 = 100.0%** |
| Cross-contact Unsupported Tokens | **2회/2종 → 0회/0종**, 남은 token 없음 |
| Label Evidence Coverage | **16 / 20 = 80.0%**, 변경 없음 |
| Cross-contact Parse Errors | 괄호/빈 token/형식/예상 밖 exception 모두 0 |
| 전체 `mvn -q clean test` | **695개, 실패 0, 오류 0, 기존 skip 1** |
| `git diff --check` 및 작업 파일 별도 공백 검사 | 통과 |

새 테스트 클래스 실행 사례는 lexical 35개(25 fixture + 추가 검증 10개),
hierarchy 11개다. 기존 미지원 목록의 알류/난류/메추리알 3개는 새 지원 검증으로 옮겨
전체 실행 사례는 652개에서 695개로 순증 43개다.
관련 테스트를 먼저 실행했으며 초기 합성 테스트의 빈 금지 목록 AssertJ 호출 오류는
동일한 검증 의미의 noneMatch로 수정했다. lexical 35개 재실행 및 최종 전체 테스트가 통과했다.

실행 명령(backend):

```bash
mvn -q -Dtest='AllergenLexicalRegressionTest,AllergenHierarchyTest,AllergenDeclarationParserTest,AllergenRegistryTest,DeclarationReferenceTest,DeclarationReferenceValidationTest,AllergenCrossContactParserTest,CrossContactReferenceTest,CrossContactReferenceValidationTest,AllergenMatcherTest,AllergenRemovabilityTest,AllergenGapTest,ChatSafetyContextServiceTest,ChatServiceSafetyTest' test
mvn -q -Dtest='AllergenLexicalRegressionTest' test
mvn -q clean test
```

### Limitations

- 이 회귀 테스트는 **Registry의 lexical unit 인식** 범위를 검증한다.
  기존 레시피/프로필 `AllergenMatcher`의 모든 substring 오탐을 해결했다는 주장이 아니다.
  해당 사용자 판정 경로는 변경하지 않았으며 기존 안전 guard 테스트로 회귀를 확인한다.
- 2.5단계 당시 Ingredient Parser/Ingredient Evidence는 미구현이었으며 3단계에서 추가했다.
  Evidence Resolver/AllergenFact, Regulatory Coverage Resolver, User Profile Matching/Safety Decision은 여전히 미구현이다.
- OCR/API, 프론트엔드/LLM, DB 리팩터링 및 실물 라벨 검증은 이번 범위에 없다.
- S020 REFERENCE_VERSION_CONFLICT는 계속 보류한다.
- 2.5단계 이후 Ingredient Tree Parser는 [3단계 문서](allergen-evidence-pipeline.md) 참조. commit / push / PR 없음.
