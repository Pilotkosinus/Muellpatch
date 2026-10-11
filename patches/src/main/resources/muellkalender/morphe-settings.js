/*
 * Shared "Extra Einstellungen (patched)" page of the Müllkalender Morphe patches.
 *
 * Adds the entry below "Müllkalender bewerten" in the settings and opens a page that shows
 * the sections registered by the individual patches. Patch scripts register sections with
 *
 *   (window.morpheSettingsSections = window.morpheSettingsSections || []).push({
 *       order: 10,
 *       render: function (container) { ... }   // fills the <section> element
 *   });
 *
 * so the load order of the scripts does not matter.
 */
(function () {
    'use strict';

    var SETTINGS_PAGE = 'app-settings';
    var ANCHOR_ITEM_TEXT = 'Müllkalender bewerten';
    var ENTRY_MARKER = 'data-morphe-patch-entry';
    var PAGE_MARKER = 'data-morphe-patch-page';
    var REPOSITORY_URL = 'https://github.com/Pilotkosinus/Muellpatch';
    // GitHub mark (Octicons "mark-github", MIT license).
    var GITHUB_ICON =
        '<svg width="36" height="36" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">' +
        '<path d="M8 0c4.42 0 8 3.58 8 8a8.013 8.013 0 0 1-5.45 7.59c-.4.08-.55-.17-.55-.38 0-.27.01-1.13.01-2.2 ' +
        '0-.75-.25-1.23-.54-1.48 1.78-.2 3.65-.88 3.65-3.95 0-.88-.31-1.59-.82-2.15.08-.2.36-1.02-.08-2.12 0 0-.67-.22' +
        '-2.2.82-.64-.18-1.32-.27-2-.27-.68 0-1.36.09-2 .27-1.53-1.03-2.2-.82-2.2-.82-.44 1.1-.16 1.92-.08 2.12-.51.56' +
        '-.82 1.28-.82 2.15 0 3.06 1.86 3.75 3.64 3.95-.23.2-.44.55-.51 1.07-.46.21-1.61.55-2.33-.66-.15-.24-.6-.83' +
        '-1.23-.82-.67.01-.27.38.01.53.34.19.73.9.82 1.13.16.45.68 1.31 2.69.94 0 .67.01 1.3.01 1.49 0 .21-.15.45-.55' +
        '.38A7.995 7.995 0 0 1 0 8c0-4.42 3.58-8 8-8Z"></path></svg>';

    var sections = window.morpheSettingsSections = window.morpheSettingsSections || [];

    // region Helpers for sections

    function escapeHtml(text) {
        return String(text).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    /** Section header, card with toggle items and description, in the style of the app. */
    function sectionHtml(header, items, description) {
        return '<div class="card-list-header"><h1>' + escapeHtml(header) + '</h1></div>' +
            '<ion-card class="list"><ion-list>' + items.join('') + '</ion-list></ion-card>' +
            (description ? '<div class="card-description">' + description + '</div>' : '');
    }

    function toggleItemHtml(id, title, meta) {
        return '<ion-item class="ion-no-padding morphe-toggle-item" lines="none" data-morphe-toggle="' + id + '">' +
            '<ion-label class="ion-text-wrap"><div class="label-wrapper">' +
            '<span class="title">' + escapeHtml(title) + '</span>' +
            (meta ? '<span class="meta">' + escapeHtml(meta) + '</span>' : '') +
            '</div></ion-label>' +
            '<ion-toggle slot="end" aria-label="' + escapeHtml(title) + '" style="padding-inline-end: 16px"></ion-toggle>' +
            '</ion-item>';
    }

    /** Wires the toggle item [id] in [container]; tapping the text toggles as well. */
    function bindToggle(container, id, checked, onChange) {
        var item = container.querySelector('[data-morphe-toggle="' + id + '"]');
        var toggle = item.querySelector('ion-toggle');
        toggle.checked = checked;
        toggle.addEventListener('ionChange', function (event) {
            onChange(!!event.detail.checked);
        });
        item.addEventListener('click', function (event) {
            if (event.target.closest && event.target.closest('ion-toggle')) return;
            toggle.checked = !toggle.checked;
            onChange(toggle.checked);
        });
        return toggle;
    }

    // endregion

    // region Settings entry

    function findAnchorItem() {
        // The home page has a "Müllkalender bewerten" entry as well, only use the settings page.
        var items = document.querySelectorAll(SETTINGS_PAGE + ' ion-list > ion-item');
        for (var i = 0; i < items.length; i++) {
            if (items[i].textContent.indexOf(ANCHOR_ITEM_TEXT) !== -1) return items[i];
        }
        return null;
    }

    function injectSettingsEntry() {
        var anchor = findAnchorItem();
        if (!anchor || anchor.parentNode.querySelector('[' + ENTRY_MARKER + ']')) return;

        // Clone the neighbor entry so layout, arrow icon, ripple and scoped styles are identical.
        var entry = anchor.cloneNode(true);
        entry.setAttribute(ENTRY_MARKER, '');

        var title = entry.querySelector('.title');
        var meta = entry.querySelector('.meta');
        if (title) {
            title.textContent = 'Extra Einstellungen (patched)';
            if (meta) meta.textContent = 'Zusätzliche Optionen des Morphe-Patches';
        } else {
            var label = entry.querySelector('ion-label');
            if (label) label.textContent = 'Extra Einstellungen (patched)';
        }

        entry.addEventListener('click', openSettingsPage);
        anchor.parentNode.insertBefore(entry, anchor.nextSibling);
    }

    var injectScheduled = false;

    function scheduleInject() {
        if (injectScheduled) return;
        injectScheduled = true;
        window.requestAnimationFrame(function () {
            injectScheduled = false;
            injectSettingsEntry();
        });
    }

    // endregion

    // region Settings page

    var PAGE_HTML =
        '<ion-header>' +
        '  <ion-toolbar>' +
        '    <ion-buttons slot="end"><span class="close-button"></span></ion-buttons>' +
        '    <ion-title>Extra Einstellungen</ion-title>' +
        '  </ion-toolbar>' +
        '</ion-header>' +
        '<ion-content class="ion-padding lightgray-content">' +
        '  <div class="morphe-sections"></div>' +
        '  <div class="card-description">' +
        '    Diese Einstellungen stammen aus einem Morphe-Patch und sind nicht Teil der Original-App.' +
        '  </div>' +
        '  <div style="display: flex; justify-content: center; padding: 8px 0 16px;">' +
        '    <a href="' + REPOSITORY_URL + '" target="_blank" rel="noopener" aria-label="Patch auf GitHub"' +
        '       title="Patch auf GitHub" style="display: inline-flex; padding: 8px; color: #24292f;">' +
        GITHUB_ICON +
        '    </a>' +
        '  </div>' +
        '</ion-content>';

    function closeButtonText() {
        var original = document.querySelector('ion-header .close-button');
        var text = original && original.textContent.trim();
        return text || 'Schließen';
    }

    function renderSections(page) {
        var target = page.querySelector('.morphe-sections');
        sections.slice().sort(function (a, b) {
            return (a.order || 0) - (b.order || 0);
        }).forEach(function (section) {
            var container = document.createElement('section');
            target.appendChild(container);
            try {
                section.render(container);
            } catch (e) {
                console.error('Morphe patch: could not render settings section', e);
                container.remove();
            }
        });
    }

    function openSettingsPage() {
        var existing = document.querySelectorAll('ion-modal[' + PAGE_MARKER + ']');
        for (var i = 0; i < existing.length; i++) {
            if (existing[i].classList.contains('overlay-hidden')) existing[i].remove();
            else return; // Already open.
        }

        var modal = document.createElement('ion-modal');
        modal.setAttribute(PAGE_MARKER, '');

        var page = document.createElement('div');
        page.className = 'ion-page';
        page.innerHTML = PAGE_HTML;
        modal.appendChild(page);

        var close = page.querySelector('.close-button');
        close.textContent = closeButtonText();
        close.addEventListener('click', function () {
            modal.dismiss();
        });

        renderSections(page);

        modal.addEventListener('ionModalDidDismiss', function () {
            // Ionic moves inline modals back to their original parent after this event,
            // so remove the element in the next task.
            setTimeout(function () {
                modal.remove();
            }, 0);
        });

        (document.querySelector('ion-app') || document.body).appendChild(modal);
        var ready = modal.componentOnReady ? modal.componentOnReady() : Promise.resolve();
        ready.then(function () {
            return modal.present();
        });
    }

    // endregion

    Object.defineProperty(window, 'morpheSettings', {
        value: Object.freeze({
            escapeHtml: escapeHtml,
            sectionHtml: sectionHtml,
            toggleItemHtml: toggleItemHtml,
            bindToggle: bindToggle
        }),
        enumerable: false
    });

    new MutationObserver(scheduleInject).observe(document.documentElement, {
        childList: true,
        subtree: true
    });
})();
