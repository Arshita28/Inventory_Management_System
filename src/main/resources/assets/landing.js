'use strict';
const video = document.querySelector('#intro');
const content = document.querySelector('.landing-content');
const playButton = document.querySelector('.play-intro');
let finished = false;

// Retain the decoded final frame and its matching still. Never loop or seek back.
function finishIntro() {
    if (finished) return;
    finished = true;
    video.pause();
    content.inert = false;
    playButton.hidden = true;
    document.body.classList.remove('intro-pending');
    document.body.classList.add('is-complete');
}
video.addEventListener('ended', finishIntro, { once: true });
video.addEventListener('error', finishIntro, { once: true });
video.querySelector('source').addEventListener('error', finishIntro, { once: true });

async function playIntro() {
    playButton.hidden = true;
    try {
        video.muted = true;
        await video.play();
    } catch {
        // Autoplay restrictions require a real user gesture; do not fake completion.
        if (!finished) playButton.hidden = false;
    }
}
playButton.addEventListener('click', playIntro);
if (document.body.classList.contains('auth-open') || matchMedia('(prefers-reduced-motion: reduce)').matches) finishIntro();
else playIntro();

for (const link of document.querySelectorAll('[data-open-login]')) {
    link.addEventListener('click', event => {
        event.preventDefault();
        document.body.classList.add('auth-open');
        history.replaceState(null, '', '/?login=1#login');
        document.querySelector('#login').scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'center' });
        document.querySelector('#login-username').focus({ preventScroll: true });
    });
}
const passwordToggle = document.querySelector('.password-toggle');
if (passwordToggle) {
    passwordToggle.hidden = false;
    passwordToggle.addEventListener('click', () => {
        const input = document.querySelector('#login-password');
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        passwordToggle.textContent = show ? 'Hide' : 'Show';
        passwordToggle.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
    });
}

// Persona selection fills the ordinary form; authentication still happens on Login.
const personaCards = [...document.querySelectorAll('[data-persona-role]')];
const personaStatus = document.querySelector('.persona-status');
let selectedPersona = null;
function clearPersonaSelection() {
    for (const card of personaCards) card.setAttribute('aria-pressed', 'false');
    selectedPersona = null;
}
for (const input of document.querySelectorAll('.login-roles input')) {
    input.addEventListener('change', () => {
        if (selectedPersona) {
            document.querySelector('#login-username').value = '';
            document.querySelector('#login-password').value = '';
        }
        clearPersonaSelection();
        for (const card of personaCards) card.hidden = card.dataset.personaRole !== input.value;
        if (personaStatus) personaStatus.textContent = 'Select a persona to fill your login details.';
    });
}
for (const card of personaCards) {
    card.addEventListener('click', () => {
        clearPersonaSelection();
        selectedPersona = card;
        card.setAttribute('aria-pressed', 'true');
        document.querySelector(`.login-roles input[value="${card.dataset.personaRole}"]`).checked = true;
        document.querySelector('#login-username').value = card.dataset.personaUsername;
        const password = document.querySelector('#login-password');
        password.value = card.dataset.personaPassword;
        password.type = 'password';
        passwordToggle.textContent = 'Show';
        passwordToggle.setAttribute('aria-label', 'Show password');
        personaStatus.textContent = `${card.dataset.personaName} selected. Click Login to continue.`;
    });
}
for (const input of document.querySelectorAll('#login-username, #login-password')) {
    input.addEventListener('input', () => {
        clearPersonaSelection();
        if (personaStatus) personaStatus.textContent = 'Login details edited.';
    });
}
