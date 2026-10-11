/*
 * Morphe patch "Announcement notifications" for Müllkalender (de.sector27.muellkalender).
 *
 * - setStreets(streets): called by the patched NewsService.requestNews, passes the street ids
 *   of the app to the native background check (NewsNotifications.java).
 * - Adds the "Sondermeldungen" section to the "Extra Einstellungen (patched)" page (morphe-settings.js):
 *   switch, hints for notification permission and battery optimization, test button.
 */
(function () {
    'use strict';

    function bridge() {
        return window.morpheNewsBridge || null;
    }

    function status() {
        try {
            return JSON.parse(bridge().getStatus());
        } catch (e) {
            return {};
        }
    }

    function setStreets(streets) {
        try {
            var native = bridge();
            if (!native || !streets || typeof streets.length !== 'number') return;
            var ids = [];
            for (var i = 0; i < streets.length; i++) {
                if (streets[i] && streets[i].id != null) ids.push(String(streets[i].id));
            }
            native.setStreets(ids.join(','));
        } catch (e) {
            // Never break the app's own news request.
        }
    }

    function formatTime(millis) {
        if (!millis) return 'noch nie';
        var date = new Date(millis);
        var pad = function (n) {
            return (n < 10 ? '0' : '') + n;
        };
        return pad(date.getDate()) + '.' + pad(date.getMonth() + 1) + '. ' +
            pad(date.getHours()) + ':' + pad(date.getMinutes()) + ' Uhr';
    }

    // region Settings section

    var HINT_STYLE = 'margin: 12px 16px; padding: 12px; border-radius: 8px; ' +
        'background: rgba(235, 68, 90, 0.1); color: inherit; font-size: 0.9em;';
    var BUTTON_STYLE = 'display: inline-block; margin-top: 8px; padding: 6px 12px; border-radius: 6px; ' +
        'background: var(--ion-color-primary, #4d8e00); color: #fff; font-weight: 500; cursor: pointer;';
    var LINK_STYLE = 'display: block; margin: 0 16px 12px; color: var(--ion-color-primary, #4d8e00); ' +
        'font-weight: 500; cursor: pointer;';

    function renderSection(container) {
        var ui = window.morpheSettings;
        var native = bridge();

        if (!native) {
            container.innerHTML = ui.sectionHtml('Sondermeldungen', [], 'Benachrichtigungen sind nicht verfügbar ' +
                '(der native Teil des Patches fehlt).');
            return;
        }

        container.innerHTML = ui.sectionHtml('Sondermeldungen', [
            ui.toggleItemHtml('announcement-notifications', 'Push-Benachrichtigung bei Sondermeldungen',
                'Prüft alle 30 Minuten, ob es eine neue Meldung zu Ausfällen oder Verzögerungen gibt, ' +
                'und benachrichtigt einmalig.'),
            '<div class="morphe-news-hints"></div>',
            '<div class="morphe-news-info"></div>'
        ], 'Die Meldungen sind dieselben wie beim roten Ausrufezeichen auf der Startseite.');

        var hints = container.querySelector('.morphe-news-hints');
        var info = container.querySelector('.morphe-news-info');

        function update() {
            var s = status();
            var html = '';
            if (s.enabled && !s.notificationsAllowed) {
                html += '<div style="' + HINT_STYLE + '">Benachrichtigungen für den Müllkalender sind nicht erlaubt.' +
                    '<br><span style="' + BUTTON_STYLE + '" data-action="permission">Benachrichtigungen erlauben</span></div>';
            }
            if (s.enabled && !s.batteryUnrestricted) {
                html += '<div style="' + HINT_STYLE + '">Damit die App auch im Hintergrund zuverlässig prüfen kann, ' +
                    'muss die Akku-Optimierung für den Müllkalender ausgeschaltet sein: in den App-Einstellungen ' +
                    'unter <b>Akkunutzung der App</b> → <b>Hintergrundnutzung zulassen</b> → <b>Uneingeschränkt</b> ' +
                    'wählen (je nach Handy auch <b>Akku</b> → <b>Nicht eingeschränkt</b>).' +
                    '<br><span style="' + BUTTON_STYLE + '" data-action="battery">App-Einstellungen öffnen</span></div>';
            }
            hints.innerHTML = html;

            info.innerHTML = s.enabled
                ? '<div style="margin: 0 16px 8px; font-size: 0.85em; opacity: 0.7;">Zuletzt geprüft: ' +
                  ui.escapeHtml(formatTime(s.lastCheck)) +
                  (s.lastResult ? '<br>' + ui.escapeHtml(s.lastResult) : '') + '</div>' +
                  '<span style="' + LINK_STYLE + '" data-action="test">Jetzt prüfen und Testbenachrichtigung senden</span>'
                : '';
        }

        ui.bindToggle(container, 'announcement-notifications', !!status().enabled, function (enabled) {
            native.setEnabled(enabled);
            if (enabled && !status().notificationsAllowed) native.requestNotificationPermission();
            update();
        });

        container.addEventListener('click', function (event) {
            var target = event.target.closest && event.target.closest('[data-action]');
            if (!target) return;
            var action = target.getAttribute('data-action');
            if (action === 'permission') native.requestNotificationPermission();
            else if (action === 'battery') native.openAppSettings();
            else if (action === 'test') {
                target.textContent = 'Prüfe …';
                // Synchronous network request in the bridge thread, let the text render first.
                setTimeout(function () {
                    native.testNow();
                    update();
                }, 50);
            }
        });

        // Refresh after returning from the system settings or the permission dialog (the dialog
        // does not trigger any page event), only re-render if something changed.
        var lastHints = null;
        var refresh = window.setInterval(function () {
            if (!document.body.contains(container)) {
                window.clearInterval(refresh);
                return;
            }
            var s = status();
            var key = [s.enabled, s.notificationsAllowed, s.batteryUnrestricted, s.lastCheck].join();
            if (key !== lastHints) {
                lastHints = key;
                update();
            }
        }, 1000);

        update();
    }

    (window.morpheSettingsSections = window.morpheSettingsSections || []).push({
        order: 20,
        render: renderSection
    });

    // endregion

    Object.defineProperty(window, 'morpheNews', {
        value: Object.freeze({
            setStreets: setStreets
        }),
        enumerable: false
    });
})();
