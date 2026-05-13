package com.callx.app.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class ReelLiveActivity extends AppCompatActivity {
    private static final int REQ=200;
    private static final String[]PERMS={Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO};

    private PreviewView preview; private TextView tvViewers,tvDuration,tvStatus;
    private RecyclerView rvComments; private EditText etComment;
    private ImageButton btnFlip,btnMute,btnEnd,btnSend;
    private View layoutPerm,layoutControls; private ProgressBar progressGo;
    private CommentAdapter adp; private final List<Cmt> comments=new ArrayList<>();
    private String myUid,myName; private boolean isLive=false,muted=false,front=true;
    private ProcessCameraProvider cam; private Handler handler=new Handler(Looper.getMainLooper());
    private long startMs=0; private DatabaseReference liveRef;
    private ValueEventListener viewerL,commentL;

    @Override protected void onCreate(Bundle s){
        super.onCreate(s); setContentView(R.layout.activity_reel_live);
        try{myUid=FirebaseUtils.getCurrentUid();myName=FirebaseUtils.getCurrentName();}catch(Exception e){finish();return;}
        bindViews();
        if(allOk()) startCam(); else ActivityCompat.requestPermissions(this,PERMS,REQ);
    }

    private void bindViews(){
        preview=findViewById(R.id.preview_live); tvViewers=findViewById(R.id.tv_live_viewers);
        tvDuration=findViewById(R.id.tv_live_duration); tvStatus=findViewById(R.id.tv_live_status);
        rvComments=findViewById(R.id.rv_live_comments); etComment=findViewById(R.id.et_live_comment);
        btnFlip=findViewById(R.id.btn_live_flip); btnMute=findViewById(R.id.btn_live_mute);
        btnEnd=findViewById(R.id.btn_live_end); btnSend=findViewById(R.id.btn_live_send_comment);
        layoutPerm=findViewById(R.id.layout_live_permission); layoutControls=findViewById(R.id.layout_live_controls);
        progressGo=findViewById(R.id.progress_go_live);
        adp=new CommentAdapter(comments); rvComments.setLayoutManager(new LinearLayoutManager(this)); rvComments.setAdapter(adp);
        Button btnGo=findViewById(R.id.btn_go_live); btnGo.setOnClickListener(v->goLive(btnGo));
        btnFlip.setOnClickListener(v->{front=!front;startCam();});
        btnMute.setOnClickListener(v->{muted=!muted;btnMute.setAlpha(muted?0.4f:1f);Toast.makeText(this,muted?"Mic muted":"Mic on",Toast.LENGTH_SHORT).show();});
        btnEnd.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("End Live?").setMessage("End your live stream?").setPositiveButton("End",(d,w)->end()).setNegativeButton("Cancel",null).show());
        btnSend.setOnClickListener(v->sendComment());
    }

    private void goLive(Button btnGo){
        if(isLive) return; btnGo.setVisibility(View.GONE); progressGo.setVisibility(View.VISIBLE);
        liveRef=FirebaseUtils.db().getReference("liveStreams").child(myUid);
        Map<String,Object>d=new HashMap<>();d.put("hostUid",myUid);d.put("hostName",myName);d.put("title",myName+"'s Live");d.put("startedAt",System.currentTimeMillis());d.put("viewers",0);d.put("isLive",true);
        liveRef.setValue(d).addOnSuccessListener(u->{if(isFinishing()||isDestroyed()) return;
            isLive=true;progressGo.setVisibility(View.GONE);layoutControls.setVisibility(View.VISIBLE);tvStatus.setVisibility(View.VISIBLE);startMs=System.currentTimeMillis();tick();listenViewers();listenComments();
        }).addOnFailureListener(ex->{if(!isFinishing()&&!isDestroyed()){progressGo.setVisibility(View.GONE);btnGo.setVisibility(View.VISIBLE);Toast.makeText(this,"Cannot start live: "+ex.getMessage(),Toast.LENGTH_SHORT).show();}});
    }

    private void tick(){handler.post(new Runnable(){@Override public void run(){if(!isLive||isFinishing()||isDestroyed()) return;long e=(System.currentTimeMillis()-startMs)/1000;tvDuration.setText(String.format("%02d:%02d",e/60,e%60));handler.postDelayed(this,1000);}});}

    private void listenViewers(){viewerL=new ValueEventListener(){@Override public void onDataChange(@NonNull DataSnapshot s){if(!isFinishing()&&!isDestroyed()){Long c=s.getValue(Long.class);tvViewers.setText((c!=null?c:0)+" viewers");}}@Override public void onCancelled(@NonNull DatabaseError e){}};liveRef.child("viewers").addValueEventListener(viewerL);}

    private void listenComments(){commentL=new ValueEventListener(){@Override public void onDataChange(@NonNull DataSnapshot snap){if(isFinishing()||isDestroyed()) return;comments.clear();for(DataSnapshot s:snap.getChildren()){Cmt c=new Cmt();c.name=s.child("name").getValue(String.class);c.text=s.child("text").getValue(String.class);if(c.name==null)c.name="User";if(c.text!=null) comments.add(c);}adp.notifyDataSetChanged();if(!comments.isEmpty()) rvComments.scrollToPosition(comments.size()-1);}@Override public void onCancelled(@NonNull DatabaseError e){}};liveRef.child("comments").addValueEventListener(commentL);}

    private void sendComment(){String t=etComment.getText()!=null?etComment.getText().toString().trim():"";if(t.isEmpty()||liveRef==null) return;Map<String,Object>c=new HashMap<>();c.put("name",myName+" (host)");c.put("text",t);c.put("ts",System.currentTimeMillis());liveRef.child("comments").push().setValue(c);etComment.setText("");}

    private void startCam(){
        CameraSelector cs=front?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;
        ListenableFuture<ProcessCameraProvider>f=ProcessCameraProvider.getInstance(this);
        f.addListener(()->{try{cam=f.get();cam.unbindAll();Preview p=new Preview.Builder().build();p.setSurfaceProvider(preview.getSurfaceProvider());cam.bindToLifecycle(this,cs,p);layoutPerm.setVisibility(View.GONE);}catch(ExecutionException|InterruptedException e){Toast.makeText(this,"Cam error",Toast.LENGTH_SHORT).show();}},ContextCompat.getMainExecutor(this));
    }

    private boolean allOk(){for(String p:PERMS) if(ContextCompat.checkSelfPermission(this,p)!=PackageManager.PERMISSION_GRANTED) return false;return true;}
    @Override public void onRequestPermissionsResult(int req,@NonNull String[]p,@NonNull int[]g){super.onRequestPermissionsResult(req,p,g);if(req==REQ&&allOk()) startCam();else Toast.makeText(this,"Camera & mic required",Toast.LENGTH_SHORT).show();}

    private void end(){isLive=false;handler.removeCallbacksAndMessages(null);if(liveRef!=null){if(viewerL!=null) liveRef.child("viewers").removeEventListener(viewerL);if(commentL!=null) liveRef.child("comments").removeEventListener(commentL);liveRef.child("isLive").setValue(false);liveRef.removeValue();}if(cam!=null) cam.unbindAll();finish();}
    @Override protected void onDestroy(){if(isLive) end();handler.removeCallbacksAndMessages(null);super.onDestroy();}

    static class Cmt{String name,text;}
    static class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.VH>{
        private final List<Cmt> items; CommentAdapter(List<Cmt> i){items=i;}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int vt){return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_live_comment,p,false));}
        @Override public void onBindViewHolder(@NonNull VH h,int pos){Cmt c=items.get(pos);h.tvName.setText(c.name);h.tvText.setText(c.text);}
        @Override public int getItemCount(){return items.size();}
        static class VH extends RecyclerView.ViewHolder{TextView tvName,tvText;VH(View v){super(v);tvName=v.findViewById(R.id.tv_live_comment_name);tvText=v.findViewById(R.id.tv_live_comment_text);}}
    }
}
