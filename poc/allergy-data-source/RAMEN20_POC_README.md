# Salus 식품안전나라 라면 20종 데이터 소스 PoC

이 디렉터리는 Salus production 코드와 분리된 데이터 연결성 실험이다. 구현 목표는
`바코드 → C005 품목보고번호 → I1250 기본정보 → C002 원재료` 연결 가능성과
C005 최신성을 실제 수치로 확인하는 것이다.

## 안전 범위

- 알레르겐 자동 판정, Ingredient Parser, OCR, LLM 호출을 하지 않는다.
- 제품명 검색은 C005 바코드 미조회 시 후보 발견에만 사용하며 자동 매칭하지 않는다.
- `api_observable_allergens`는 비워 두고 안전성 지표는 `NOT_EVALUATED`로 유지한다.
- API Key는 `FOODSAFETY_API_KEY` 환경변수에서만 읽고 파일과 raw artifact에 저장하지 않는다.
- raw artifact에는 요청 필터와 응답 본문만 기록하고 인증키가 포함된 요청 URL은 기록하지 않는다.

## 실행

```bash
python3 --version
echo "${FOODSAFETY_API_KEY:+SET}"
python3 poc/allergy-data-source/foodsafety_ramen_poc.py
python3 poc/allergy-data-source/score_ramen_poc.py
```

키가 설정되지 않은 경우에만 다음과 같이 현재 실행 환경에 설정한다.

```bash
export FOODSAFETY_API_KEY="..."
```

## 파일

- `ramen20_manifest.csv`: 20개 고정 SKU와 외부 판매처 바코드 근거. C005 응답에서 바코드를 역산하지 않는다.
- `ramen20_gold_labels.csv`: 실제 라벨 수기 검토용 빈 Gold Dataset. 현재는 모두 `NOT_EVALUATED`다.
- `foodsafety_ramen_poc.py`: C005 exact barcode 조회와 후속 I1250/C002 조회 실행기.
- `score_ramen_poc.py`: 데이터 존재성 관련 세 지표만 계산한다.
- `api_results.csv`: SKU별 요약 결과.
- `metrics.json`: 핵심 세 지표와 평가 보류 항목.
- `raw/C005`, `raw/I1250`, `raw/C002`: 인증정보를 제외한 가능한 범위의 원본 JSON.

## 공식 API 계약

- C005: https://foodsafetykorea.go.kr/api/openApiInfo.do?svc_no=C005
- I1250: https://www.foodsafetykorea.go.kr/api/openApiInfo.do?svc_no=I1250
- C002: https://www.foodsafetykorea.go.kr/api/openApiInfo.do?svc_no=C002

C005 공식 메타정보에는 2018년 이후 최신화 중단이 명시되어 있다. 따라서 최근 제품의
바코드 미조회는 PoC가 측정하려는 결과이며, 제품명 후보가 발견되어도 품목보고번호로
자동 승격하지 않는다.

## 해석 제한

C002 원재료가 존재한다는 사실만으로 알레르기 판정이 가능하다고 결론 내릴 수 없다.
복합원재료 상세, 별도 알레르기 표시, 교차오염 주의문구가 빠질 수 있으므로 실제 제품
라벨 Gold Dataset과 비교하기 전에는 Primary Evidence 아키텍처를 확정하지 않는다.
