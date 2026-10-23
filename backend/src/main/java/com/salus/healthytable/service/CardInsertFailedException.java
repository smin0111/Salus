package com.salus.healthytable.service;

/**
 * 레시피 카드 INSERT(flush) 단계에서 DB 제약 위반이 난 경우에만 던지는 예외입니다.
 *
 * <p>비검사 예외라 {@link ChatReplyWriter}의 트랜잭션은 전체 롤백되고(메시지도 저장되지 않음),
 * 호출자는 트랜잭션 밖에서 이 예외만 잡아 새 트랜잭션으로 메시지만 저장합니다.
 * 메시지 INSERT 실패·커밋 실패·원인 불명 오류에는 쓰지 않습니다.
 */
public class CardInsertFailedException extends RuntimeException {
    public CardInsertFailedException(Throwable cause) {
        super("레시피 카드 저장 실패", cause);
    }
}
