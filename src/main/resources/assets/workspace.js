'use strict';
// Progressive enhancement: Java renders the views and permissions; this only switches panels.
const views = [...document.querySelectorAll('[data-view]')];
const navigation = [...document.querySelectorAll('[data-nav]')];
const label = document.querySelector('#current-view-label');
history.scrollRestoration = 'manual';

const sidebar = document.querySelector('#workspace-sidebar');
const sidebarToggle = document.querySelector('#sidebar-toggle');
const sidebarPreference = 'voltedge.sidebar.hidden';
function setSidebarHidden(hidden) {
    if (!sidebar || !sidebarToggle) return;
    sidebar.hidden = hidden;
    document.body.classList.toggle('sidebar-hidden', hidden);
    sidebarToggle.setAttribute('aria-expanded', String(!hidden));
    const action = hidden ? 'Show workspace sidebar' : 'Hide workspace sidebar';
    sidebarToggle.setAttribute('aria-label', action);
    sidebarToggle.title = action;
}
try { setSidebarHidden(localStorage.getItem(sidebarPreference) === 'true'); }
catch { setSidebarHidden(false); }
sidebarToggle?.addEventListener('click', () => {
    setSidebarHidden(!sidebar.hidden);
    try { localStorage.setItem(sidebarPreference, String(sidebar.hidden)); }
    catch { /* Navigation still toggles when browser storage is unavailable. */ }
});

function showView(hash, focus = false) {
    let targetId;
    try { targetId = decodeURIComponent(hash.replace(/^#/, '')); }
    catch { targetId = ''; }
    if (targetId === 'home') targetId = 'dashboard';
    const target = document.getElementById(targetId);
    const view = target?.closest('[data-view]')
        || views.find(item => item.dataset.view === document.body.dataset.initialView)
        || views[0];
    if (!view) return;
    for (const item of views) {
        item.hidden = item !== view;
        item.classList.toggle('is-active', item === view);
    }
    const selectedLink = navigation.find(link => link.dataset.nav === targetId)
        || navigation.find(link => link.dataset.nav === view.dataset.view);
    for (const link of navigation) {
        if (link === selectedLink) {
            link.setAttribute('aria-current', 'page');
            const group = link.closest('.nav-group');
            if (group) group.open = true;
        }
        else link.removeAttribute('aria-current');
    }
    label.textContent = selectedLink?.textContent.trim() || 'Dashboard';
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

const saleLines = document.querySelector('#sale-lines');
let nextLine = Math.max(0, ...[...(saleLines?.querySelectorAll('[name^="sku"]') || [])].map(field => Number(field.name.slice(3)))) + 1;
document.querySelector('#add-sale-line')?.addEventListener('click', () => {
    if (nextLine >= 20) return;
    const row = saleLines.querySelector('.sale-line').cloneNode(true);
    row.querySelectorAll('[name]').forEach(field => {
        field.name = field.name.replace(/\d+$/, String(nextLine));
        if (field.tagName === 'INPUT') field.value = field.name.startsWith('qty') ? '1' : '';
    });
    nextLine++;
    saleLines.append(row);
});
saleLines?.addEventListener('click', event => {
    if (event.target.closest('.remove-line') && saleLines.children.length > 1) event.target.closest('.sale-line').remove();
});
document.querySelector('#print-invoice')?.addEventListener('click', () => window.print());
function updateSalePreview() {
    if (!saleLines) return;
    let subtotal = 0;
    saleLines.querySelectorAll('.sale-line').forEach(row => {
        const select = row.querySelector('select');
        const quantity = Number(row.querySelector('[name^="qty"]').value);
        subtotal += Math.round(Number(select.selectedOptions[0]?.dataset.price || 0) * 100) * quantity;
    });
    const discount = Math.round(Number(document.querySelector('[name="discount"]').value || 0) * 100);
    const formatter = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
    document.querySelector('#sale-preview').textContent = `Subtotal ${formatter.format(subtotal / 100)} · Discount ${formatter.format(discount / 100)} · Total ${formatter.format((subtotal - discount) / 100)}`;
}
saleLines?.closest('form').addEventListener('input', updateSalePreview);
saleLines?.closest('form').addEventListener('change', updateSalePreview);
saleLines?.closest('form').addEventListener('click', () => queueMicrotask(updateSalePreview));
updateSalePreview();

// Block incompatible characters while typing or pasting; Java validates every submission too.
function validateNumericField(field) {
    const kind = field.dataset.valueType;
    if (!kind) return;
    field.setCustomValidity('');
    if (!field.value) return;
    const label = field.dataset.fieldLabel || 'This field';
    const pattern = kind === 'integer' ? /^[0-9]+$/ : /^[0-9]+(?:\.[0-9]{0,2})?$/;
    if (!pattern.test(field.value)) {
        field.setCustomValidity(kind === 'integer' ? `${label}: enter whole numbers only.` : `${label}: use no more than 2 decimal places.`);
    } else if (field.min && Number(field.value) < Number(field.min)) {
        field.setCustomValidity(`${label}: minimum ${field.min}.`);
    } else if (field.max && Number(field.value) > Number(field.max)) {
        field.setCustomValidity(`${label}: maximum ${Number(field.max).toLocaleString('en-IN')}.`);
    }
}
document.addEventListener('keydown', event => {
    const field = event.target.closest('input[data-value-type]');
    if (!field || event.ctrlKey || event.metaKey || event.altKey || event.key.length !== 1) return;
    if (field.dataset.valueType === 'integer' ? !/[0-9]/.test(event.key) : !/[0-9.]/.test(event.key)) event.preventDefault();
});
document.addEventListener('beforeinput', event => {
    const field = event.target.closest('input[data-value-type]');
    if (!field || !event.data || event.isComposing) return;
    if (field.dataset.valueType === 'integer' ? /[^0-9]/.test(event.data) : /[^0-9.]/.test(event.data)) event.preventDefault();
});
document.addEventListener('paste', event => {
    const field = event.target.closest('input[data-value-type]');
    if (!field) return;
    const pasted = event.clipboardData?.getData('text') || '';
    const pattern = field.dataset.valueType === 'integer' ? /^[0-9]+$/ : /^[0-9]+(?:\.[0-9]{0,2})?$/;
    if (!pattern.test(pasted.trim())) {
        event.preventDefault();
        field.setCustomValidity(field.dataset.valueType === 'integer' ? 'Paste a whole number without letters or decimal points.' : 'Paste an amount with up to 2 decimal places.');
        field.reportValidity();
    }
});
document.addEventListener('input', event => {
    if (event.target.matches('input[data-value-type]')) validateNumericField(event.target);
});
document.addEventListener('submit', event => {
    const fields = [...event.target.querySelectorAll('input[data-value-type]')];
    fields.forEach(validateNumericField);
    const invalid = fields.find(field => !field.checkValidity());
    if (invalid) { event.preventDefault(); invalid.reportValidity(); }
});
document.querySelectorAll('input[data-value-type]').forEach(validateNumericField);
