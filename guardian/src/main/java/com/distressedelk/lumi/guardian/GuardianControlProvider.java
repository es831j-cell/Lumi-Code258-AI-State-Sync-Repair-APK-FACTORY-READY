package com.distressedelk.lumi.guardian;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Lumi 1.0 signature-protected Guardian control surface. Guardian remains a separate package and
 * independently calls Lumi's protected certification/checkpoint provider instead of trusting a
 * model/relay assertion.
 */
public final class GuardianControlProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        Bundle out=new Bundle();
        if(getContext()==null){out.putBoolean("ok",false);out.putString("error","Guardian context unavailable");return out;}
        try{
            if("health".equals(method)) {
                Bundle lumi=GuardianBridgeClient.call(getContext(),"health");
                lumi.putBoolean("guardianInstalled", true);
                lumi.putBoolean("installerPermissionReady",
                        android.os.Build.VERSION.SDK_INT < 26 || getContext().getPackageManager().canRequestPackageInstalls());
                lumi.putLong("guardianVersionCode", 4L);
                lumi.putString("guardianVersionName", "1.3-factory-exit");
                return lumi;
            }
            if("certify".equals(method)) return GuardianBridgeClient.call(getContext(),"certify");
            if("create_checkpoint".equals(method)) return GuardianBridgeClient.call(getContext(),"create_checkpoint");
            if("restore_latest_checkpoint".equals(method)) return GuardianBridgeClient.call(getContext(),"restore_latest_checkpoint");
            out.putBoolean("ok",false);out.putString("error","Unsupported Guardian control method");return out;
        }catch(Exception e){out.putBoolean("ok",false);out.putString("error",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));return out;}
    }
    @Override public String getType(Uri uri){return null;}
    @Override public Cursor query(Uri uri,String[] p,String s,String[] a,String sort){return null;}
    @Override public Uri insert(Uri uri,ContentValues v){return null;}
    @Override public int delete(Uri uri,String s,String[] a){return 0;}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){return 0;}
}
