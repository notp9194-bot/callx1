package com.callx.app.conversation.delegates;
import androidx.lifecycle.LifecycleOwner;import androidx.lifecycle.Transformations;
import androidx.paging.*;import androidx.recyclerview.widget.*;
import com.callx.app.cache.CacheManager;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.db.AppDatabase;import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;import com.callx.app.chat.performance.SwipeOptimizer;
import com.google.firebase.database.*;import android.app.Activity;import android.view.View;
import java.util.concurrent.Executor;
/**
 * ChatPagingDelegate — Paging 3 setup, Firebase real-time listener, Room DB sync.
 */
public class ChatPagingDelegate {
    private static final int PAGE_SIZE=20,PREFETCH=10,INITIAL=40;
    public interface Callback {
        void onMessageAdded(Message m);void runOnUiThread(Runnable r);LifecycleOwner getLifecycleOwner();
    }
    private final Activity activity;private final ActivityChatBinding binding;
    private final String chatId,currentUid;private final AppDatabase db;
    private final Executor ioExecutor;private final DatabaseReference messagesRef;
    private final Callback callback;
    public MessagePagingAdapter pagingAdapter;private ChildEventListener msgListener;

    public ChatPagingDelegate(Activity a,ActivityChatBinding b,String chatId,String uid,
            AppDatabase db,Executor io,DatabaseReference ref,Callback cb){
        activity=a;binding=b;this.chatId=chatId;currentUid=uid;this.db=db;ioExecutor=io;messagesRef=ref;callback=cb;
    }
    public void setupPagingRecyclerView(MessagePagingAdapter.ActionListener al,MessagePagingAdapter.MultiSelectListener ml){
        pagingAdapter=new MessagePagingAdapter(currentUid,false);
        pagingAdapter.setActionListener(al);pagingAdapter.setMultiSelectListener(ml);
        LinearLayoutManager llm=new LinearLayoutManager(activity);llm.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(llm);binding.rvMessages.setAdapter(pagingAdapter);
        SwipeOptimizer.disableChangeAnimations(binding.rvMessages);
        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver(){
            @Override public void onItemRangeInserted(int pos,int cnt){
                int tot=pagingAdapter.getItemCount(),lv=llm.findLastVisibleItemPosition();
                if(lv>=tot-cnt-2)binding.rvMessages.scrollToPosition(tot-1);
            }
        });
        pagingAdapter.addLoadStateListener(st->{
            LoadState r=st.getRefresh();
            if(r instanceof LoadState.Loading){
                binding.shimmerContainer.startShimmer();binding.shimmerContainer.setVisibility(View.VISIBLE);
                binding.llEmptyChat.setVisibility(View.GONE);
            }else{
                binding.shimmerContainer.stopShimmer();binding.shimmerContainer.setVisibility(View.GONE);
                boolean e=pagingAdapter.getItemCount()==0;
                binding.rvMessages.setVisibility(e?View.GONE:View.VISIBLE);
                binding.llEmptyChat.setVisibility(e?View.VISIBLE:View.GONE);
            }return null;
        });
    }
    public void observePagedMessages(){
        Pager<Integer,MessageEntity> pager=new Pager<>(
                new PagingConfig(PAGE_SIZE,PREFETCH,false,INITIAL),()->db.messageDao().getMessagesPagingSource(chatId));
        Transformations.map(PagingLiveData.getLiveData(pager),
                pd->PagingDataTransforms.map(pd,ioExecutor,ChatPagingDelegate::entityToModel))
                .observe(callback.getLifecycleOwner(),pd->pagingAdapter.submitData(callback.getLifecycleOwner().getLifecycle(),pd));
    }
    public void startRealtimeListener(){
        ioExecutor.execute(()->{
            long ts=CacheManager.getInstance(activity).getLastSyncTimestamp(chatId);
            callback.runOnUiThread(()->attachFirebaseListener(ts));
        });
    }
    private void attachFirebaseListener(long lastTs){
        Query q=lastTs>0?messagesRef.orderByChild("timestamp").startAfter((double)lastTs)
                :messagesRef.orderByChild("timestamp").limitToLast(INITIAL);
        msgListener=new ChildEventListener(){
            @Override public void onChildAdded(DataSnapshot s,String p){Message m=s.getValue(Message.class);if(m==null)return;m.id=s.getKey();saveToRoom(m);callback.onMessageAdded(m);}
            @Override public void onChildChanged(DataSnapshot s,String p){Message m=s.getValue(Message.class);if(m==null)return;m.id=s.getKey();saveToRoom(m);}
            @Override public void onChildRemoved(DataSnapshot s){String k=s.getKey();if(k==null)return;ioExecutor.execute(()->db.messageDao().softDelete(k));}
            @Override public void onChildMoved(DataSnapshot s,String p){}
            @Override public void onCancelled(DatabaseError e){}
        };
        q.addChildEventListener(msgListener);
    }
    public void saveToRoom(Message m){ioExecutor.execute(()->db.messageDao().insertMessage(modelToEntity(m)));}
    public void detach(){if(messagesRef!=null&&msgListener!=null)messagesRef.removeEventListener(msgListener);}
    public static Message entityToModel(MessageEntity e){
        Message m=new Message();m.id=e.id;m.messageId=e.id;m.senderId=e.senderId;m.senderName=e.senderName;
        m.senderPhoto=e.senderPhoto;m.text=e.text;m.type=e.type;m.mediaUrl=e.mediaUrl;
        m.imageUrl="image".equals(e.type)?e.mediaUrl:null;m.thumbnailUrl=e.thumbnailUrl;
        m.fileName=e.fileName;m.fileSize=e.fileSize;m.duration=e.duration;m.timestamp=e.timestamp;
        m.status=e.status;m.replyToId=e.replyToId;m.replyToText=e.replyToText;
        m.replyToSenderName=e.replyToSenderName;m.replyToType=e.replyToType;m.replyToMediaUrl=e.replyToMediaUrl;
        m.edited=e.edited;m.deleted=e.deleted;m.forwardedFrom=e.forwardedFrom;m.starred=e.starred;m.pinned=e.pinned;
        m.reelId=e.reelId;m.reelThumbUrl=e.reelThumbUrl;m.fontStyle=e.fontStyle;m.expiresAt=e.expiresAt;return m;
    }
    public MessageEntity modelToEntity(Message m){
        MessageEntity e=new MessageEntity();e.id=m.id!=null?m.id:"";e.chatId=chatId;e.senderId=m.senderId;
        e.senderName=m.senderName;e.senderPhoto=m.senderPhoto;e.text=m.text;e.type=m.type!=null?m.type:"text";
        e.mediaUrl=m.mediaUrl!=null?m.mediaUrl:m.imageUrl;e.thumbnailUrl=m.thumbnailUrl;e.fileName=m.fileName;
        e.fileSize=m.fileSize;e.duration=m.duration;e.timestamp=m.timestamp;e.status=m.status;
        e.replyToId=m.replyToId;e.replyToText=m.replyToText;e.replyToSenderName=m.replyToSenderName;
        e.replyToType=m.replyToType;e.replyToMediaUrl=m.replyToMediaUrl;e.edited=m.edited;e.deleted=m.deleted;
        e.forwardedFrom=m.forwardedFrom;e.starred=Boolean.TRUE.equals(m.starred);e.pinned=Boolean.TRUE.equals(m.pinned);
        e.reelId=m.reelId;e.reelThumbUrl=m.reelThumbUrl;e.fontStyle=m.fontStyle;e.expiresAt=m.expiresAt;
        e.syncedAt=System.currentTimeMillis();return e;
    }
}