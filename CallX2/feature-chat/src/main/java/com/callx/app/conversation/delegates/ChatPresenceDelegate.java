package com.callx.app.conversation.delegates;
import android.app.Activity;import android.view.View;import androidx.annotation.NonNull;
import com.callx.app.chat.databinding.ActivityChatBinding;import com.callx.app.db.AppDatabase;
import com.callx.app.models.Message;import com.callx.app.utils.*;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;import java.util.*;import java.util.concurrent.Executor;
/** Typing indicator, online/last-seen status, mark read. */
public class ChatPresenceDelegate {
    private final Activity activity;private final ActivityChatBinding binding;
    private final String chatId,currentUid,partnerUid;
    private final AppDatabase db;private final Executor ioExecutor;private final DatabaseReference messagesRef;
    private ValueEventListener typingListener,onlineListener;
    public ChatPresenceDelegate(Activity a,ActivityChatBinding b,String chatId,String uid,String puid,AppDatabase db,Executor io,DatabaseReference ref){
        activity=a;binding=b;this.chatId=chatId;currentUid=uid;partnerUid=puid;this.db=db;ioExecutor=io;messagesRef=ref;
    }
    public void setOurTypingStatus(boolean typing){FirebaseUtils.db().getReference("typing").child(chatId).child(currentUid).setValue(typing);}
    public void clearOurTypingStatus(){FirebaseUtils.db().getReference("typing").child(chatId).child(currentUid).setValue(false);}
    public void watchTyping(){
        typingListener=new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot s){
                boolean t=false;for(DataSnapshot c:s.getChildren()){if(c.getKey()!=null&&!c.getKey().equals(currentUid)&&Boolean.TRUE.equals(c.getValue(Boolean.class))){t=true;break;}}
                if(binding.tvTyping==null||binding.tvStatus==null)return;
                if(t){binding.tvTyping.setVisibility(View.VISIBLE);binding.tvStatus.setVisibility(View.GONE);}
                else{binding.tvTyping.setVisibility(View.GONE);binding.tvStatus.setVisibility(binding.tvStatus.getText().length()>0?View.VISIBLE:View.GONE);}
            }
            @Override public void onCancelled(@NonNull DatabaseError e){}
        };
        FirebaseUtils.db().getReference("typing").child(chatId).addValueEventListener(typingListener);
    }
    public void watchPartnerStatus(){
        if(partnerUid==null||partnerUid.isEmpty())return;
        onlineListener=new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot s){
                if(binding.tvStatus==null)return;
                Boolean ghost=s.child("privacy").child("ghost").getValue(Boolean.class);
                if(Boolean.TRUE.equals(ghost)){binding.tvStatus.setVisibility(View.GONE);return;}
                String lsv=s.child("privacy").child("lastSeenVisibility").getValue(String.class);
                boolean hide=SecurityManager.VIS_NOBODY.equals(lsv);
                Boolean online=s.child("online").getValue(Boolean.class);Long ls=s.child("lastSeen").getValue(Long.class);
                String st;
                if(Boolean.TRUE.equals(online)){Boolean incog=s.child("privacy").child("incognito").getValue(Boolean.class);st=Boolean.TRUE.equals(incog)?"":"online";}
                else if(!hide&&ls!=null&&ls>0)st=fmtLastSeen(ls);else st="";
                binding.tvStatus.setText(st);
                boolean typVis=binding.tvTyping!=null&&binding.tvTyping.getVisibility()==View.VISIBLE;
                binding.tvStatus.setVisibility(!typVis&&st.length()>0?View.VISIBLE:View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e){}
        };
        FirebaseUtils.getUserRef(partnerUid).addValueEventListener(onlineListener);
    }
    private String fmtLastSeen(long ts){
        long d=System.currentTimeMillis()-ts;if(d<0)d=0;
        if(d<60000)return"last seen just now";
        if(d<3600000)return"last seen "+(d/60000)+" min ago";
        if(d<86400000)return"last seen at "+new SimpleDateFormat("hh:mm a",Locale.getDefault()).format(new Date(ts));
        if(d<7*86400000L)return"last seen "+new SimpleDateFormat("EEE, hh:mm a",Locale.getDefault()).format(new Date(ts));
        return"last seen "+new SimpleDateFormat("dd MMM",Locale.getDefault()).format(new Date(ts));
    }
    public void markMessagesRead(){
        ioExecutor.execute(()->{if(db!=null&&chatId!=null){db.chatDao().markChatRead(chatId);}});
    }
    public void markRead(Message m){
        if(m==null||m.id==null)return;
        if(!currentUid.equals(m.senderId)&&!"read".equals(m.status)){
            SecurityManager sec=new SecurityManager(activity);
            if(!sec.isReadReceiptsEnabled())return;
            messagesRef.child(m.id).child("status").setValue("read");
            ioExecutor.execute(()->db.messageDao().updateStatus(m.id,"read"));
        }
    }
    public void detach(){
        if(typingListener!=null&&chatId!=null)FirebaseUtils.db().getReference("typing").child(chatId).removeEventListener(typingListener);
        if(onlineListener!=null&&partnerUid!=null)FirebaseUtils.getUserRef(partnerUid).removeEventListener(onlineListener);
    }
}