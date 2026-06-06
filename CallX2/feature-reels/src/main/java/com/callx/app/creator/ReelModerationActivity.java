package com.callx.app.creator;

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

public class ReelModerationActivity extends AppCompatActivity {
    private Switch switchEnabled; private EditText etKeyword; private ImageButton btnAdd;
    private RecyclerView rvKeywords; private TextView tvHidden; private ProgressBar progress;
    private KeywordAdapter adapter; private final List<String> keywords=new ArrayList<>();
    private String myUid; private boolean modEnabled=true;

    @Override protected void onCreate(Bundle s){
        super.onCreate(s); setContentView(R.layout.activity_reel_moderation);
        try{myUid=FirebaseUtils.getCurrentUid();}catch(Exception e){finish();return;}
        bindViews(); load();
    }

    private void bindViews(){
        ((ImageButton)findViewById(R.id.btn_mod_back)).setOnClickListener(v->finish());
        switchEnabled=findViewById(R.id.switch_mod_enabled); etKeyword=findViewById(R.id.et_mod_keyword);
        btnAdd=findViewById(R.id.btn_mod_add_keyword); rvKeywords=findViewById(R.id.rv_mod_keywords);
        tvHidden=findViewById(R.id.tv_mod_hidden_count); progress=findViewById(R.id.progress_mod_load);
        adapter=new KeywordAdapter(keywords,this::remove);
        rvKeywords.setLayoutManager(new LinearLayoutManager(this)); rvKeywords.setAdapter(adapter);
        switchEnabled.setOnCheckedChangeListener((b,c)->{modEnabled=c;save("enabled",c);});
        btnAdd.setOnClickListener(v->addKw());
        etKeyword.setOnEditorActionListener((tv,a,e)->{addKw();return true;});
    }

    private void load(){
        progress.setVisibility(View.VISIBLE);
        FirebaseUtils.getUserRef(myUid).child("moderation").addListenerForSingleValueEvent(new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot s){
                if(isFinishing()||isDestroyed()) return; progress.setVisibility(View.GONE);
                Boolean en=s.child("enabled").getValue(Boolean.class); modEnabled=en==null||en; switchEnabled.setChecked(modEnabled);
                keywords.clear(); for(DataSnapshot kw:s.child("keywords").getChildren()){String w=kw.getValue(String.class);if(w!=null&&!w.isEmpty()) keywords.add(w);}
                adapter.notifyDataSetChanged();
                Long h=s.child("totalHiddenCount").getValue(Long.class); tvHidden.setText("Auto-hidden comments: "+(h!=null?h:0));
            }
            @Override public void onCancelled(@NonNull DatabaseError e){if(!isFinishing()&&!isDestroyed()) progress.setVisibility(View.GONE);}
        });
    }

    private void addKw(){
        String w=etKeyword.getText()!=null?etKeyword.getText().toString().trim().toLowerCase():"";
        if(w.isEmpty()) return;
        if(keywords.contains(w)){Toast.makeText(this,"Already exists",Toast.LENGTH_SHORT).show();return;}
        if(keywords.size()>=100){Toast.makeText(this,"Max 100 keywords",Toast.LENGTH_SHORT).show();return;}
        keywords.add(w); adapter.notifyItemInserted(keywords.size()-1); etKeyword.setText(""); saveKws();
    }

    private void remove(int pos){if(pos<0||pos>=keywords.size()) return;keywords.remove(pos);adapter.notifyItemRemoved(pos);saveKws();}
    private void saveKws(){FirebaseUtils.getUserRef(myUid).child("moderation").child("keywords").setValue(new ArrayList<>(keywords));}
    private void save(String k,Object v){FirebaseUtils.getUserRef(myUid).child("moderation").child(k).setValue(v);}

    static class KeywordAdapter extends RecyclerView.Adapter<KeywordAdapter.VH>{
        private final List<String> items; private final java.util.function.IntConsumer onRemove;
        KeywordAdapter(List<String> i,java.util.function.IntConsumer r){items=i;onRemove=r;}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int vt){return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_mod_keyword,p,false));}
        @Override public void onBindViewHolder(@NonNull VH h,int pos){h.tv.setText(items.get(pos));h.btn.setOnClickListener(v->onRemove.accept(h.getAdapterPosition()));}
        @Override public int getItemCount(){return items.size();}
        static class VH extends RecyclerView.ViewHolder{TextView tv;ImageButton btn;VH(View v){super(v);tv=v.findViewById(R.id.tv_keyword);btn=v.findViewById(R.id.btn_keyword_remove);}}
    }
}
