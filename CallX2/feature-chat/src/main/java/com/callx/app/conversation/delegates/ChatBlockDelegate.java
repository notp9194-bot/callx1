package com.callx.app.conversation.delegates;
import android.app.Activity;import android.app.AlertDialog;import android.view.*;import android.widget.*;
import androidx.annotation.NonNull;
import com.callx.app.chat.R;import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.models.Message;import com.callx.app.utils.*;import com.callx.app.views.EmojiRainView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.*;import com.google.firebase.database.*;import java.util.*;
/** Block, mute, perma-block, special requests, unblock-joy. */
public class ChatBlockDelegate {
    private static final int MAX_REQ=3;
    public interface Callback{void applyBlockUiChanged();void invalidateOptionsMenu();}
    private final Activity activity;private final ActivityChatBinding binding;
    private final String chatId,currentUid,partnerUid,partnerName,currentName;private final Callback callback;
    private ValueEventListener blockListener,permaBlockListener,myPermaBlockListener;
    public boolean isMuted=false,isBlocked=false,partnerPermaBlockedMe=false,iPermaBlockedPartner=false;
    public ChatBlockDelegate(Activity a,ActivityChatBinding b,String chatId,String uid,String puid,String pname,String cname,Callback cb){
        activity=a;binding=b;this.chatId=chatId;currentUid=uid;partnerUid=puid;partnerName=pname;currentName=cname;callback=cb;
    }
    public void watchMute(){
        if(currentUid==null||partnerUid==null)return;
        FirebaseUtils.db().getReference("muted").child(currentUid).child(partnerUid).addValueEventListener(new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot s){isMuted=Boolean.TRUE.equals(s.getValue(Boolean.class));callback.invalidateOptionsMenu();}
            @Override public void onCancelled(@NonNull DatabaseError e){}
        });
    }
    public void toggleMute(){FirebaseUtils.db().getReference("muted").child(currentUid).child(partnerUid).setValue(!isMuted);}
    public void watchBlock(){
        if(currentUid==null||partnerUid==null)return;
        blockListener=new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot s){isBlocked=Boolean.TRUE.equals(s.getValue(Boolean.class));callback.applyBlockUiChanged();}
            @Override public void onCancelled(@NonNull DatabaseError e){}
        };
        FirebaseUtils.getBlocksRef(currentUid).child(partnerUid).addValueEventListener(blockListener);
    }
    public void applyBlockUi(){
        binding.etMessage.setEnabled(!isBlocked);
        if(isBlocked){
            binding.etMessage.setHint("You have blocked "+partnerName);
            binding.btnSend.setVisibility(View.GONE);binding.btnMic.setVisibility(View.GONE);
            binding.llBlockBanner.setVisibility(View.VISIBLE);
            binding.tvBlockBannerText.setText(iPermaBlockedPartner?"You have permanently blocked "+partnerName:"You have blocked "+partnerName);
            if(!iPermaBlockedPartner){binding.btnPermanentBlock.setVisibility(View.VISIBLE);binding.btnPermanentBlock.setOnClickListener(v->confirmPermanentBlock());}
            else binding.btnPermanentBlock.setVisibility(View.GONE);
        }else{binding.etMessage.setHint(activity.getString(R.string.hint_message));binding.btnMic.setVisibility(View.VISIBLE);binding.llBlockBanner.setVisibility(View.GONE);}
    }
    public void confirmBlockUser(){
        String lbl=isBlocked?"Unblock":"Block";
        new AlertDialog.Builder(activity).setTitle(lbl+" "+partnerName+"?")
                .setPositiveButton(lbl,(d,w)->FirebaseUtils.getBlocksRef(currentUid).child(partnerUid).setValue(!isBlocked))
                .setNegativeButton("Cancel",null).show();
    }
    public void confirmPermanentBlock(){
        new AlertDialog.Builder(activity).setTitle("Permanently Block "+partnerName+"?")
                .setMessage("They cannot contact you ever again. Cannot be undone.")
                .setPositiveButton("Permanent Block",(d,w)->{
                    FirebaseUtils.db().getReference("permaBlocked").child(currentUid).child(partnerUid).setValue(true);
                    FirebaseUtils.getBlocksRef(currentUid).child(partnerUid).setValue(true);
                    Toast.makeText(activity,partnerName+" permanently blocked.",Toast.LENGTH_LONG).show();
                    binding.llBlockBanner.setVisibility(View.GONE);
                }).setNegativeButton("Cancel",null).show();
    }
    public void watchPartnerPermaBlock(){
        if(partnerUid==null||currentUid==null)return;
        permaBlockListener=new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot s){
                partnerPermaBlockedMe=Boolean.TRUE.equals(s.getValue(Boolean.class));applyPermaBlockUi();
            }
            @Override public void onCancelled(@NonNull DatabaseError e){}
        };
        FirebaseUtils.db().getReference("permaBlocked").child(partnerUid).child(currentUid).addValueEventListener(permaBlockListener);
    }
    public void watchMyPermaBlock(){
        if(currentUid==null||partnerUid==null)return;
        myPermaBlockListener=new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot s){iPermaBlockedPartner=Boolean.TRUE.equals(s.getValue(Boolean.class));callback.applyBlockUiChanged();}
            @Override public void onCancelled(@NonNull DatabaseError e){}
        };
        FirebaseUtils.db().getReference("permaBlocked").child(currentUid).child(partnerUid).addValueEventListener(myPermaBlockListener);
    }
    private void applyPermaBlockUi(){
        if(!partnerPermaBlockedMe){binding.etMessage.setEnabled(true);binding.etMessage.setHint(activity.getString(R.string.hint_message));return;}
        binding.etMessage.setEnabled(false);binding.etMessage.setHint(partnerName+" has blocked you");
        binding.btnSend.setVisibility(View.GONE);binding.btnMic.setVisibility(View.GONE);
        FirebaseUtils.db().getReference("specialRequests").child(partnerUid).child(currentUid).child("attemptCount").get().addOnSuccessListener(snap->{
            long cnt=snap.exists()&&snap.getValue(Long.class)!=null?snap.getValue(Long.class):0L;
            if(cnt>=MAX_REQ)Snackbar.make(binding.getRoot(),partnerName+" permanently blocked you. No more requests.",Snackbar.LENGTH_LONG).show();
            else Snackbar.make(binding.getRoot(),partnerName+" has permanently blocked you",Snackbar.LENGTH_INDEFINITE)
                    .setAction("Send request ("+(MAX_REQ-cnt)+" left)",v->openSpecialRequestDialog()).show();
        });
    }
    public void checkAndShowUnblockJoy(){
        if(currentUid==null||partnerUid==null)return;
        boolean fromNotif=activity.getIntent().getBooleanExtra("show_unblock_joy",false);
        FirebaseUtils.db().getReference("unblockEvents").child(currentUid).child(partnerUid).get().addOnSuccessListener(snap->{
            if(!snap.exists())return;
            String name=snap.child("unblockedBy").getValue(String.class);if(name==null)name=partnerName;
            snap.getRef().removeValue();final String dn=name;
            binding.getRoot().postDelayed(()->showUnblockJoySheet(dn),fromNotif?400:800);
        });
    }
    private void showUnblockJoySheet(String name){
        if(activity.isFinishing()||activity.isDestroyed())return;
        View sheet=LayoutInflater.from(activity).inflate(R.layout.dialog_unblock_joy,null);
        android.widget.TextView tvName=sheet.findViewById(R.id.tv_joy_name);tvName.setText(name);
        EmojiRainView rain=sheet.findViewById(R.id.emoji_rain_joy);rain.setHappyMode(true);rain.startRain();
        MaterialButton btnOpen=sheet.findViewById(R.id.btn_joy_open_chat);
        MaterialButton btnLater=sheet.findViewById(R.id.btn_joy_later);
        de.hdodenhof.circleimageview.CircleImageView iv=sheet.findViewById(R.id.iv_joy_avatar);
        String pp=activity.getIntent().getStringExtra("partnerPhoto");
        if(pp!=null&&!pp.isEmpty())com.bumptech.glide.Glide.with(activity).load(pp).into(iv);
        BottomSheetDialog dlg=new BottomSheetDialog(activity,com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog);
        dlg.setContentView(sheet);dlg.setCancelable(true);dlg.setOnDismissListener(d->rain.stopRain());
        btnOpen.setOnClickListener(v->dlg.dismiss());btnLater.setOnClickListener(v->dlg.dismiss());dlg.show();
    }
    public void checkAndShowPendingSpecialRequest(){
        if(currentUid==null||partnerUid==null)return;
        DatabaseReference reqRef=FirebaseUtils.db().getReference("specialRequests").child(currentUid).child(partnerUid);
        DatabaseReference seenRef=FirebaseUtils.db().getReference("seenRequests").child(currentUid).child(partnerUid);
        reqRef.get().addOnSuccessListener(rs->{
            if(!rs.exists())return;Long rt=rs.child("ts").getValue(Long.class);if(rt==null)return;
            seenRef.get().addOnSuccessListener(ss->{
                Long sa=ss.exists()?ss.getValue(Long.class):0L;if(sa==null)sa=0L;if(rt<=sa)return;
                seenRef.setValue(rt).addOnSuccessListener(v->{
                    String fn=rs.child("fromName").getValue(String.class);
                    String fp=rs.child("fromPhoto").getValue(String.class);
                    String txt=rs.child("text").getValue(String.class);
                    android.content.Intent popup=new android.content.Intent();
                    popup.setClassName(activity.getPackageName(),"com.callx.app.activities.SpecialRequestPopupActivity");
                    popup.putExtra("fromUid",partnerUid);popup.putExtra("fromName",fn!=null?fn:partnerName);
                    popup.putExtra("fromPhoto",fp!=null?fp:"");popup.putExtra("text",txt!=null?txt:"Please unblock me");
                    activity.startActivity(popup);
                });
            });
        });
    }
    public void openSpecialRequestDialog(){
        FirebaseUtils.db().getReference("specialRequests").child(partnerUid).child(currentUid).get().addOnSuccessListener(snap->{
            long cnt=0,lastTs=0;
            if(snap.exists()){Long c=snap.child("attemptCount").getValue(Long.class);Long t=snap.child("ts").getValue(Long.class);if(c!=null)cnt=c;if(t!=null)lastTs=t;}
            if(cnt>=MAX_REQ){FirebaseUtils.db().getReference("permaBlocked").child(partnerUid).child(currentUid).setValue(true);Toast.makeText(activity,"All attempts used. Permanently blocked.",Toast.LENGTH_LONG).show();return;}
            long elapsed=System.currentTimeMillis()-lastTs,cd=24L*60*60*1000;
            if(lastTs>0&&elapsed<cd){long h=(cd-elapsed)/(1000*60*60),mn=((cd-elapsed)%(1000*60*60))/(1000*60);Toast.makeText(activity,"Wait "+h+"h "+mn+"m before next request.",Toast.LENGTH_LONG).show();return;}
            showSendRequestSheet(cnt);
        });
    }
    private void showSendRequestSheet(long curCnt){
        View sheet=LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_special_request,null);
        android.widget.EditText et=sheet.findViewById(R.id.et_request_message);
        MaterialButton btnSend=sheet.findViewById(R.id.btn_request_send);
        MaterialButton btnCancel=sheet.findViewById(R.id.btn_request_cancel);
        EmojiRainView rain=sheet.findViewById(R.id.emoji_rain_request);
        long rem=MAX_REQ-curCnt;if(et!=null)et.setHint("Request... ("+rem+" attempt"+(rem==1?"":"s")+" left)");
        if(rain!=null)rain.startRain();
        BottomSheetDialog dlg=new BottomSheetDialog(activity,com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog);
        dlg.setContentView(sheet);dlg.setCancelable(true);if(rain!=null)dlg.setOnDismissListener(d->rain.stopRain());
        if(btnCancel!=null)btnCancel.setOnClickListener(v->dlg.dismiss());
        if(btnSend!=null)btnSend.setOnClickListener(v->{
            String txt=et!=null?et.getText().toString().trim():"";if(txt.isEmpty())txt="Please unblock me";
            FirebaseUser me=FirebaseAuth.getInstance().getCurrentUser();String ph=me!=null&&me.getPhotoUrl()!=null?me.getPhotoUrl().toString():"";
            long nc=curCnt+1;Map<String,Object> entry=new HashMap<>();entry.put("text",txt);entry.put("ts",System.currentTimeMillis());entry.put("fromName",currentName);entry.put("fromUid",currentUid);entry.put("fromPhoto",ph);entry.put("attemptCount",nc);
            FirebaseUtils.db().getReference("specialRequests").child(partnerUid).child(currentUid).setValue(entry);
            if(nc>=MAX_REQ){FirebaseUtils.db().getReference("permaBlocked").child(partnerUid).child(currentUid).setValue(true);Toast.makeText(activity,"Last attempt. Permanently blocked.",Toast.LENGTH_LONG).show();}
            else Toast.makeText(activity,"Request sent ("+nc+"/"+MAX_REQ+")",Toast.LENGTH_SHORT).show();
            PushNotify.notifySpecialRequest(partnerUid,currentUid,currentName,ph,txt);dlg.dismiss();
        });
        dlg.show();
    }
    public void detach(){
        if(blockListener!=null&&currentUid!=null&&partnerUid!=null)FirebaseUtils.getBlocksRef(currentUid).child(partnerUid).removeEventListener(blockListener);
        if(permaBlockListener!=null&&partnerUid!=null&&currentUid!=null)FirebaseUtils.db().getReference("permaBlocked").child(partnerUid).child(currentUid).removeEventListener(permaBlockListener);
        if(myPermaBlockListener!=null&&currentUid!=null&&partnerUid!=null)FirebaseUtils.db().getReference("permaBlocked").child(currentUid).child(partnerUid).removeEventListener(myPermaBlockListener);
    }
}