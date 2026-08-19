import React from 'react';

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
