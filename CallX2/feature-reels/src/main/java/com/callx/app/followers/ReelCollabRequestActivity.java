package com.callx.app.followers;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

public class ReelCollabRequestActivity extends AppCompatActivity {
    private LinearLayout tabRcv,tabSent; private View indRcv,indSent;
    private RecyclerView rv; private View layoutEmpty; private ProgressBar progress;
    private ReqAdapter adapter; private final List<CR> requests=new ArrayList<>();
    private String myUid,myName; private boolean showRcv=true;

    @Override protected void onCreate(Bundle s){
        super.onCreate(s); setContentView(R.layout.activity_reel_collab_request);
        try{myUid=FirebaseUtils.getCurrentUid();myName=FirebaseUtils.getCurrentName();}catch(Exception e){finish();return;}
        bindViews(); loadRcv();
    }

    private void bindViews(){
        ((ImageButton)findViewById(R.id.btn_collab_req_back)).setOnClickListener(v->finish());
        tabRcv=findViewById(R.id.tab_collab_received); tabSent=findViewById(R.id.tab_collab_sent);
        indRcv=findViewById(R.id.indicator_collab_received); indSent=findViewById(R.id.indicator_collab_sent);
        rv=findViewById(R.id.rv_collab_requests); layoutEmpty=findViewById(R.id.layout_collab_empty);
        progress=findViewById(R.id.progress_collab_load);
        adapter=new ReqAdapter(requests,this::accept,this::decline,this::cancel,showRcv);
        rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(adapter);
        tabRcv.setOnClickListener(v->{showRcv=true;loadRcv();updateTabs();});
        tabSent.setOnClickListener(v->{showRcv=false;loadSent();updateTabs();});
        ((Button)findViewById(R.id.btn_collab_send_new)).setOnClickListener(v->showDialog());
    }

    private void updateTabs(){indRcv.setVisibility(showRcv?View.VISIBLE:View.INVISIBLE);indSent.setVisibility(!showRcv?View.VISIBLE:View.INVISIBLE);adapter.setShowRcv(showRcv);}

    private void loadRcv(){progress.setVisibility(View.VISIBLE);requests.clear();FirebaseUtils.db().getReference("collabRequests").child(myUid).addListenerForSingleValueEvent(new ValueEventListener(){@Override public void onDataChange(@NonNull DataSnapshot snap){if(isFinishing()||isDestroyed()) return;progress.setVisibility(View.GONE);for(DataSnapshot s:snap.getChildren()){CR r=parse(s);if(r!=null&&"pending".equals(r.status)) requests.add(r);}adapter.notifyDataSetChanged();layoutEmpty.setVisibility(requests.isEmpty()?View.VISIBLE:View.GONE);}@Override public void onCancelled(@NonNull DatabaseError e){if(!isFinishing()&&!isDestroyed()) progress.setVisibility(View.GONE);}});}

    private void loadSent(){progress.setVisibility(View.VISIBLE);requests.clear();FirebaseUtils.db().getReference("collabSent").child(myUid).addListenerForSingleValueEvent(new ValueEventListener(){@Override public void onDataChange(@NonNull DataSnapshot snap){if(isFinishing()||isDestroyed()) return;progress.setVisibility(View.GONE);for(DataSnapshot s:snap.getChildren()){CR r=parse(s);if(r!=null) requests.add(r);}adapter.notifyDataSetChanged();layoutEmpty.setVisibility(requests.isEmpty()?View.VISIBLE:View.GONE);}@Override public void onCancelled(@NonNull DatabaseError e){if(!isFinishing()&&!isDestroyed()) progress.setVisibility(View.GONE);}});}

    private void accept(CR r){FirebaseUtils.db().getReference("collabRequests").child(myUid).child(r.fromUid).child("status").setValue("accepted");FirebaseUtils.db().getReference("collabSent").child(r.fromUid).child(myUid).child("status").setValue("accepted");r.status="accepted";adapter.notifyDataSetChanged();Toast.makeText(this,"Collab accepted with "+r.fromName,Toast.LENGTH_SHORT).show();}
    private void decline(CR r){FirebaseUtils.db().getReference("collabRequests").child(myUid).child(r.fromUid).removeValue();requests.remove(r);adapter.notifyDataSetChanged();layoutEmpty.setVisibility(requests.isEmpty()?View.VISIBLE:View.GONE);}
    private void cancel(CR r){FirebaseUtils.db().getReference("collabSent").child(myUid).child(r.toUid).removeValue();FirebaseUtils.db().getReference("collabRequests").child(r.toUid).child(myUid).removeValue();requests.remove(r);adapter.notifyDataSetChanged();layoutEmpty.setVisibility(requests.isEmpty()?View.VISIBLE:View.GONE);}

    private void showDialog(){
        View v=LayoutInflater.from(this).inflate(R.layout.dialog_collab_send,null);
        EditText etUser=v.findViewById(R.id.et_collab_to_username),etMsg=v.findViewById(R.id.et_collab_message);
        Spinner sp=v.findViewById(R.id.sp_collab_type);
        ArrayAdapter<String>sa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"Duet","Stitch","Co-create"});
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);sp.setAdapter(sa);
        new android.app.AlertDialog.Builder(this).setTitle("Send Collab Request").setView(v)
            .setPositiveButton("Send",(d,w)->{String u=etUser.getText()!=null?etUser.getText().toString().trim():"",m=etMsg.getText()!=null?etMsg.getText().toString().trim():"",t=(String)sp.getSelectedItem();if(u.isEmpty()){Toast.makeText(this,"Enter a username",Toast.LENGTH_SHORT).show();return;}sendReq(u,m,t);})
            .setNegativeButton("Cancel",null).show();
    }

    private void sendReq(String username,String msg,String type){
        FirebaseUtils.db().getReference("usernames").child(username).addListenerForSingleValueEvent(new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot s){if(isFinishing()||isDestroyed()) return;String uid=s.getValue(String.class);if(uid==null||uid.isEmpty()){Toast.makeText(ReelCollabRequestActivity.this,"@"+username+" not found",Toast.LENGTH_SHORT).show();return;}if(uid.equals(myUid)){Toast.makeText(ReelCollabRequestActivity.this,"Can't collab with yourself",Toast.LENGTH_SHORT).show();return;}Map<String,Object>d=new HashMap<>();d.put("fromUid",myUid);d.put("fromName",myName);d.put("toUid",uid);d.put("toName",username);d.put("type",type);d.put("message",msg);d.put("status","pending");d.put("timestamp",System.currentTimeMillis());FirebaseUtils.db().getReference("collabRequests").child(uid).child(myUid).setValue(d);FirebaseUtils.db().getReference("collabSent").child(myUid).child(uid).setValue(d);Toast.makeText(ReelCollabRequestActivity.this,"Request sent to @"+username,Toast.LENGTH_SHORT).show();}
            @Override public void onCancelled(@NonNull DatabaseError e){Toast.makeText(ReelCollabRequestActivity.this,"Error searching user",Toast.LENGTH_SHORT).show();}
        });
    }

    private CR parse(DataSnapshot s){try{CR r=new CR();r.fromUid=str(s,"fromUid");r.fromName=str(s,"fromName");r.toUid=str(s,"toUid");r.toName=str(s,"toName");r.type=str(s,"type");r.message=str(s,"message");r.status=str(s,"status");Long t=s.child("timestamp").getValue(Long.class);r.timestamp=t!=null?t:0;return r;}catch(Exception e){return null;}}
    private String str(DataSnapshot s,String k){String v=s.child(k).getValue(String.class);return v!=null?v:"";}

    static class CR{String fromUid="",fromName="",toUid="",toName="",type="",message="",status="";long timestamp;boolean hidden;}

    static class ReqAdapter extends RecyclerView.Adapter<ReqAdapter.VH>{
        private final List<CR> items; private final java.util.function.Consumer<CR> accept,decline,cancel; private boolean showRcv;
        ReqAdapter(List<CR> i,java.util.function.Consumer<CR>a,java.util.function.Consumer<CR>d,java.util.function.Consumer<CR>c,boolean showRcv){items=i;accept=a;decline=d;cancel=c;this.showRcv=showRcv;}
        void setShowRcv(boolean v){showRcv=v;notifyDataSetChanged();}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int vt){return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_collab_request_v2,p,false));}
        @Override public void onBindViewHolder(@NonNull VH h,int pos){CR r=items.get(pos);if(r.hidden){h.itemView.setVisibility(View.GONE);return;}h.itemView.setVisibility(View.VISIBLE);h.tvName.setText(showRcv?r.fromName:r.toName);h.tvType.setText(r.type+" collab");h.tvMsg.setText(r.message.isEmpty()?"":" \""+r.message+"\"");h.tvStatus.setText(r.status.toUpperCase());if(showRcv&&"pending".equals(r.status)){h.btnA.setVisibility(View.VISIBLE);h.btnD.setVisibility(View.VISIBLE);h.btnC.setVisibility(View.GONE);h.btnA.setOnClickListener(v->accept.accept(r));h.btnD.setOnClickListener(v->decline.accept(r));}else if(!showRcv&&"pending".equals(r.status)){h.btnA.setVisibility(View.GONE);h.btnD.setVisibility(View.GONE);h.btnC.setVisibility(View.VISIBLE);h.btnC.setOnClickListener(v->cancel.accept(r));}else{h.btnA.setVisibility(View.GONE);h.btnD.setVisibility(View.GONE);h.btnC.setVisibility(View.GONE);}}
        @Override public int getItemCount(){return items.size();}
        static class VH extends RecyclerView.ViewHolder{TextView tvName,tvType,tvMsg,tvStatus;Button btnA,btnD,btnC;VH(View v){super(v);tvName=v.findViewById(R.id.tv_collab_req_name);tvType=v.findViewById(R.id.tv_collab_req_type);tvMsg=v.findViewById(R.id.tv_collab_req_message);tvStatus=v.findViewById(R.id.tv_collab_req_status);btnA=v.findViewById(R.id.btn_collab_accept);btnD=v.findViewById(R.id.btn_collab_decline);btnC=v.findViewById(R.id.btn_collab_cancel);}}
    }
}
