package com.hakim.resmigazeteozeti;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.Html;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DateTimeFormatter displayDate = DateTimeFormatter.ofPattern("dd.MM.yyyy", new Locale("tr", "TR"));
    private TextView status;
    private ProgressBar progress;
    private EditText dateInput;
    private EditText numberInput;
    private Button trackingButton;
    private LinearLayout results;
    private Issue currentIssue;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        refreshTrackingButton();
        loadLatest();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(255,253,248));

        TextView header = new TextView(this);
        header.setText("Resmî Gazete Özeti");
        header.setTextSize(24);
        header.setTextColor(Color.WHITE);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(18));
        header.setBackgroundColor(Color.rgb(139,30,45));
        root.addView(header, new LinearLayout.LayoutParams(-1,-2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16),dp(16),dp(16),dp(40));
        scroll.addView(content, new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));

        content.addView(card("Her gün Resmî Gazete'yi kontrol eder; tarih veya sayı ile arama yapar. Özet cihazda hazırlanır, API anahtarı gerekmez."));
        content.addView(button("Bugünkü / Son Resmî Gazete", v -> loadLatest()), margin(10));

        content.addView(section("Tarihle ara"));
        dateInput = edit("GG.AA.YYYY", InputType.TYPE_CLASS_DATETIME);
        dateInput.setText(LocalDate.now().format(displayDate));
        content.addView(dateInput, new LinearLayout.LayoutParams(-1,dp(52)));
        content.addView(button("Tarihli Sayıyı Getir", v -> loadDate()), margin(8));

        content.addView(section("Resmî Gazete sayısıyla ara"));
        numberInput = edit("Örnek: 33326", InputType.TYPE_CLASS_NUMBER);
        content.addView(numberInput, new LinearLayout.LayoutParams(-1,dp(52)));
        content.addView(button("Sayıyı Bul", v -> loadNumber()), margin(8));

        content.addView(section("Otomatik takip"));
        trackingButton = button("", v -> toggleTracking());
        content.addView(trackingButton);
        TextView hint = new TextView(this);
        hint.setText("Kontrol her gün yaklaşık 06.30'da yapılır. Pil tasarrufu nedeniyle gecikme olabilir.");
        hint.setTextSize(13);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(dp(2),dp(8),dp(2),dp(6));
        content.addView(hint);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(Color.rgb(70,70,70));
        status.setPadding(dp(2),dp(16),dp(2),dp(8));
        content.addView(status);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(-1,dp(42)));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results, new LinearLayout.LayoutParams(-1,-2));
        return root;
    }

    private void loadLatest() {
        runAsync("Son yayımlanan sayı kontrol ediliyor…", () -> Repository.fetchLatest());
    }

    private void loadDate() {
        try {
            LocalDate date = LocalDate.parse(dateInput.getText().toString().trim(), displayDate);
            runAsync(date.format(displayDate)+" tarihli sayı getiriliyor…", () -> Repository.fetch(date));
        } catch (DateTimeParseException e) {
            toast("Tarihi GG.AA.YYYY biçiminde girin.");
        }
    }

    private void loadNumber() {
        Integer number;
        try { number = Integer.valueOf(numberInput.getText().toString().trim()); }
        catch (Exception e) { toast("Geçerli bir sayı girin."); return; }
        if (number <= 0) { toast("Geçerli bir sayı girin."); return; }
        setLoading(true, number+" sayılı Resmî Gazete aranıyor…");
        executor.execute(() -> {
            try {
                Issue issue = Repository.findByNumber(number, text -> main.post(() -> status.setText(text)));
                main.post(() -> { setLoading(false, ""); showIssue(issue); });
            } catch (Throwable t) {
                main.post(() -> { setLoading(false, ""); showError(t); });
            }
        });
    }

    private void runAsync(String message, Callable<Issue> task) {
        setLoading(true, message);
        executor.execute(() -> {
            try {
                Issue issue = task.call();
                main.post(() -> { setLoading(false, ""); showIssue(issue); });
            } catch (Throwable t) {
                main.post(() -> { setLoading(false, ""); showError(t); });
            }
        });
    }

    private void showIssue(Issue issue) {
        currentIssue = issue;
        results.removeAllViews();
        status.setText(issue.title+"\n"+issue.entries.size()+" düzenleme/karar bulundu.");
        results.addView(card(Repository.headline(issue)), margin(8));
        results.addView(secondary("Tam PDF'yi Aç", v -> openUrl(issue.pdfUrl)), margin(8));
        Button all = button("Günün Ayrıntılı Özetini Göster", v -> showText(issue.title, Repository.detailed(issue)));
        results.addView(all, margin(8));
        if (issue.entries.isEmpty()) {
            results.addView(card("Bu sayı için ayrı HTML başlıkları okunamadı. Tam PDF bağlantısını kullanın."), margin(8));
            return;
        }
        int i=1;
        for (Entry e : issue.entries) {
            Button b = secondary((i++)+". ["+e.section+"]\n"+e.title, v -> summarize(e));
            b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            results.addView(b, margin(8));
        }
    }

    private void summarize(Entry e) {
        setLoading(true, "Düzenleme özeti hazırlanıyor…");
        executor.execute(() -> {
            try {
                String text = Repository.summarize(e);
                main.post(() -> { setLoading(false, ""); showSummary(e, text); });
            } catch (Throwable t) {
                main.post(() -> { setLoading(false, ""); showError(t); });
            }
        });
    }

    private void showSummary(Entry e, String text) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(e.title).setMessage(text)
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Resmî Metni Aç", (d,w) -> openUrl(e.htmlUrl != null ? e.htmlUrl : e.pdfUrl))
            .setNegativeButton("Kopyala", (d,w) -> copy(text)).create();
        dialog.setOnShowListener(x -> {
            TextView m = dialog.findViewById(android.R.id.message);
            if (m != null) { m.setTextIsSelectable(true); m.setTextSize(15); }
        });
        dialog.show();
    }

    private void showText(String title, String text) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text)
            .setPositiveButton("Kapat", null).setNegativeButton("Kopyala", (d,w) -> copy(text)).show();
    }

    private void toggleTracking() {
        boolean enable = !Prefs.enabled(this);
        Prefs.setEnabled(this, enable);
        if (enable) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
            }
            Scheduler.scheduleNext(this);
            Scheduler.scheduleImmediate(this);
            toast("Günlük takip açıldı.");
        } else {
            Scheduler.cancel(this);
            toast("Günlük takip kapatıldı.");
        }
        refreshTrackingButton();
    }

    private void refreshTrackingButton() {
        if (trackingButton != null) trackingButton.setText(Prefs.enabled(this) ? "Günlük Takibi Kapat" : "Günlük Takibi Aç");
    }

    private void setLoading(boolean loading, String message) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (!message.isEmpty()) status.setText(message);
    }

    private void showError(Throwable t) {
        String msg = t.getMessage();
        status.setText("İşlem tamamlanamadı: "+(msg == null ? t.getClass().getSimpleName() : msg));
        toast("Resmî Gazete bilgisi alınamadı.");
    }

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) { toast("Bağlantı bulunamadı."); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("Bağlantı açılamadı."); }
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Resmî Gazete özeti", text));
        toast("Özet kopyalandı.");
    }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(this); e.setHint(hint); e.setInputType(type); e.setSingleLine(true); return e;
    }
    private TextView section(String text) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(18); v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setTextColor(Color.rgb(100,20,32)); v.setPadding(dp(2),dp(20),dp(2),dp(8)); return v;
    }
    private TextView card(String text) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(15); v.setTextColor(Color.rgb(36,36,36));
        v.setPadding(dp(14),dp(14),dp(14),dp(14)); v.setBackgroundColor(Color.WHITE); v.setTextIsSelectable(true); return v;
    }
    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(15);
        b.setBackgroundColor(Color.rgb(139,30,45)); b.setOnClickListener(l); return b;
    }
    private Button secondary(String text, View.OnClickListener l) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.rgb(36,36,36));
        b.setTextSize(14); b.setBackgroundColor(Color.rgb(244,237,237)); b.setOnClickListener(l); return b;
    }
    private LinearLayout.LayoutParams margin(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top); return p; }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }

    static final class Issue {
        final LocalDate date; final Integer number; final String title; final String pdfUrl; final List<Entry> entries;
        Issue(LocalDate d,Integer n,String t,String p,List<Entry> e){date=d;number=n;title=t;pdfUrl=p;entries=e;}
    }
    static final class Entry {
        final String key,section,title; String htmlUrl,pdfUrl;
        Entry(String k,String s,String t,String h,String p){key=k;section=s;title=t;htmlUrl=h;pdfUrl=p;}
    }

    static final class Repository {
        static final Locale TR = new Locale("tr","TR");
        static final Pattern ISSUE_NO = Pattern.compile("(?:Sayı\\s*:?\\s*|)(\\d{4,6})\\s*(?:Sayılı\\s+Resm[îi]\\s+Gazete)?", Pattern.CASE_INSENSITIVE);
        static final Pattern LINK = Pattern.compile("<a[^>]+href\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        static Issue fetchLatest() throws Exception {
            LocalDate today=LocalDate.now(ZoneId.of("Europe/Istanbul"));
            Exception last=null;
            for(int i=0;i<=10;i++) try { Issue x=fetch(today.minusDays(i)); if(x.number!=null)return x; } catch(Exception e){last=e;}
            throw new IOException("Son yayımlanan sayı bulunamadı.",last);
        }
        static Issue fetch(LocalDate date) throws Exception {
            String y=String.valueOf(date.getYear()), m=String.format(Locale.US,"%02d",date.getMonthValue()), basic=date.format(DateTimeFormatter.BASIC_ISO_DATE);
            String[] urls={"https://www.resmigazete.gov.tr/eskiler/"+y+"/"+m+"/"+basic+".htm", "https://www.resmigazete.gov.tr/fihrist?tarih="+date};
            Exception last=null;
            for(String u:urls) try { String html=get(u); Issue x=parse(date,u,html); if(x.number!=null||!x.entries.isEmpty())return x; } catch(Exception e){last=e;}
            throw new IOException(date+" tarihli Resmî Gazete bulunamadı.",last);
        }
        static Issue findByNumber(int target, java.util.function.Consumer<String> progress) throws Exception {
            Issue latest=fetchLatest();
            if(latest.number==null) throw new IOException("Güncel sayı okunamadı.");
            if(target>latest.number) throw new IOException("Bu sayı henüz yayımlanmamış görünüyor. Güncel sayı: "+latest.number);
            if(target==latest.number)return latest;
            LocalDate estimated=latest.date.minusDays(latest.number-target);
            Issue quick=scan(estimated,target,20); if(quick!=null)return quick;
            LocalDate low=LocalDate.of(1921,2,7), high=latest.date;
            for(int i=1;i<=25&&!low.isAfter(high);i++){
                progress.accept("Sayı aranıyor: adım "+i+"/25");
                LocalDate mid=low.plusDays(ChronoUnit.DAYS.between(low,high)/2);
                Issue x=nearest(mid,4);
                if(x==null){low=mid.plusDays(5);continue;}
                if(x.number==target)return x;
                if(x.number<target)low=x.date.plusDays(1); else high=x.date.minusDays(1);
            }
            Issue x=scan(high,target,35); if(x!=null)return x;
            throw new IOException(target+" sayılı Resmî Gazete bulunamadı. Tarihle aramayı deneyin.");
        }
        static Issue nearest(LocalDate date,int radius){ for(int i=0;i<=radius;i++){int[] os=i==0?new int[]{0}:new int[]{i,-i}; for(int o:os)try{Issue x=fetch(date.plusDays(o));if(x.number!=null)return x;}catch(Exception ignored){}}return null; }
        static Issue scan(LocalDate date,int target,int radius){ for(int i=0;i<=radius;i++){int[] os=i==0?new int[]{0}:new int[]{i,-i}; for(int o:os)try{Issue x=fetch(date.plusDays(o));if(x.number!=null&&x.number==target)return x;}catch(Exception ignored){}}return null; }
        static Issue parse(LocalDate date,String base,String html) throws Exception {
            Integer no=parseNo(html);
            String basic=date.format(DateTimeFormatter.BASIC_ISO_DATE);
            LinkedHashMap<String,Entry> map=new LinkedHashMap<>();
            Matcher lm=LINK.matcher(html);
            while(lm.find()){
                String href=resolve(base,lm.group(1));
                if(!href.toLowerCase(TR).contains(basic.toLowerCase(TR)) || href.toLowerCase(TR).contains("/ilanlar/"))continue;
                if(!(href.matches("(?i).*\\.(?:htm|html|pdf)(?:[?#].*)?$")))continue;
                String key=href.replaceAll("(?i).*/([^/]+?)\\.(?:htm|html|pdf)(?:[?#].*)?$","$1");
                String title=decode(lm.group(2)); if(title.length()<3)continue;
                Entry e=map.get(key); if(e==null){e=new Entry(key,section(title),title,null,null);map.put(key,e);}
                if(href.toLowerCase(TR).contains(".pdf"))e.pdfUrl=href; else e.htmlUrl=href;
                if(title.length()>e.title.length()) map.put(key,new Entry(key,e.section,title,e.htmlUrl,e.pdfUrl));
            }
            String pdf="https://www.resmigazete.gov.tr/eskiler/"+date.getYear()+"/"+String.format(Locale.US,"%02d",date.getMonthValue())+"/"+basic+".pdf";
            String title=date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy",TR))+" Tarihli"+(no==null?"":" ve "+no+" Sayılı")+" Resmî Gazete";
            return new Issue(date,no,title,pdf,new ArrayList<>(map.values()));
        }
        static Integer parseNo(String html){
            String text=decode(html).replaceAll("\\s+"," ");
            Matcher m=Pattern.compile("Sayı\\s*:?\\s*(\\d{1,6})",Pattern.CASE_INSENSITIVE).matcher(text);
            if(m.find())try{return Integer.valueOf(m.group(1));}catch(Exception ignored){}
            m=Pattern.compile("(\\d{1,6})\\s+Sayılı\\s+Resm[îi]\\s+Gazete",Pattern.CASE_INSENSITIVE).matcher(text);
            if(m.find())try{return Integer.valueOf(m.group(1));}catch(Exception ignored){}
            return null;
        }
        static String headline(Issue issue){
            LinkedHashMap<String,Integer> c=new LinkedHashMap<>(); for(Entry e:issue.entries)c.put(e.section,c.getOrDefault(e.section,0)+1);
            StringBuilder b=new StringBuilder(issue.title); if(!c.isEmpty()){b.append("\n");for(Map.Entry<String,Integer>x:c.entrySet())b.append(x.getKey()).append(": ").append(x.getValue()).append("  •  ");}
            int n=Math.min(6,issue.entries.size()); if(n>0)b.append("\n\nÖne çıkan başlıklar:"); for(int i=0;i<n;i++)b.append("\n• ").append(issue.entries.get(i).title); return b.toString();
        }
        static String detailed(Issue issue){ StringBuilder b=new StringBuilder(headline(issue)); b.append("\n\nKısa günlük liste:"); int n=Math.min(20,issue.entries.size()); for(int i=0;i<n;i++)b.append("\n\n").append(i+1).append(". [").append(issue.entries.get(i).section).append("] ").append(issue.entries.get(i).title); if(issue.entries.size()>n)b.append("\n\nAyrıca ").append(issue.entries.size()-n).append(" kayıt daha bulunmaktadır."); b.append("\n\nNot: Bu çıktı otomatik özettir; resmî metnin yerine geçmez."); return b.toString(); }
        static String summarize(Entry e) throws Exception {
            if(e.htmlUrl==null)return e.title+"\n\nBu kayıt yalnızca PDF olarak sunuluyor. Resmî PDF bağlantısından inceleyin.\n\nNot: Otomatik özettir; hukuki görüş değildir.";
            String text=decode(get(e.htmlUrl)).replaceAll("(?is)<script.*?</script>|<style.*?</style>"," ").replaceAll("(?s)<[^>]+>"," ").replaceAll("\\s+"," ").trim();
            StringBuilder b=new StringBuilder(); b.append(e.title).append("\n\nDüzenleme türü: ").append(e.section).append("\n\nÖne çıkan noktalar:");
            List<String> lines=new ArrayList<>();
            addMatch(lines,text,"Amaç",Pattern.compile("Amaç\\s+MADDE\\s+\\d+[–—-]?\\s*(.{20,500}?[.!?])",Pattern.CASE_INSENSITIVE));
            addMatch(lines,text,"Kapsam",Pattern.compile("Kapsam\\s+MADDE\\s+\\d+[–—-]?\\s*(.{20,500}?[.!?])",Pattern.CASE_INSENSITIVE));
            addMatch(lines,text,"Yürürlük",Pattern.compile("Yürürlük\\s+MADDE\\s+\\d+[–—-]?\\s*(.{10,350}?yürürlüğe girer\\.)",Pattern.CASE_INSENSITIVE));
            addMatch(lines,text,"Yürütme",Pattern.compile("Yürütme\\s+MADDE\\s+\\d+[–—-]?\\s*(.{10,350}?yürütür\\.)",Pattern.CASE_INSENSITIVE));
            if(lines.isEmpty()){
                Matcher s=Pattern.compile("([^.!?]{40,330}[.!?])").matcher(text); while(s.find()&&lines.size()<5){String q=s.group(1).trim(); if(!q.toLowerCase(TR).contains("resmî gazete"))lines.add(q);}
            }
            for(String q:lines)b.append("\n• ").append(q.length()>420?q.substring(0,420)+"…":q);
            b.append("\n\nNot: Bu çıktı metinden otomatik çıkarılmış özettir; hukuki değerlendirme veya resmî metnin yerine geçmez."); return b.toString();
        }
        static void addMatch(List<String> out,String text,String name,Pattern p){Matcher m=p.matcher(text);if(m.find())out.add(name+": "+m.group(1).trim());}
        static String section(String t){String u=t.toUpperCase(TR);if(u.contains("KANUN"))return "Kanun";if(u.contains("YÖNETMELİK"))return "Yönetmelik";if(u.contains("TEBLİĞ"))return "Tebliğ";if(u.contains("GENELGE"))return "Genelge";if(u.contains("ANAYASA MAHKEMESİ")||u.contains("DANIŞTAY")||u.contains("YARGITAY"))return "Yargı Kararı";if(u.contains("ATAMA"))return "Atama Kararı";if(u.contains("KARAR"))return "Karar";return "Diğer";}
        static String resolve(String base,String href){try{return URI.create(base).resolve(href.replace("&amp;","&")).toString();}catch(Exception e){return href;}}
        static String decode(String html){if(html==null)return "";return Html.fromHtml(html,Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00A0',' ').replaceAll("\\s+"," ").trim();}
        static String get(String url) throws Exception {
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(20000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","Mozilla/5.0 (Android) ResmiGazeteOzeti/1.0");c.setRequestProperty("Referer","https://www.resmigazete.gov.tr/");
            int code=c.getResponseCode(); if(code<200||code>=400)throw new IOException("Sunucu yanıtı: "+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))!=-1&&total<8*1024*1024){out.write(buf,0,n);total+=n;}return out.toString(StandardCharsets.UTF_8.name());} finally {c.disconnect();}
        }
    }

    public static final class Prefs {
        static SharedPreferences p(Context c){return c.getSharedPreferences("rg_ozet",Context.MODE_PRIVATE);} static boolean enabled(Context c){return p(c).getBoolean("enabled",false);} static void setEnabled(Context c,boolean v){p(c).edit().putBoolean("enabled",v).apply();} static int last(Context c){return p(c).getInt("last",-1);} static void save(Context c,int n){p(c).edit().putInt("last",n).apply();}
    }
    public static final class Scheduler {
        static final int DAILY=43001, NOW=43002; static void scheduleNext(Context c){ZonedDateTime now=ZonedDateTime.now(ZoneId.of("Europe/Istanbul"));ZonedDateTime next=now.withHour(6).withMinute(30).withSecond(0).withNano(0);if(!next.isAfter(now))next=next.plusDays(1);long d=Math.max(1000,Duration.between(now,next).toMillis());JobInfo j=new JobInfo.Builder(DAILY,new ComponentName(c,DailyJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(d).setOverrideDeadline(d+Duration.ofHours(2).toMillis()).build();c.getSystemService(JobScheduler.class).schedule(j);} static void scheduleImmediate(Context c){JobInfo j=new JobInfo.Builder(NOW,new ComponentName(c,DailyJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000).setOverrideDeadline(Duration.ofMinutes(5).toMillis()).build();c.getSystemService(JobScheduler.class).schedule(j);} static void cancel(Context c){JobScheduler s=c.getSystemService(JobScheduler.class);s.cancel(DAILY);s.cancel(NOW);}
    }
    public static final class DailyJobService extends JobService {
        @Override public boolean onStartJob(JobParameters p){new Thread(() -> {try{if(!Prefs.enabled(this))return;Issue i=Repository.fetchLatest();if(i.number!=null&&i.number>Prefs.last(this)){notifyIssue(this,i);Prefs.save(this,i.number);}}catch(Throwable ignored){}finally{if(Prefs.enabled(this))Scheduler.scheduleNext(this);jobFinished(p,false);}}).start();return true;} @Override public boolean onStopJob(JobParameters p){return true;}
    }
    public static final class BootReceiver extends BroadcastReceiver { @Override public void onReceive(Context c,Intent i){if(Prefs.enabled(c))Scheduler.scheduleNext(c);} }
    static void notifyIssue(Context c,Issue i){if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;NotificationManager nm=c.getSystemService(NotificationManager.class);String id="rg_daily";if(nm.getNotificationChannel(id)==null)nm.createNotificationChannel(new NotificationChannel(id,"Günlük Resmî Gazete",NotificationManager.IMPORTANCE_DEFAULT));Intent open=new Intent(c,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(c,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification n=new Notification.Builder(c,id).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle((i.number==null?"Yeni":i.number)+" sayılı Resmî Gazete yayımlandı").setContentText("Günün başlıkları hazır").setStyle(new Notification.BigTextStyle().bigText(Repository.headline(i))).setContentIntent(pi).setAutoCancel(true).build();nm.notify(33326,n);}
}
