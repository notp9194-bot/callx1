package com.callx.app.conversation.delegates;
import android.app.Activity;import android.widget.Toast;
import androidx.annotation.NonNull;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.db.AppDatabase;import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;import com.callx.app.utils.*;
import com.google.firebase.database.*;
import java.util.*; import java.util.concurrent.*;
/**
 * ChatSenderDelegate — Text message send, Firebase push (local-first), offline retry,
 *                      draft save/restore, send-button state.
 */
public class ChatSenderDelegate {
    public static final int MAX_MSG_LEN=4000;
    public interface Callback {
        boolean isOnline();boolean isMuted();Message buildOutgoing();
        void clearReply();void runOnUiThread(Runnable r);void clearOurTypingStatus();
    }
    private final Activity activity;private final ActivityChatBinding binding;
    private final String chatId,currentUid,partnerUid,currentName;
    private final AppDatabase db;private final Executor ioExecutor;
    private final DatabaseReference messagesRef;private final Callback callback;

    public ChatSenderDelegate(Activity a,ActivityChatBinding b,String chatId,String uid,
            String puid,String name,AppDatabase db,Executor io,DatabaseReference ref,Callback cb){
        activity=a;binding=b;this.chatId=chatId;currentUid=uid;partnerUid=puid;currentName=name;
        this.db=db;ioExecutor=io;messagesRef=ref;callback=cb;
    }
    public void sendTextMessage(){
        String text=binding.etMessage.getText().toString().trim();
        if(text.isEmpty())return;
        if(text.length()>MAX_MSG_LEN){Toast.makeText(activity,"Message too long (max "+MAX_MSG_LEN+" chars)",Toast.LENGTH_SHORT).show();return;}
        binding.etMessage.setText("");
        callback.clearOurTypingStatus();
        Executors.newSingleThreadExecutor().execute(()->{if(db!=null)db.chatDao().saveDraft(chatId,"");});
        Message m=callback.buildOutgoing();m.type="text";
        m.fontStyle=TypingStyleManager.get(activity).getCurrentStyle();
        if(m.fontStyle==TypingStyleManager.STYLE_SAMSUNG_SCRIPT)text=UnicodeStyler.toScript(text);
        m.text=text;pushMessage(m,text);callback.clearReply();
    }
    public void pushMessage(Message m,String preview){
        String key=messagesRef.push().getKey();if(key==null)return;m.id=key;
        ChatPrivacyManager pm=new ChatPrivacyManager(activity,chatId,false);
        long dis=pm.getDisappearingMs();if(dis>0)m.expiresAt=m.timestamp+dis;
        MessageEntity ent=messageToEntity(m,"pending");
        Executors.newSingleThreadExecutor().execute(()->
                AppDatabase.getInstance(activity.getApplicationContext()).messageDao().insertMessage(ent));
        if(callback.isOnline())firebasePushMessage(m,key,preview);
        else Toast.makeText(activity,"No connection — message queued",Toast.LENGTH_SHORT).show();
    }
    public void firebasePushMessage(Message m,String key,String preview){
        messagesRef.child(key).setValue(m).addOnSuccessListener(v->
                Executors.newSingleThreadExecutor().execute(()->
                        AppDatabase.getInstance(activity.getApplicationContext()).messageDao().updateStatus(key,"sent")));
        long ts=m.timestamp;
        Map<String,Object> my=new HashMap<>();my.put("lastMessage",preview);my.put("lastTs",ts);
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid).updateChildren(my);
        Map<String,Object> their=new HashMap<>();their.put("lastMessage",preview);their.put("lastTs",ts);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).updateChildren(their);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).child("unread")
                .addListenerForSingleValueEvent(new ValueEventListener(){
                    @Override public void onDataChange(@NonNull DataSnapshot s){Long c=s.getValue(Long.class);s.getRef().setValue((c!=null?c:0)+1);}
                    @Override public void onCancelled(@NonNull DatabaseError e){}
                });
        if(!callback.isMuted())PushNotify.notifyMessage(partnerUid,currentUid,currentName,chatId,m.id,preview,m.type!=null?m.type:"text",m.mediaUrl!=null?m.mediaUrl:"");
    }
    public void retryPendingMessages(){
        Executors.newSingleThreadExecutor().execute(()->{
            AppDatabase localDb=AppDatabase.getInstance(activity.getApplicationContext());
            List<MessageEntity> pending=localDb.messageDao().getPendingMessages(chatId);
            if(pending==null||pending.isEmpty())return;
            for(MessageEntity pe:pending){
                Message m=entityToMsg(pe);String preview=pe.text!=null?pe.text:"["+pe.type+"]";
                callback.runOnUiThread(()->firebasePushMessage(m,pe.id,preview));
            }
        });
    }
    public void updateSendButtonState(boolean online){
        if(binding==null)return;
        binding.btnSend.setEnabled(online);binding.btnSend.setAlpha(online?1f:.4f);
        binding.btnMic.setEnabled(online);binding.btnMic.setAlpha(online?1f:.4f);
    }
    public void saveDraft(){
        if(db==null||chatId==null||binding==null)return;
        String d=binding.etMessage.getText()!=null?binding.etMessage.getText().toString():"";
        Executors.newSingleThreadExecutor().execute(()->db.chatDao().saveDraft(chatId,d));
    }
    public void restoreDraft(){
        if(db==null||chatId==null)return;
        Executors.newSingleThreadExecutor().execute(()->{
            String d=db.chatDao().getDraft(chatId);
            if(d!=null&&!d.isEmpty())callback.runOnUiThread(()->{
                if(binding!=null&&binding.etMessage!=null){binding.etMessage.setText(d);binding.etMessage.setSelection(d.length());}
            });
        });
    }
    public MessageEntity messageToEntity(Message m,String status){
        MessageEntity e=new MessageEntity();e.id=m.id;e.chatId=chatId;e.senderId=m.senderId;
        e.senderName=m.senderName;e.text=m.text;e.type=m.type;e.mediaUrl=m.mediaUrl;
        e.thumbnailUrl=m.thumbnailUrl;e.fileName=m.fileName;e.fileSize=m.fileSize;
        e.duration=m.duration;e.timestamp=m.timestamp;e.status=status;e.replyToId=m.replyToId;
        e.replyToText=m.replyToText;e.replyToSenderName=m.replyToSenderName;e.replyToType=m.replyToType;
        e.replyToMediaUrl=m.replyToMediaUrl;e.isGroup=false;e.syncedAt=System.currentTimeMillis();
        e.fontStyle=m.fontStyle;e.expiresAt=m.expiresAt;return e;
    }
    private Message entityToMsg(MessageEntity p){
        Message m=new Message();m.id=p.id;m.senderId=p.senderId;m.senderName=p.senderName;
        m.text=p.text;m.type=p.type;m.mediaUrl=p.mediaUrl;m.thumbnailUrl=p.thumbnailUrl;
        m.fileName=p.fileName;m.fileSize=p.fileSize;m.duration=p.duration;m.timestamp=p.timestamp;
        m.status="sent";m.replyToId=p.replyToId;m.replyToText=p.replyToText;
        m.replyToSenderName=p.replyToSenderName;m.fontStyle=p.fontStyle;return m;
    }
}