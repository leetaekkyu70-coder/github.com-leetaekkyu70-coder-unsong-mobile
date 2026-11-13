package kr.unsong.mobile;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 매일 아침 8시에 깨어나, 오늘 날짜에 해당하는 기한 알림을 폰 알림으로 보여준다. */
public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "due";
    private static final int HOUR = 8;

    @Override
    public void onReceive(Context context, Intent intent) {
        run(context);
    }

    /** 8시가 지났으면 오늘 알림을 확인하고, 다음 아침 확인을 예약한다. */
    static void run(Context c) {
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= HOUR) check(c);
        schedule(c);
    }

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, HOUR);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (!t.after(Calendar.getInstance())) t.add(Calendar.DAY_OF_YEAR, 1);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
    }

    private static void check(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("app", Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String last = prefs.getString("lastCheck", "");
        Set<String> shown = today.equals(last)
                ? new HashSet<>(prefs.getStringSet("shown", new HashSet<String>())) : new HashSet<String>();
        try {
            JSONArray list = new JSONArray(prefs.getString("reminders", "[]"));
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channel_due),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(c.getString(R.string.channel_due_desc));
            nm.createNotificationChannel(ch);
            PendingIntent open = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                String id = o.optString("id"), date = o.optString("date");
                // 오늘 것, 그리고 폰이 꺼져 있어 놓친 날의 것
                boolean due = date.equals(today)
                        || (!last.isEmpty() && date.compareTo(last) > 0 && date.compareTo(today) < 0);
                if (!due || shown.contains(id)) continue;
                Notification n = new Notification.Builder(c, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle(o.optString("title"))
                        .setContentText(o.optString("text"))
                        .setStyle(new Notification.BigTextStyle().bigText(o.optString("text")))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build();
                nm.notify(id.hashCode(), n);
                shown.add(id);
            }
        } catch (Exception e) {
            // 목록을 읽지 못하면 이번 확인은 건너뛴다.
        }
        prefs.edit().putString("lastCheck", today).putStringSet("shown", shown).apply();
    }
}
