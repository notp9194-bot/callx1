package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

public class ReelTagPeopleActivity extends AppCompatActivity {
    public static final String EXTRA_TAGGED_UIDS = "tagged_uids";
    public static final String RESULT_TAGGED_UIDS = "result_tagged_uids";
    public static final int MAX_TAGS = 10;

    private EditText etSearch;
    private RecyclerView rvContacts;
    private LinearLayout layoutSelected;
    private TextView tvSelectedCount;
    private Button btnDone;
    private ProgressBar progressSearch;
    private ContactAdapter adapter;
    private final List<Contact> all = new ArrayList<>(), filtered = new ArrayList<>();
    private final Map<String,Contact> selected = new LinkedHashMap<>();
    private String myUid;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_tag_people);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        String preTags = getIntent().getStringExtra(EXTRA_TAGGED_UIDS);
        bindViews(); loadContacts(preTags);
    }

    private void bindViews() {
        ((ImageButton)findViewById(R.id.btn_tag_back)).setOnClickListener(v->finish());
        etSearch=findViewById(R.id.et_tag_search); rvContacts=findViewById(R.id.rv_tag_contacts);
        layoutSelected=findViewById(R.id.layout_tag_selected); tvSelectedCount=findViewById(R.id.tv_tag_count);
        btnDone=findViewById(R.id.btn_tag_done); progressSearch=findViewById(R.id.progress_tag_search);
        adapter=new ContactAdapter(filtered,this::toggleSelect);
        rvContacts.setLayoutManager(new LinearLayoutManager(this)); rvContacts.setAdapter(adapter);
        etSearch.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            @Override public void afterTextChanged(Editable s){}
            @Override public void onTextChanged(CharSequence s,int st,int b,int c){filterList(s.toString().toLowerCase().trim());}
        });
        btnDone.setOnClickListener(v->returnResult());
    }

    private void loadContacts(String preTags) {
        progressSearch.setVisibility(View.VISIBLE);
        FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener(){
            @Override public void onDataChange(@NonNull DataSnapshot snap){
                if(isFinishing()||isDestroyed()) return;
                List<String> uids=new ArrayList<>();
                for(DataSnapshot s:snap.getChildren()) uids.add(s.getKey());
                fetchUsers(uids,preTags);
            }
            @Override public void onCancelled(@NonNull DatabaseError e){
                if(isFinishing()||isDestroyed()) return; progressSearch.setVisibility(View.GONE);
            }
        });
    }

    private void fetchUsers(List<String> uids,String preTags) {
        if(uids.isEmpty()){progressSearch.setVisibility(View.GONE);return;}
        Set<String> pre=new HashSet<>();
        if(preTags!=null&&!preTags.isEmpty()) pre.addAll(Arrays.asList(preTags.split(",")));
        final int[]cnt={uids.size()};
        for(String uid:uids){
            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener(){
                @Override public void onDataChange(@NonNull DataSnapshot s){
                    if(!isFinishing()&&!isDestroyed()){
                        Contact c=new Contact(); c.uid=uid;
                        c.name=str(s,"name"); c.username=str(s,"username"); String _t=str(s,"thumbUrl"); String _p=str(s,"photoUrl"); c.photo=(!_t.isEmpty())?_t:_p;
                        if(!c.name.isEmpty()){all.add(c); if(pre.contains(uid)) selected.put(uid,c);}
                    }
                    if(--cnt[0]<=0) finishLoad();
                }
                @Override public void onCancelled(@NonNull DatabaseError e){if(--cnt[0]<=0) finishLoad();}
            });
        }
    }

    private void finishLoad(){
        if(isFinishing()||isDestroyed()) return;
        progressSearch.setVisibility(View.GONE);
        all.sort(Comparator.comparing(c->c.name.toLowerCase()));
        filtered.addAll(all); adapter.notifyDataSetChanged(); refreshChips();
    }

    private void filterList(String q){
        filtered.clear();
        for(Contact c:all) if(q.isEmpty()||c.name.toLowerCase().contains(q)||c.username.toLowerCase().contains(q)) filtered.add(c);
        adapter.notifyDataSetChanged();
    }

    private void toggleSelect(Contact c){
        if(selected.containsKey(c.uid)) selected.remove(c.uid);
        else{
            if(selected.size()>=MAX_TAGS){Toast.makeText(this,"Max "+MAX_TAGS+" tags",Toast.LENGTH_SHORT).show();return;}
            selected.put(c.uid,c);
        }
        adapter.setSelected(new HashSet<>(selected.keySet())); refreshChips();
    }

    private void refreshChips(){
        layoutSelected.removeAllViews();
        tvSelectedCount.setText(selected.size()+"/"+MAX_TAGS+" tagged");
        for(Contact c:selected.values()){
            TextView tv=new TextView(this); tv.setText(c.name+"  ✕");
            tv.setTextColor(0xFFFFFFFF); tv.setBackgroundColor(0xFFFF3B5C);
            tv.setPadding(24,8,24,8); tv.setTextSize(13);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(4,4,4,4); tv.setLayoutParams(lp);
            tv.setOnClickListener(v->{ selected.remove(c.uid); adapter.setSelected(new HashSet<>(selected.keySet())); refreshChips(); });
            layoutSelected.addView(tv);
        }
    }

    private void returnResult(){
        StringBuilder sb=new StringBuilder();
        for(String uid:selected.keySet()){if(sb.length()>0) sb.append(","); sb.append(uid);}
        Intent r=new Intent(); r.putExtra(RESULT_TAGGED_UIDS,sb.toString()); setResult(RESULT_OK,r); finish();
    }

    private String str(DataSnapshot s,String k){String v=s.child(k).getValue(String.class);return v!=null?v:"";}

    static class Contact{String uid,name,username,photo;}

    static class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH>{
        private final List<Contact> items; private final java.util.function.Consumer<Contact> toggle;
        private Set<String> sel=new HashSet<>();
        ContactAdapter(List<Contact> i, java.util.function.Consumer<Contact> t){items=i;toggle=t;}
        void setSelected(Set<String> s){sel=s;notifyDataSetChanged();}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int vt){
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_tag_contact,p,false));}
        @Override public void onBindViewHolder(@NonNull VH h,int pos){
            Contact c=items.get(pos); h.tvName.setText(c.name);
            h.tvUsername.setText(c.username.isEmpty()?"":"@"+c.username);
            h.cbTag.setChecked(sel.contains(c.uid));
            if(!c.photo.isEmpty()) Glide.with(h.ivAvatar).load(c.photo).circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
            h.itemView.setOnClickListener(v->toggle.accept(c));
        }
        @Override public int getItemCount(){return items.size();}
        static class VH extends RecyclerView.ViewHolder{
            CircleImageView ivAvatar; TextView tvName,tvUsername; CheckBox cbTag;
            VH(View v){super(v);ivAvatar=v.findViewById(R.id.iv_tag_avatar);tvName=v.findViewById(R.id.tv_tag_name);tvUsername=v.findViewById(R.id.tv_tag_username);cbTag=v.findViewById(R.id.cb_tag_check);}
        }
    }
}
