package de.muellpatch.extension.muellkalender;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.UserManager;
import android.provider.Settings;
import android.text.Html;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Morphe patch "Announcement notifications" for Müllkalender.
 *
 * Checks the app's announcement endpoint ("Sondermeldungen", the red "!" on the home page)
 * every 30 minutes in the background and shows a notification once for every new announcement.
 *
 * Hooks (see AnnouncementNotificationsPatch.kt):
 * - MessageHandler.<init>                  -> {@link #attach(WebView)}: JS interface + alarm
 * - TimedNotificationPublisher.onReceive   -> {@link #onAlarm}: the app's existing (not exported)
 *                                             receiver also receives our alarm, so the manifest
 *                                             does not need to be changed.
 * - LocalNotificationRestoreReceiver.onReceive (boot) -> {@link #onBoot}: alarms do not survive reboots.
 */
@SuppressWarnings("unused")
public final class NewsNotifications {
    private static final String TAG = "MorpheNews";

    private static final String NEWS_URL = "https://muellkalender-v2.sector27.de/api/v1/news";
    private static final String ALARM_RECEIVER =
            "com.capacitorjs.plugins.localnotifications.TimedNotificationPublisher";
    private static final String ACTION_CHECK = "de.muellpatch.muellkalender.CHECK_NEWS";
    private static final long INTERVAL_MS = 30 * 60 * 1000L;

    private static final String CHANNEL_ID = "morphe_announcements";
    private static final int NOTIFICATION_ID = 0x6D_6E01;
    private static final int ALARM_REQUEST_CODE = 0x6D_6E02;
    private static final int PERMISSION_REQUEST_CODE = 0x6D_6E03;

    private static final String PREFS = "morphe_announcement_notifications";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STREETS = "streets";
    private static final String KEY_LAST_SEEN = "last_seen";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_LAST_RESULT = "last_result";

    private NewsNotifications() {
    }

    // region Hooks

    /** Called by the patched Capacitor MessageHandler constructor, before the page is loaded. */
    public static void attach(WebView webView) {
        try {
            webView.addJavascriptInterface(new JsBridge(webView), "morpheNewsBridge");
            Context context = webView.getContext().getApplicationContext();
            if (isEnabled(context)) scheduleNext(context);
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    /**
     * Called at the start of TimedNotificationPublisher.onReceive.
     *
     * @return true if the intent was our alarm and the original receiver must not handle it.
     */
    public static boolean onAlarm(BroadcastReceiver receiver, Context context, Intent intent) {
        if (intent == null || !ACTION_CHECK.equals(intent.getAction())) return false;

        final Context app = context.getApplicationContext();
        if (!isEnabled(app)) return true;

        scheduleNext(app);
        final BroadcastReceiver.PendingResult pending = receiver.goAsync();
        new Thread(() -> {
            try {
                check(app, true, false);
            } finally {
                pending.finish();
            }
        }, "MorpheNewsCheck").start();
        return true;
    }

    /** Called at the start of LocalNotificationRestoreReceiver.onReceive (boot completed). */
    public static void onBoot(Context context) {
        try {
            // LOCKED_BOOT_COMPLETED: credential protected preferences are not available yet,
            // BOOT_COMPLETED follows after unlocking.
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager != null && !userManager.isUserUnlocked()) return;

            Context app = context.getApplicationContext();
            if (isEnabled(app)) scheduleNext(app);
        } catch (Throwable t) {
            Log.e(TAG, "onBoot failed", t);
        }
    }

    // endregion

    // region Settings

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    private static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            scheduleNext(context);
            // Remember the current announcement without notifying: it is already visible in the app.
            new Thread(() -> check(context, false, false), "MorpheNewsCheck").start();
        } else {
            cancelAlarm(context);
        }
    }

    // endregion

    // region Alarm

    private static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(ACTION_CHECK)
                .setComponent(new ComponentName(context, ALARM_RECEIVER))
                .setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleNext(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        long triggerAt = System.currentTimeMillis() + INTERVAL_MS;
        // Both also fire in Doze (at most every ~9 minutes there, which is fine for 30 minutes).
        // The app declares USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM, otherwise Android may delay by up to ~20 minutes.
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(context));
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(context));
        }
    }

    private static void cancelAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(alarmIntent(context));
    }

    // endregion

    // region Check

    /**
     * Requests the current announcement for the saved streets.
     *
     * @param notifyNew  show a notification if the announcement was not seen before.
     * @param forceShow  always show the current announcement (test button), does not change "seen".
     * @return a short German status text for the settings page.
     */
    private static synchronized String check(Context context, boolean notifyNew, boolean forceShow) {
        SharedPreferences prefs = prefs(context);
        String result;
        try {
            String streets = prefs.getString(KEY_STREETS, "");
            if (streets.isEmpty()) {
                result = "Keine Straße bekannt – bitte die App einmal öffnen.";
            } else {
                JSONObject news = requestNews(streets);
                if (news == null) {
                    result = "Keine aktuelle Sondermeldung.";
                    if (forceShow) showTestNotification(context);
                } else {
                    String key = news.optString("date") + "|" + news.optString("title");
                    boolean isNew = !key.equals(prefs.getString(KEY_LAST_SEEN, null));
                    if (forceShow || (notifyNew && isNew)) showNotification(context, news);
                    if (!forceShow) prefs.edit().putString(KEY_LAST_SEEN, key).apply();
                    result = "Aktuelle Sondermeldung: " + news.optString("title");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "check failed", t);
            result = "Fehler beim Abrufen: " + t.getClass().getSimpleName();
        }
        Log.i(TAG, "check (notifyNew=" + notifyNew + ", forceShow=" + forceShow + "): " + result);
        prefs.edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_LAST_RESULT, result)
                .apply();
        return result;
    }

    /** POST {"streets":[ids]} like the app's NewsService, returns null if there is no announcement. */
    private static JSONObject requestNews(String streetsCsv) throws Exception {
        JSONArray ids = new JSONArray();
        for (String id : streetsCsv.split(",")) {
            id = id.trim();
            if (id.isEmpty()) continue;
            try {
                ids.put(Long.parseLong(id));
            } catch (NumberFormatException e) {
                ids.put(id);
            }
        }
        byte[] body = new JSONObject().put("streets", ids).toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(NEWS_URL).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);

            JSONObject response = new JSONObject(readAll(connection.getInputStream()));
            // "data" is the announcement object, or an empty array if there is none.
            JSONObject data = response.optJSONObject("data");
            return data != null && data.has("title") ? data : null;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // endregion

    // region Notification

    private static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    private static Notification.Builder builder(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sondermeldungen",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Ausfälle und Verzögerungen bei der Müllabfuhr (Morphe-Patch)");
            manager.createNotificationChannel(channel);
            return new Notification.Builder(context, CHANNEL_ID);
        }
        //noinspection deprecation
        return new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH);
    }

    private static void post(Context context, String title, String text) {
        if (!notificationsAllowed(context)) return;

        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent contentIntent = launch == null ? null : PendingIntent.getActivity(context, NOTIFICATION_ID,
                launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(0xFF4D8E00) // Primary color of the app.
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .build();
        context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private static void showNotification(Context context, JSONObject news) {
        String text = Html.fromHtml(news.optString("content"), Html.FROM_HTML_MODE_COMPACT)
                .toString().trim().replaceAll("\\s*\\n\\s*", "\n");
        post(context, news.optString("title"), text);
    }

    private static void showTestNotification(Context context) {
        post(context, "Müllkalender: Test", "Benachrichtigungen funktionieren. Aktuell gibt es keine Sondermeldung.");
    }

    // endregion

    // region Status

    private static boolean ignoresBatteryOptimizations(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static String status(Context context) {
        SharedPreferences prefs = prefs(context);
        try {
            return new JSONObject()
                    .put("enabled", isEnabled(context))
                    .put("notificationsAllowed", notificationsAllowed(context))
                    .put("batteryUnrestricted", ignoresBatteryOptimizations(context))
                    .put("streets", prefs.getString(KEY_STREETS, ""))
                    .put("lastCheck", prefs.getLong(KEY_LAST_CHECK, 0))
                    .put("lastResult", prefs.getString(KEY_LAST_RESULT, ""))
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // endregion

    /** Interface for morphe-announcement-notifications.js ("window.morpheNewsBridge"). */
    public static final class JsBridge {
        private final WebView webView;
        private final Context context;

        JsBridge(WebView webView) {
            this.webView = webView;
            this.context = webView.getContext().getApplicationContext();
        }

        @JavascriptInterface
        public String getStatus() {
            return status(context);
        }

        @JavascriptInterface
        public void setEnabled(boolean enabled) {
            NewsNotifications.setEnabled(context, enabled);
        }

        /** Street ids of the app (comma separated), synced by the patched NewsService. */
        @JavascriptInterface
        public void setStreets(String streetIds) {
            prefs(context).edit().putString(KEY_STREETS, streetIds == null ? "" : streetIds).apply();
        }

        /** Checks now and always shows the current announcement (or a test notification). */
        @JavascriptInterface
        public String testNow() {
            return check(context, false, true);
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            Activity activity = activity();
            if (Build.VERSION.SDK_INT >= 33 && activity != null && !activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS) && !notificationsAllowed(context)
                    && wasPermissionRequested()) {
                // Permanently denied: only the system settings can change it.
                openNotificationSettings();
            } else if (Build.VERSION.SDK_INT >= 33 && activity != null) {
                prefs(context).edit().putBoolean("permission_requested", true).apply();
                activity.runOnUiThread(() -> activity.requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE));
            } else {
                openNotificationSettings();
            }
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            Intent intent = Build.VERSION.SDK_INT >= 26
                    ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                    : appDetails();
            start(intent);
        }

        /** App info page, where "Akku" / "App battery usage" can be set to "Nicht eingeschränkt". */
        @JavascriptInterface
        public void openAppSettings() {
            start(appDetails());
        }

        private boolean wasPermissionRequested() {
            return prefs(context).getBoolean("permission_requested", false);
        }

        private Intent appDetails() {
            return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.getPackageName(), null));
        }

        private Activity activity() {
            Context viewContext = webView.getContext();
            return viewContext instanceof Activity ? (Activity) viewContext : null;
        }

        private void start(Intent intent) {
            Activity activity = activity();
            if (activity != null) {
                activity.runOnUiThread(() -> activity.startActivity(intent));
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    }
}
