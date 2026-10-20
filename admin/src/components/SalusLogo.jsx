import React from 'react';

/**
 * SALUS 로고(마크 이미지 + 글자)입니다.
 * size: 마크 크기(px), showWordmark: 글자 표시 여부, suffix: 글자 뒤 표시(예: "ADMIN")
 * 스크린 리더에는 전체를 이미지 하나(aria-label)로 읽게 하고, 내부 요소는 aria-hidden으로 숨깁니다.
 */
export default function SalusLogo({ size = 40, showWordmark = true, suffix = '' }) {
    const label = suffix ? `SALUS ${suffix}` : 'SALUS';

    return (
        <span className="salus-logo" role="img" aria-label={label}>
            <img
                aria-hidden="true"
                src="/salus-logo-mark.png"
                width={size}
                height={size}
                alt=""
                className="salus-logo-mark"
            />
            {showWordmark && (
                <span aria-hidden="true" className="salus-logo-wordmark">
                    S<span className="salus-logo-a">A</span>LUS
                    {suffix && <span className="salus-logo-suffix">{suffix}</span>}
                </span>
            )}
        </span>
    );
}
