# v4 case coverage change

- 수정한 기존 case ID: `multi-med-food-condition-and-api-failed`
- 변경 후 case ID: `multi-med-food-condition-and-api-failed` (ID 유지)
- 기존 의미: 두 가상 약물의 `FOOD_INTAKE_CONDITION`과 `API_FAILED`를 함께 집계하고, 두 약물별 결과와 부분 실패 요약을 검증한다.
- 유지한 assertion: decision, source, exposable, per-drug status 2개, medication result count 2, medication summary, original recipe 상태를 그대로 유지했다.
- 추가한 assertion: 같은 session의 후속 수정 turn에서 두 약물 이름·개수·약물별 결과·부분 실패·비-SAFE 상태·safety context와 원본/source evidence 보존을 검증한다. 약 중단, 용량 변경, 임의 복용 일정도 금지한다.
- case 수 유지 근거: 기존 case를 삭제하거나 추가하지 않고 하나를 다중 turn으로 강화해 총 70개를 유지했다.
- 고유 coverage 유지: 기존 최초 요청이 먼저 실행되며 최초·후속 turn 각각에서 기존 exact per-drug status, medication summary, overall status, decision, exposable 기대값을 검사하므로 기존 복수 약물 부분 실패 검증을 약화하지 않았다.
- production 변경: 없음. 외부 Runner와 fixture 복사본만 변경했다.
