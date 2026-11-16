'use strict';
// Progressive enhancement: Java renders the views and permissions; this only switches panels.
const views = [...document.querySelectorAll('[data-view]')];
const navigation = [...document.querySelectorAll('[data-nav]')];
const label = document.querySelector('#current-view-label');
history.scrollRestoration = 'manual';

function showView(hash, focus = false) {
    let targetId;
    try { targetId = decodeURIComponent(hash.replace(/^#/, '')); }
    catch { targetId = ''; }
    const target = document.getElementById(targetId);
    const view = target?.closest('[data-view]')
        || views.find(item => item.dataset.view === document.body.dataset.initialView)
        || views[0];
    if (!view) return;
    for (const item of views) {
        item.hidden = item !== view;
        item.classList.toggle('is-active', item === view);
    }
    for (const link of navigation) {
        if (link.dataset.nav === view.dataset.view) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    }
    label.textContent = navigation.find(link => link.dataset.nav === view.dataset.view)?.textContent.trim() || 'Home';
    if (target && target !== view) {
        for (let node = target.parentElement; node && node !== view; node = node.parentElement) {
            if (node.tagName === 'DETAILS') node.open = true;
        }
    }
    requestAnimationFrame(() => {
        if (target && target !== view) target.scrollIntoView({ block: 'start' });
        else window.scrollTo({ top: 0, behavior: 'instant' });
        if (focus) {
            const heading = view.querySelector('h1, h2');
            if (heading) {
                heading.setAttribute('tabindex', '-1');
                heading.focus({ preventScroll: true });
            }
        }
    });
}

document.addEventListener('click', event => {
    const link = event.target.closest('a[href^="#"]');
    if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    if (!document.getElementById(link.hash.slice(1))?.closest('[data-view]')) return;
    event.preventDefault();
    history.pushState(null, '', link.hash);
    showView(link.hash, true);
});
window.addEventListener('popstate', () => showView(location.hash));
window.addEventListener('hashchange', () => showView(location.hash));
window.addEventListener('pageshow', () => showView(location.hash));
// Retain the current view when Java refreshes a filtered report or catalogue.
for (const form of document.querySelectorAll('form[method="get"]')) {
    form.addEventListener('submit', () => {
        const view = form.closest('[data-view]');
        const action = new URL(form.action, location.href);
        if (view && !action.hash) {
            action.hash = view.id;
            form.action = action.href;
        }
    });
}
showView(location.hash);
