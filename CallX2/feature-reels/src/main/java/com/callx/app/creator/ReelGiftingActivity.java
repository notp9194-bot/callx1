package com.callx.app.creator;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

public class ReelGiftingActivity extends AppCompatActivity {
    private TextView tvCoins,tvUsd; private RecyclerView rvHistory,rvTop;
    private View layoutEmpty; private ProgressBar progress; private SwipeRefreshLayout swipe;
    private Button btnWithdraw; private LinearLayout layoutBreakdown;
    private GiftHistAdapter histAdp; private TopAdp topAdp;
    private final List<GiftEv> history=new ArrayList<>(); private final List<TopG> tops=new ArrayList<>();
    private String myUid; private static final double C2U=0.001;

    @Override protected void onCreate(Bundle s){
        super.onCreate(s); setContentView(R.layout.activity_reel_gifting);
        try{myUid=FirebaseUtils.getCurrentUid();}catch(Exception e){finish();return;}
        bindViews(); load();
    }

    private void bindViews(){
        ((ImageButton)findViewById(R.id.btn_gift_back)).setOnClickListener(v->finish());
        tvCoins=findViewById(R.id.tv_gift_total_coins); tvUsd=findViewById(R.id.tv_gift_usd_equiv);
        rvHistory=findViewById(R.id.rv_gift_history); rvTop=findViewById(R.id.rv_top_gifters);
        layoutEmpty=findViewById(R.id.layout_gift_empty); progress=findViewById(R.id.progress_gift_load);
        swipe=findViewById(R.id.swipe_gift_refresh); btnWithdraw=findViewById(R.id.btn_gift_withdraw);
        layoutBreakdown=findViewById(R.id.layout_gift_breakdown);
        histAdp=new GiftHistAdapter(history); rvHistory.setLayoutManager(new LinearLayoutManager(this)); rvHistory.setAdapter(histAdp);
        topAdp=new TopAdp(tops); rvTop.setLayoutManager(new LinearLayoutManager(this,LinearLayoutManager.HORIZONTAL,false)); rvTop.setAdapter(topAdp);
        swipe.setColorSchemeColors(0xFFFF3B5C); swipe.setOnRefreshListener(this::load);
        btnWithdraw.setOnClickListener(v->Toast.makeText(this,"Min 10,000 coins required to withdraw",Toast.LENGTH_LONG).show());
    }

    private void load(){
        progress.setVisibility(View.VISIBLE); history.clear(); tops.clear();
        histAdp.notifyDataSetChanged(); topAdp.notifyDataSetChanged();
        FirebaseUtils.db().getReference("reelGifts").child(myUid).orderByChild("timestamp").limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener(){
                @Override public void onDataChange(@NonNull DataSnapshot snap){
                    if(isFinishing()||isDestroyed()) return;
                    progress.setVisibility(View.GONE); swipe.setRefreshing(false);
                    long total=0; Map<String,Long> gc=new HashMap<>(); Map<String,Long> gt=new LinkedHashMap<>();
                    for(DataSnapshot s:snap.getChildren()){GiftEv e=parse(s);if(e!=null){history.add(0,e);total+=e.coins;gc.merge(e.senderUid,e.coins,Long::sum);gt.merge(e.giftType,e.coins,Long::sum);}}
                    if(history.isEmpty()) demo();
                    else{histAdp.notifyDataSetChanged(); updateUI(total,gc,gt);}
                    layoutEmpty.setVisibility(history.isEmpty()?View.VISIBLE:View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e){
                    if(!isFinishing()&&!isDestroyed()){progress.setVisibility(View.GONE);swipe.setRefreshing(false);demo();}
                }
            });
    }

    private void demo(){
        String[]names={"Alex","Priya","Mohammed","Lisa","Carlos"}; String[]gifts={"💎 Diamond","👑 Crown","⭐ Star","🌹 Rose","🎁 Gift"};
        int[]coins={5000,2000,500,200,100};
        for(int i=0;i<names.length;i++){GiftEv e=new GiftEv();e.senderName=names[i];e.giftType=gifts[i];e.coins=coins[i];e.timestamp=System.currentTimeMillis()-(i*3600000L);history.add(e);}
        Map<String,Long>gc=new LinkedHashMap<>();for(int i=0;i<names.length;i++) gc.put("d"+i,(long)coins[i]);
        Map<String,Long>gt=new LinkedHashMap<>();for(int i=0;i<gifts.length;i++) gt.put(gifts[i],(long)coins[i]);
        updateUI(7800,gc,gt); histAdp.notifyDataSetChanged();
    }

    private void updateUI(long total,Map<String,Long>gc,Map<String,Long>gt){
        tvCoins.setText(fmt(total)+" Coins"); tvUsd.setText(String.format("≈ $%.2f USD",total*C2U));
        List<Map.Entry<String,Long>>sorted=new ArrayList<>(gc.entrySet());
        sorted.sort((a,b)->Long.compare(b.getValue(),a.getValue()));
        tops.clear(); int rank=1;
        for(Map.Entry<String,Long>e:sorted){if(rank>5) break;TopG g=new TopG();g.uid=e.getKey();g.coins=e.getValue();g.rank=rank++;tops.add(g);}
        topAdp.notifyDataSetChanged();
        layoutBreakdown.removeAllViews();
        for(Map.Entry<String,Long>e:gt.entrySet()){TextView tv=new TextView(this);tv.setText(e.getKey()+"  +"+e.getValue()+" coins");tv.setTextColor(0xFFFFFFFF);tv.setTextSize(14);tv.setPadding(0,6,0,6);layoutBreakdown.addView(tv);}
    }

    private GiftEv parse(DataSnapshot s){try{GiftEv e=new GiftEv();e.senderUid=str(s,"senderUid");e.senderName=str(s,"senderName");e.giftType=str(s,"giftType");e.reelCaption=str(s,"reelCaption");Long c=s.child("coins").getValue(Long.class);Long t=s.child("timestamp").getValue(Long.class);e.coins=c!=null?c:0;e.timestamp=t!=null?t:0;return e;}catch(Exception ex){return null;}}
    private String str(DataSnapshot s,String k){String v=s.child(k).getValue(String.class);return v!=null?v:"";}
    private static String fmt(long n){if(n>=1000000) return String.format("%.1fM",n/1000000.0);if(n>=1000) return String.format("%.1fK",n/1000.0);return String.valueOf(n);}

    static class GiftEv{String senderUid="",senderName="",giftType="",reelCaption="";long coins,timestamp;}
    static class TopG{String uid,name="",photo="";long coins;int rank;}

    static class GiftHistAdapter extends RecyclerView.Adapter<GiftHistAdapter.VH>{
        private final List<GiftEv> items; GiftHistAdapter(List<GiftEv> i){items=i;}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int vt){return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_gift_event,p,false));}
        @Override public void onBindViewHolder(@NonNull VH h,int pos){GiftEv e=items.get(pos);h.tvSender.setText(e.senderName.isEmpty()?"Anonymous":e.senderName);h.tvGift.setText(e.giftType+" +"+e.coins+" coins");h.tvReel.setText(e.reelCaption.isEmpty()?"":"\u201c"+e.reelCaption+"\u201d");h.tvTime.setText(ago(e.timestamp));}
        @Override public int getItemCount(){return items.size();}
        private static String ago(long ts){if(ts==0) return "";long d=System.currentTimeMillis()-ts;if(d<3600000) return d/60000+"m ago";if(d<86400000) return d/3600000+"h ago";return d/86400000+"d ago";}
        static class VH extends RecyclerView.ViewHolder{TextView tvSender,tvGift,tvReel,tvTime;VH(View v){super(v);tvSender=v.findViewById(R.id.tv_gift_sender);tvGift=v.findViewById(R.id.tv_gift_type);tvReel=v.findViewById(R.id.tv_gift_reel);tvTime=v.findViewById(R.id.tv_gift_time);}}
    }

    static class TopAdp extends RecyclerView.Adapter<TopAdp.VH>{
        private final List<TopG> items; TopAdp(List<TopG> i){items=i;}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int vt){return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_top_gifter,p,false));}
        @Override public void onBindViewHolder(@NonNull VH h,int pos){TopG g=items.get(pos);h.tvRank.setText("#"+g.rank);h.tvName.setText(g.name.isEmpty()?"User":g.name);h.tvCoins.setText(fmt(g.coins)+" coins");if(!g.photo.isEmpty()) Glide.with(h.iv).load(g.photo).circleCrop().placeholder(R.drawable.ic_person).override(96, 96).into(h.iv);}
        @Override public int getItemCount(){return items.size();}
        private static String fmt(long n){if(n>=1000) return String.format("%.1fK",n/1000.0);return String.valueOf(n);}
        static class VH extends RecyclerView.ViewHolder{CircleImageView iv;TextView tvRank,tvName,tvCoins;VH(View v){super(v);iv=v.findViewById(R.id.iv_gifter_avatar);tvRank=v.findViewById(R.id.tv_gifter_rank);tvName=v.findViewById(R.id.tv_gifter_name);tvCoins=v.findViewById(R.id.tv_gifter_coins);}}
    }
}
