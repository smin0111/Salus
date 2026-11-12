import React, { useEffect, useRef, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import './landingPage.web.css';

const CHAPTERS = [
    { label: '재료 말하기', title: <>먼저, 가진 재료를<br />말해보세요.</>, description: '“두부랑 양배추가 있는데, 간단하게 저녁을 먹고 싶어.” 검색어를 고민할 필요 없이, 평소 말하듯 시작하면 돼요.', detail: '등록한 건강 정보와 식단 제한을 추천에 참고해요.' },
    { label: '한 끼 고르기', title: <>먹고 싶은<br />한 끼를 골라요.</>, description: '재료와 조리 시간, 조리 순서를 살펴보고 오늘의 식사를 골라보세요. 더 궁금한 건 같은 대화에서 이어서 물어볼 수 있어요.', detail: '원재료 표시와 주의 안내도 함께 확인해 주세요.' },
    { label: '식사 기록', title: <>먹은 것을 적으면,<br />나의 식사가 보여요.</>, description: '아침, 점심, 저녁. 한 끼씩 남긴 기록을 모아 오늘을 돌아보고, 주간·월간 보기로 식사의 흐름을 살펴보세요.', detail: '채팅에서 고른 식사도 기록으로 이어갈 수 있어요.' },
    { label: '내 레시피', title: <>다시 먹고 싶은 건,<br />내 것으로 남겨요.</>, description: '마음에 든 레시피는 저장하고, 내 입맛에 맞게 고쳐보세요. 직접 쓴 레시피도 한곳에 모을 수 있어요.', detail: '공유하고 싶을 때는 피드로 나눌 수 있어요.' },
];

function QuestionExample() {
    return <div className="sl-question-example">
        <p className="sl-micro">새 채팅</p>
        <h3>식사에 대해<br />무엇이 궁금하세요?</h3>
        <div className="sl-question-composer"><p>두부랑 양배추가 있는데,<br />간단하게 저녁을 먹고 싶어.<span className="sl-type-caret" aria-hidden="true" /></p><span className="sl-example-send" aria-hidden="true">↑</span></div>
        <p className="sl-question-helper">오늘 가진 재료부터 이야기해 보세요.</p>
    </div>;
}

function StoryExample({ step }) {
    if (step === 0) return <QuestionExample />;
    if (step === 1) return <ChatExample />;
    if (step === 2) return <RecordExample />;
    return <RecipesExample />;
}

function Arrow({ down = false }) {
    return <span aria-hidden="true" className={down ? 'sl-arrow sl-arrow-down' : 'sl-arrow'}>{down ? '↓' : '↗'}</span>;
}

function ChatExample({ hero = false }) {
    return <div className={`sl-chat-example${hero ? ' sl-chat-hero' : ''}`}>
        <div className="sl-question">두부랑 양배추가 있는데,<br />간단하게 저녁을 먹고 싶어.</div>
        <div className="sl-answer">
            <span className="sl-small-wordmark">salus</span>
            <p>두부 채소볶음은 어때요?<br />팬 하나로 준비할 수 있어요.</p>
            <div className="sl-recipe-preview">
                <span className="sl-micro">오늘의 제안</span>
                <h3>두부 채소볶음</h3>
                <div className="sl-recipe-meta"><span>15분</span><span>1인분</span></div>
                <div className="sl-ingredient-line">두부 <span>·</span> 양배추 <span>·</span> 간장</div>
                <p className="sl-recipe-note">알레르기 성분과 원재료는 직접 확인해 주세요.</p>
            </div>
        </div>
        {!hero && <div className="sl-composer-preview" aria-hidden="true"><span>추가로 물어보세요.</span><span>↑</span></div>}
    </div>;
}

function RecordExample() {
    return <div className="sl-record-example">
        <div className="sl-example-title"><h3>식사 기록</h3><span>오늘</span></div>
        <div className="sl-week" aria-label="월요일부터 일요일까지의 기록 예시">{'월화수목금토일'.split('').map((day, i) => <div key={day} className={i === 2 ? 'is-today' : ''}><span>{day}</span><b>{i + 2}</b><i className={i < 3 ? 'has-record' : ''} /></div>)}</div>
        <p className="sl-record-date">한 끼씩, 차곡차곡.</p>
        {[['아침', '달걀과 토스트'], ['점심', '현미밥과 채소'], ['저녁', '두부 채소볶음']].map(([meal, name]) => <div className="sl-meal-row" key={meal}><span>{meal}</span><div><strong>{name}</strong><small>기록한 식사</small></div><span aria-hidden="true">✓</span></div>)}
        <p className="sl-example-footnote">오늘 · 주간 · 월간으로 돌아보세요.</p>
    </div>;
}

function RecipesExample() {
    return <div className="sl-recipes-example">
        <div className="sl-example-title"><h3>내 레시피</h3><span>나만의 모음</span></div>
        <p className="sl-example-intro">다음에 또 먹고 싶은 것들.</p>
        {[['두부 채소볶음', '채팅에서 저장', '간장은 조금 덜 넣기.'], ['버섯 들깨탕', '직접 작성', '들깨가루는 마지막에.'], ['토마토 달걀볶음', '직접 작성', '잘 익은 토마토로.']].map(([name, origin, memo], i) => <div className="sl-saved-row" key={name}><span className="sl-saved-index">0{i + 1}</span><div><small>{origin}</small><h4>{name}</h4><p>{memo}</p></div><span aria-hidden="true">↗</span></div>)}
        <p className="sl-example-footnote">저장하고, 고치고, 다시 꺼내보세요.</p>
    </div>;
}

export default function LandingPageScreen({ onNavigate }) {
    const { isLoggedIn } = useAuth();
    const rootRef = useRef(null);
    const stepsRef = useRef([]);
    const [activeStep, setActiveStep] = useState(0);
    const [reduceMotion, setReduceMotion] = useState(() => typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
    const [motionPaused, setMotionPaused] = useState(false);
    const motionReduced = reduceMotion || motionPaused;

    useEffect(() => {
        const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
        if (!media) return undefined;
        const update = () => setReduceMotion(media.matches);
        update();
        media.addEventListener?.('change', update);
        return () => media.removeEventListener?.('change', update);
    }, []);

    useEffect(() => {
        const root = rootRef.current;
        if (!root) return undefined;
        let frame = 0;
        const update = () => {
            frame = 0;
            const focus = root.getBoundingClientRect().top + root.clientHeight * (window.innerWidth <= 760 ? 0.78 : 0.72);
            let next = 0;
            stepsRef.current.forEach((step, i) => { if (step && step.querySelector('.sl-chapter-copy').getBoundingClientRect().top <= focus) next = i; });
            setActiveStep(previous => previous === next ? previous : next);
            const total = root.scrollHeight - root.clientHeight;
            root.style.setProperty('--sl-progress', total > 0 ? String(root.scrollTop / total) : '0');
        };
        const schedule = () => { if (!frame) frame = requestAnimationFrame(update); };
        root.addEventListener('scroll', schedule, { passive: true });
        window.addEventListener('resize', schedule);
        update();
        let observer;
        if (!motionReduced && 'IntersectionObserver' in window) {
            observer = new IntersectionObserver(entries => {
                entries.forEach(entry => {
                    if (entry.isIntersecting) {
                        entry.target.dataset.revealed = 'true';
                        observer.unobserve(entry.target);
                    }
                });
            }, { root, rootMargin: '0px 0px -32px 0px', threshold: 0.08 });
            root.querySelectorAll('[data-reveal]').forEach(element => {
                // Content remains visible until its observer is installed, including unsupported browsers.
                observer.observe(element);
            });
            root.dataset.motion = 'on';
        }
        return () => {
            root.removeEventListener('scroll', schedule);
            window.removeEventListener('resize', schedule);
            if (frame) cancelAnimationFrame(frame);
            observer?.disconnect();
            delete root.dataset.motion;
        };
    }, [motionReduced]);

    const scrollTo = (id, chapter = false) => {
        const root = rootRef.current;
        const target = document.getElementById(id);
        if (!root || !target) return;
        const destination = chapter ? target.querySelector('.sl-chapter-copy') || target : target;
        const offset = chapter ? root.clientHeight * (window.innerWidth <= 760 ? 0.78 : 0.72) - 4 : 100;
        root.scrollTo({ top: destination.getBoundingClientRect().top - root.getBoundingClientRect().top + root.scrollTop - offset, behavior: motionReduced ? 'auto' : 'smooth' });
    };
    const start = () => onNavigate('chat');

    return <div ref={rootRef} className="salus-landing" data-reduced-motion={motionReduced ? 'true' : 'false'}>
        <a className="sl-skip" href="#sl-main" onClick={event => { event.preventDefault(); document.getElementById('sl-main')?.focus(); }}>본문으로 건너뛰기</a>
        <header className="sl-header">
            <button className="sl-wordmark" aria-label="Salus 소개 처음으로" onClick={() => rootRef.current?.scrollTo({ top: 0, behavior: motionReduced ? 'auto' : 'smooth' })}>salus</button>
            <nav className="sl-navigation" aria-label="서비스 소개"><button onClick={() => scrollTo('sl-story')}>이용 방법</button><button onClick={() => scrollTo('sl-health')}>건강 정보</button><button onClick={() => scrollTo('sl-faq')}>궁금한 점</button></nav>
            <div className="sl-header-actions"><button className="sl-login" onClick={() => onNavigate(isLoggedIn ? 'account-settings' : 'login')}>{isLoggedIn ? '내 계정' : '로그인'}</button><button className="sl-button sl-button-small" onClick={start}>시작하기 <Arrow /></button></div>
            <div className="sl-reading-progress" aria-hidden="true" />
        </header>

        <main id="sl-main" tabIndex={-1}>
            <section className="sl-hero sl-wrap" aria-labelledby="sl-hero-title">
                <div className="sl-hero-copy">
                    <p className="sl-overline">나의 식사를 알아가는 곳, 살루스</p>
                    <h1 id="sl-hero-title">오늘 뭘 먹을지,<br /><span>나에게서</span><br className="sl-desktop-break" /> 시작해요.</h1>
                    <p className="sl-hero-description">먹을 수 있는 재료, 평소의 식습관, 오늘의 한 끼.<br />흩어져 있던 식사 이야기를 한곳에 모아요.</p>
                    <div className="sl-hero-actions"><button className="sl-button" onClick={start}>채팅으로 시작하기 <Arrow /></button><button className="sl-text-button" onClick={() => scrollTo('sl-story')}>어떻게 쓰나요 <Arrow down /></button></div>
                </div>
                <div className="sl-hero-note">
                    <div className="sl-note-caption"><span>한 끼의 시작은 이런 대화에서.</span><span>화면 예시</span></div>
                    <ChatExample hero />
                    <div className="sl-note-bottom"><span>거창한 계획보다, 오늘 먹고 싶은 것부터.</span><span aria-hidden="true">↙</span></div>
                </div>
            </section>

            <section className="sl-introduction sl-wrap" data-reveal>
                <span className="sl-section-marker">한 끼를 따라가 볼까요</span>
                <h2>냉장고에 있던 두부가<br />나의 레시피가 되기까지.</h2>
                <p>대화로 고르고, 먹은 것을 기록하고, 내 입맛대로 남기고.<br />살루스에서 한 끼가 이어지는 과정을 만나보세요.</p>
            </section>

            <section id="sl-story" className="sl-story sl-wrap" aria-label="살루스 이용 흐름">
                <div className="sl-story-layout">
                    <div className="sl-story-display">
                        <div className="sl-example-tabs" aria-label="예시 화면 선택">{CHAPTERS.map((chapter, i) => <button key={chapter.label} aria-current={activeStep === i ? 'step' : undefined} onClick={() => scrollTo(`sl-step-${i}`, true)}><span>0{i + 1}</span>{chapter.label}</button>)}</div>
                        <div className="sl-example-window" role="region" aria-label={`서비스 화면 예시: ${CHAPTERS[activeStep].label}`}>
                            <div className="sl-window-bar"><span className="sl-small-wordmark">salus</span><span>서비스 화면 예시</span></div>
                            <div className="sl-window-navigation" aria-hidden="true">{['채팅', '식사 기록', '내 레시피'].map((label, i) => <span key={label} className={(activeStep < 2 ? 0 : activeStep - 1) === i ? 'is-active' : ''}>{label}</span>)}</div>
                            <div className="sl-example-panel" key={activeStep}><StoryExample step={activeStep} /></div>
                        </div>
                        <p className="sl-demo-caption">설명을 위한 예시이며, 실제 추천과 기록이 아닙니다.</p>
                    </div>
                    <div className="sl-story-steps">{CHAPTERS.map((chapter, i) => <article key={chapter.label} id={`sl-step-${i}`} ref={node => { stepsRef.current[i] = node; }} className={`sl-chapter${activeStep === i ? ' is-active' : ''}`}>
                        <div className="sl-chapter-copy" data-reveal><p className="sl-chapter-label"><span>0{i + 1}</span>{chapter.label}</p><h2>{chapter.title}</h2><p className="sl-chapter-description">{chapter.description}</p><p className="sl-chapter-detail">{chapter.detail}</p></div>
                        <div className="sl-mobile-example" role="region" aria-label={`${chapter.label} 화면 예시`}><div className="sl-window-bar"><span className="sl-small-wordmark">salus</span><span>서비스 화면 예시</span></div><div className="sl-mobile-panel"><StoryExample step={i} /></div><p className="sl-mobile-caption">설명을 위한 예시입니다.</p></div>
                    </article>)}</div>
                </div>
            </section>

            <section id="sl-health" className="sl-health">
                <div className="sl-wrap sl-health-layout" data-reveal>
                    <div><span className="sl-section-marker">나를 위한 기준</span><h2>좋은 식사의 기준은<br />사람마다 다르니까.</h2></div>
                    <div className="sl-health-copy"><p>피해야 하는 식재료가 있나요?<br />지키고 싶은 식사 습관은요?</p><p>알레르기와 식단 제한, 건강 목표를 등록해 두세요. 매번 처음부터 설명하지 않아도 식사를 추천할 때 참고할 수 있어요.</p><button className="sl-text-button" onClick={() => onNavigate(isLoggedIn ? 'health' : 'login')}>내 건강 정보 입력하기 <Arrow /></button><p className="sl-health-notice">살루스는 음식 선택을 돕는 정보 서비스예요. 의료 진단이나 치료를 대신하지 않으며, 알레르기 성분과 제품의 원재료 표시는 직접 확인해 주세요.</p></div>
                </div>
            </section>

            <section id="sl-faq" className="sl-faq sl-wrap" data-reveal>
                <div><span className="sl-section-marker">시작하기 전에</span><h2>궁금한 점을<br />모았어요.</h2></div>
                <div className="sl-faq-list">
                    <details><summary>어떤 서비스인가요?<span aria-hidden="true">+</span></summary><p>채팅으로 식사를 추천받고, 먹은 음식을 기록하고, 레시피를 저장하는 개인 식단·건강 기록 서비스입니다. 건강 정보와 검진 수치도 내 계정에서 관리할 수 있어요. 채팅의 답변과 레시피는 AI가 생성하거나 정리한 정보로, 틀릴 수 있어요.</p></details>
                    <details><summary>로그인이 꼭 필요한가요?<span aria-hidden="true">+</span></summary><p>로그인 없이 채팅을 둘러볼 수 있어요. 지난 대화를 이어가거나 식사 기록·내 레시피·건강 정보를 저장하려면 로그인해 주세요.</p></details>
                    <details><summary>알레르기가 있어도 사용할 수 있나요?<span aria-hidden="true">+</span></summary><p>등록한 알레르기를 추천에 참고하지만, 특정 음식이 안전하다고 보장하지는 않습니다. 가공식품의 원재료와 교차 접촉 가능성을 직접 확인하고, 필요한 경우 의료진과 상의해 주세요.</p></details>
                    <details><summary>내 기록이 다른 사람에게 보이나요?<span aria-hidden="true">+</span></summary><p>식사 기록과 건강 정보, 내 레시피는 내 계정에서 관리합니다. 레시피는 직접 피드에 공유하는 경우 다른 사용자에게 공개됩니다. 자세한 사항은 <button className="sl-inline-link" onClick={() => onNavigate('privacy')}>개인정보 처리방침</button>에서 확인해 주세요.</p></details>
                    <details><summary>PLUS는 어떻게 이용하나요?<span aria-hidden="true">+</span></summary><p>PLUS 이용권의 가격과 기간, 이용 조건은 <button className="sl-inline-link" onClick={() => onNavigate('upgrade')}>PLUS 안내 화면</button>에서 확인할 수 있어요.</p></details>
                </div>
            </section>

            <section className="sl-ending sl-wrap" data-reveal><p>오늘의 한 끼부터.</p><h2>잘 먹는 일도,<br /><span>조금씩 나답게.</span></h2><button className="sl-button" onClick={start}>살루스 열기 <Arrow /></button></section>
        </main>
        <footer className="sl-footer sl-wrap"><div><span className="sl-footer-wordmark">salus</span><p>매일의 식사를 알아가는 작은 습관.</p></div><div className="sl-footer-links"><button onClick={() => onNavigate('terms')}>서비스 이용약관</button><button onClick={() => onNavigate('privacy')}>개인정보 처리방침</button><button aria-pressed={motionReduced} disabled={reduceMotion} onClick={() => setMotionPaused(value => !value)}>움직임 줄이기{motionReduced ? ' 켜짐' : ''}</button><span>© {new Date().getFullYear()} Salus</span></div></footer>
    </div>;
}
