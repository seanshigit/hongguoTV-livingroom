package com.hongguotv.nativeapp;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Java/framework-only: independent of Kotlin and Media3 names removed by release R8. */
public class ReleasePlaybackAcceptance extends Instrumentation {
    private Activity activity;
    private MediaSessionManager manager;
    private volatile MediaController current;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private File output;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private List<View> all(View root) {
        List<View> result=new ArrayList<>(); result.add(root);
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++) result.addAll(all(group.getChildAt(i)));
        }
        return result;
    }
    private List<View> all() { return all(activity.getWindow().getDecorView()); }
    private void await(String name, BooleanSupplier condition) {
        long deadline=SystemClock.elapsedRealtime()+90_000;
        while(SystemClock.elapsedRealtime()<deadline) {
            boolean[] passed={false};
            runOnMainSync(() -> passed[0]=condition.getAsBoolean());
            if(passed[0]) return;
            SystemClock.sleep(150);
        }
        throw new AssertionError("Timed out: "+name);
    }
    private MediaController controller() {
        return current;
    }
    private boolean hasState(int state) {
        MediaController controller=controller();
        return controller!=null && controller.getPlaybackState()!=null && controller.getPlaybackState().getState()==state;
    }
    private void click(String prefix) { runOnMainSync(() -> {
        for(View view:all()) if(view instanceof TextView && view.isShown() && view.isFocusable()
            && ((TextView)view).getText().toString().startsWith(prefix)) { view.performClick(); return; }
        throw new AssertionError("Missing control: "+prefix);
    }); }
    private boolean playControl(View view) {
        if(!(view instanceof TextView)) return false;
        String text=((TextView)view).getText().toString();
        return text.startsWith("开始观看") || text.startsWith("继续第");
    }
    private boolean detail() { for(View view:all()) if(playControl(view)) return true; return false; }
    private List<String> codecs() throws IOException {
        List<String> rows=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("dumpsys media.metrics"))))) {
            String line; while((line=reader.readLine())!=null) if(line.contains("{codec,")) rows.add(line);
        }
        return rows;
    }
    private String field(String line,String name) {
        Matcher match=Pattern.compile("android\\.media\\.mediacodec\\."+Pattern.quote(name)+"=([^,)}]+)").matcher(line);
        return match.find()?match.group(1):"";
    }
    @Override public void onStart() {
        StringBuilder report=new StringBuilder(); int result=Activity.RESULT_CANCELED;
        output=new File(getTargetContext().getExternalFilesDir(null),"release-acceptance"); output.mkdirs();
        try {
            getUiAutomation().adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL);
            manager=getTargetContext().getSystemService(MediaSessionManager.class);
            sessionsListener=sessions -> {
                current=null;
                if(sessions!=null) for(MediaController session:sessions)
                    if(session.getPackageName().equals(getTargetContext().getPackageName())) { current=session; break; }
            };
            manager.addOnActiveSessionsChangedListener(sessionsListener,null,new Handler(Looper.getMainLooper()));
            // Do not run the application as shell: it changes Android's audio-focus eligibility.
            getUiAutomation().dropShellPermissionIdentity();
            Set<String> oldIds=new HashSet<>(); for(String line:codecs()) oldIds.add(field(line,"id"));
            File prefs=new File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs/native-library-v1.xml");
            if(prefs.exists()) Files.copy(prefs.toPath(),new File(output,"library-after-install.xml").toPath(),StandardCopyOption.REPLACE_EXISTING);
            // Record preservation first, then use normal playback defaults and episode one.
            getTargetContext().getSharedPreferences("native-library-v1",0).edit().putFloat("playbackSpeed",1f).putInt("quality",1080).commit();
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"com.hongguotv.nativeapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            for(String type:new String[]{"漫剧","短剧"}) {
                await("home",() -> { for(View view:all()) if(view instanceof TextView) { String text=((TextView)view).getText().toString(); if(text.startsWith("刷新片库") || text.contains("按确认查看")) return true; } return false; });
                click(type);
                await("posters",() -> { for(View view:all()) if(String.valueOf(view.getTag()).startsWith("hot:")) return true; return false; });
                runOnMainSync(() -> { for(View view:all()) if(String.valueOf(view.getTag()).startsWith("hot:")) { view.performClick(); return; } });
                await("detail",this::detail);
                runOnMainSync(() -> { for(View view:all()) if("第 1 集".contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) { view.performClick(); return; } throw new AssertionError("Missing first episode"); });
                await("release playback",() -> hasState(PlaybackState.STATE_PLAYING));
                SystemClock.sleep(2200);
                controller().getTransportControls().pause(); await("pause",() -> hasState(PlaybackState.STATE_PAUSED));
                controller().getTransportControls().play(); await("resume",() -> hasState(PlaybackState.STATE_PLAYING));
                Bitmap bitmap=getUiAutomation().takeScreenshot();
                if(bitmap!=null) { try(OutputStream stream=new FileOutputStream(new File(output,type+"-release.png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,stream); } bitmap.recycle(); }
                report.append("PASS ").append(type).append(": signed release reports playing; system pause/resume succeeds (video frames checked below)\n");
                controller().getTransportControls().stop(); await("return to detail",this::detail);
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            }
            int decoded=0;
            for(String line:codecs()) if(!oldIds.contains(field(line,"id")) && field(line,"mime").startsWith("video/") && !field(line,"frames-rendered").isEmpty() && Long.parseLong(field(line,"frames-rendered"))>0) {
                decoded++;
                report.append("PASS decoder:");
                for(String key:new String[]{"codec","mime","width","height","frames-rendered"}) report.append(' ').append(key).append('=').append(field(line,key));
                report.append('\n');
            }
            if(decoded<2) throw new AssertionError("Missing two new video decoder sessions with rendered frames");
            result=Activity.RESULT_OK;
        } catch(Throwable error) {
            StringWriter stack=new StringWriter(); error.printStackTrace(new PrintWriter(stack)); report.append("FAIL: ").append(stack);
        } finally {
            getUiAutomation().adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL);
            if(manager!=null && sessionsListener!=null) manager.removeOnActiveSessionsChangedListener(sessionsListener);
            getUiAutomation().dropShellPermissionIdentity();
        }
        try(FileWriter file=new FileWriter(new File(output,"report.txt"))) { file.write(report.toString()); } catch(IOException error) { result=Activity.RESULT_CANCELED; }
        Bundle bundle=new Bundle(); bundle.putString("stream",report.toString()); finish(result,bundle);
    }
}
