/*
 * Morphe patch "Calendar waste type" for Müllkalender (de.sector27.muellkalender).
 *
 * - calendarTitle(reminder): title for exported calendar events, including the waste type(s) and bin color(s)
 *   of the pickup day. Returns reminder.title unchanged if the switch is off.
 * - tag(reminder, pickupDay, street): called by the patched ReminderDatesFactory.buildByOption
 *   to remember which PickupDay/street a reminder belongs to (non-enumerable, never serialized).
 * - replaceEvents(calendar, street, option, addEvents): called by the patched
 *   CalendarService.addByStreet, deletes the events already exported for the street
 *   (by the unpatched or patched app) before the export runs, so no event is duplicated.
 * - Adds the "Kalenderexport" section to the "Extra Einstellungen (patched)" page (morphe-settings.js).
 */
(function () {
    'use strict';

    var PREF_KEY = 'morphe_patch_calendar_waste_type';
    var PICKUP_DAY_KEY = '__morphePickupDay';
    var STREET_KEY = '__morpheStreet';
    var TYPE_SEPARATOR = ' – ';

    // region Preference

    function isEnabled() {
        try {
            var value = window.localStorage.getItem(PREF_KEY);
            return value === null ? true : value === 'true';
        } catch (e) {
            return true;
        }
    }

    function setEnabled(enabled) {
        try {
            window.localStorage.setItem(PREF_KEY, enabled ? 'true' : 'false');
        } catch (e) {
            // Ignore, the switch then only applies until the app is closed.
        }
    }

    // endregion

    // region Calendar title

    function hide(target, key, value) {
        Object.defineProperty(target, key, {
            value: value,
            enumerable: false,
            configurable: true,
            writable: true
        });
    }

    function tag(reminder, pickupDay, street) {
        try {
            if (reminder && typeof reminder === 'object') {
                hide(reminder, PICKUP_DAY_KEY, pickupDay);
                hide(reminder, STREET_KEY, street);
            }
        } catch (e) {
            // Never break the original reminder creation.
        }
        return reminder;
    }

    // Tour.color values (the app ships bin images for black, blue, brown and yellow).
    var COLOR_EMOJIS = {
        black: '⚫',
        blue: '🔵',
        brown: '🟤',
        yellow: '🟡',
        green: '🟢',
        red: '🔴',
        orange: '🟠',
        purple: '🟣',
        white: '⚪'
    };

    function colorEmoji(color) {
        if (typeof color !== 'string') return '';
        return COLOR_EMOJIS[color.trim().toLowerCase()] || '';
    }

    /**
     * Waste types of a PickupDay as provided by the app (Tour.label + Tour.color emoji),
     * without duplicates, e.g. "Wertstoffe 🟡".
     */
    function wasteTypes(pickupDay) {
        var tours = pickupDay && pickupDay.tours;
        if (!tours || typeof tours.length !== 'number') return [];

        var seen = {};
        var types = [];
        for (var i = 0; i < tours.length; i++) {
            var label = tours[i] && tours[i].label;
            if (typeof label !== 'string') continue;
            label = label.trim();
            var key = label.toLowerCase();
            if (!label || seen[key]) continue;
            seen[key] = true;
            types.push({label: label, emoji: colorEmoji(tours[i].color)});
        }
        // Same ordering as the app's own PickupDay.names getter (alphabetical).
        return types.sort(function (a, b) {
            return a.label.localeCompare(b.label, 'de');
        }).map(function (type) {
            return type.emoji ? type.label + ' ' + type.emoji : type.label;
        });
    }

    function calendarTitle(reminder) {
        var original = reminder.title;
        if (!isEnabled()) return original;

        try {
            var types = wasteTypes(reminder[PICKUP_DAY_KEY]);
            if (types.length === 0 || typeof original !== 'string') return original;

            var insertion = TYPE_SEPARATOR + types.join(', ');
            var street = reminder[STREET_KEY];
            // Original title: "<LABELS.PICKUP> (<street.name>)".
            var suffix = street && street.name != null ? ' (' + street.name + ')' : null;

            if (suffix && original.length > suffix.length &&
                original.slice(original.length - suffix.length) === suffix) {
                return original.slice(0, original.length - suffix.length) + insertion + suffix;
            }
            return original + insertion;
        } catch (e) {
            return original;
        }
    }

    // endregion

    // region Replace existing events

    // Part of the description of every event the app exports (unpatched and patched),
    // the same marker the app itself uses to find its events (CalendarEvent.belongsToUs).
    var APP_EVENT_MARKER = 'die Müllkalender-App - ein Service der sector27 GmbH';
    var DAY_MS = 24 * 60 * 60 * 1000;

    function calendarPlugin() {
        return window.plugins && window.plugins.calendar;
    }

    function startOfToday() {
        var date = new Date();
        date.setHours(0, 0, 0, 0);
        return date;
    }

    function toDate(value) {
        if (value == null) return null;
        var date = new Date(typeof value.valueOf === 'function' ? value.valueOf() : value);
        return isNaN(date.getTime()) ? null : date;
    }

    function isAppEventOfStreet(event, street, calendarId) {
        if (!event || typeof event.message !== 'string' || event.message.indexOf(APP_EVENT_MARKER) === -1) {
            return false;
        }
        // Same check as CalendarEvent.belongsToStreet.
        if (typeof event.location !== 'string' || event.location.indexOf(street.name) === -1) return false;
        // Only the calendar that is exported to.
        return event.calendar == null || calendarId == null || String(event.calendar) === String(calendarId);
    }

    function findEvents(plugin, from, to, calendarId) {
        return new Promise(function (resolve, reject) {
            plugin.findEventWithOptions(null, null, null, from, to,
                calendarId != null ? {calendarId: calendarId} : {}, resolve, reject);
        });
    }

    function deleteEvent(plugin, event) {
        return new Promise(function (resolve) {
            // Errors are ignored, a remaining old event is better than an aborted export.
            plugin.deleteEventById(event.id, toDate(event.startDate), resolve, resolve);
        });
    }

    /**
     * Deletes the events the app (unpatched or patched) already exported for this street to
     * this calendar from today until the end of the new export, so they are not duplicated.
     */
    function deleteExistingEvents(calendar, street, option) {
        var plugin = calendarPlugin();
        if (!plugin || !street || street.name == null) return Promise.resolve();

        var from = startOfToday();
        var until = toDate(option && option.endTime);
        var to = new Date((until ? until.getTime() : from.getTime()) + 2 * DAY_MS);
        var calendarId = calendar && calendar.id != null ? calendar.id : null;

        return findEvents(plugin, from, to, calendarId).then(function (events) {
            var existing = (events || []).filter(function (event) {
                return isAppEventOfStreet(event, street, calendarId);
            });
            // One after another, the calendar provider does not like parallel writes.
            return existing.reduce(function (previous, event) {
                return previous.then(function () {
                    return deleteEvent(plugin, event);
                });
            }, Promise.resolve());
        });
    }

    /**
     * Wraps CalendarService.addByStreet: first deletes the existing events of the street,
     * then runs the original export ([addEvents] returns the original Observable).
     */
    function replaceEvents(calendar, street, option, addEvents) {
        var original = addEvents();
        var Observable = original && original.constructor;
        if (typeof Observable !== 'function' || typeof original.subscribe !== 'function') return original;

        return new Observable(function (subscriber) {
            var subscription = null;
            var cancelled = false;

            deleteExistingEvents(calendar, street, option).catch(function (e) {
                console.error('Morphe patch: could not delete existing calendar events', e);
            }).then(function () {
                if (!cancelled) subscription = original.subscribe(subscriber);
            });

            return function () {
                cancelled = true;
                if (subscription) subscription.unsubscribe();
            };
        });
    }

    // endregion

    // region Settings section

    (window.morpheSettingsSections = window.morpheSettingsSections || []).push({
        order: 10,
        render: function (container) {
            var ui = window.morpheSettings;
            container.innerHTML = ui.sectionHtml('Kalenderexport', [
                ui.toggleItemHtml('calendar-waste-type', 'Tonnenart im Kalender anzeigen',
                    'Fügt beim Kalenderexport die Müllart(en) mit Tonnenfarbe zum Titel des Termins hinzu.')
            ], 'Beim Export werden bereits vorhandene Müllkalender-Termine der Straße im gewählten Kalender ' +
                '(ab heute) ersetzt, damit keine Termine doppelt sind.');
            ui.bindToggle(container, 'calendar-waste-type', isEnabled(), setEnabled);
        }
    });

    // endregion

    Object.defineProperty(window, 'morpheWasteType', {
        value: Object.freeze({
            tag: tag,
            calendarTitle: calendarTitle,
            replaceEvents: replaceEvents,
            isEnabled: isEnabled
        }),
        enumerable: false
    });
})();
