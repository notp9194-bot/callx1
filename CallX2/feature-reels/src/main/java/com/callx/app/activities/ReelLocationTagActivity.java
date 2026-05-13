package com.callx.app.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import java.util.*;

public class ReelLocationTagActivity extends AppCompatActivity {
    public static final String EXTRA_CURRENT = "current_location";
    public static final String RESULT_NAME   = "place_name";
    public static final String RESULT_LAT    = "place_lat";
    public static final String RESULT_LNG    = "place_lng";
    private static final String PREF = "reel_loc_recents";
    private static final int MAX_RECENT = 5;

    private EditText etSearch;
    private RecyclerView rvPlaces;
    private View layoutEmpty;
    private PlaceAdapter adapter;
    private final List<Place> displayed = new ArrayList<>(), all = buildStatic();

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_location_tag);
        String current = getIntent().getStringExtra(EXTRA_CURRENT);
        bindViews(current); showRecents();
    }

    private void bindViews(String current) {
        ((ImageButton)findViewById(R.id.btn_location_back)).setOnClickListener(v->finish());
        etSearch=findViewById(R.id.et_location_search);
        rvPlaces=findViewById(R.id.rv_location_places);
        layoutEmpty=findViewById(R.id.layout_location_empty);
        TextView tvCurrent=findViewById(R.id.tv_current_location);
        View btnRemove=findViewById(R.id.btn_remove_location);
        if(current!=null&&!current.isEmpty()){
            tvCurrent.setText("Current: "+current); tvCurrent.setVisibility(View.VISIBLE);
            btnRemove.setVisibility(View.VISIBLE);
            btnRemove.setOnClickListener(v->{Intent r=new Intent();r.putExtra(RESULT_NAME,"");setResult(RESULT_OK,r);finish();});
        } else {tvCurrent.setVisibility(View.GONE); btnRemove.setVisibility(View.GONE);}
        adapter=new PlaceAdapter(displayed,this::selectPlace);
        rvPlaces.setLayoutManager(new LinearLayoutManager(this)); rvPlaces.setAdapter(adapter);
        etSearch.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            @Override public void afterTextChanged(Editable s){}
            @Override public void onTextChanged(CharSequence s,int st,int b,int c){filter(s.toString().trim());}
        });
    }

    private void showRecents() {
        displayed.clear();
        SharedPreferences p=getSharedPreferences(PREF,MODE_PRIVATE);
        String raw=p.getString("list","");
        if(!raw.isEmpty()) for(String n:raw.split("\\|")) if(!n.isEmpty()){Place pl=new Place();pl.name=n;pl.isRecent=true;displayed.add(pl);}
        displayed.addAll(all.subList(0,Math.min(10,all.size())));
        adapter.notifyDataSetChanged(); layoutEmpty.setVisibility(displayed.isEmpty()?View.VISIBLE:View.GONE);
    }

    private void filter(String q) {
        displayed.clear();
        if(q.isEmpty()){showRecents();return;}
        for(Place p:all) if(p.name.toLowerCase().contains(q.toLowerCase())) displayed.add(p);
        if(!q.isEmpty()){Place c=new Place();c.name=q;c.isCustom=true;displayed.add(0,c);}
        layoutEmpty.setVisibility(displayed.isEmpty()?View.VISIBLE:View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void selectPlace(Place p) {
        SharedPreferences prefs=getSharedPreferences(PREF,MODE_PRIVATE);
        String raw=prefs.getString("list","");
        List<String> recents=new ArrayList<>(Arrays.asList(raw.split("\\|")));
        recents.remove(p.name); recents.add(0,p.name);
        if(recents.size()>MAX_RECENT) recents=recents.subList(0,MAX_RECENT);
        prefs.edit().putString("list",String.join("|",recents)).apply();
        Intent r=new Intent(); r.putExtra(RESULT_NAME,p.name);
        r.putExtra(RESULT_LAT,p.lat); r.putExtra(RESULT_LNG,p.lng);
        setResult(RESULT_OK,r); finish();
    }

    private static List<Place> buildStatic() {
        String[][] d={{"New York, USA","40.71","-74.00"},{"Los Angeles, USA","34.05","-118.24"},
            {"London, UK","51.50","-0.12"},{"Paris, France","48.85","2.35"},{"Tokyo, Japan","35.67","139.65"},
            {"Dubai, UAE","25.20","55.27"},{"Mumbai, India","19.07","72.87"},{"Delhi, India","28.70","77.10"},
            {"Singapore","1.35","103.81"},{"Sydney, Australia","-33.86","151.20"},
            {"Toronto, Canada","43.65","-79.38"},{"Berlin, Germany","52.52","13.40"},
            {"Barcelona, Spain","41.38","2.17"},{"Istanbul, Turkey","41.00","28.97"},
            {"Cairo, Egypt","30.04","31.23"},{"São Paulo, Brazil","-23.55","-46.63"},
            {"Mexico City, Mexico","19.43","-99.13"},{"Seoul, South Korea","37.56","126.97"},
            {"Jakarta, Indonesia","-6.20","106.84"},{"Bangkok, Thailand","13.75","100.50"}};
        List<Place> list=new ArrayList<>();
        for(String[] row:d){Place p=new Place();p.name=row[0];p.lat=Double.parseDouble(row[1]);p.lng=Double.parseDouble(row[2]);list.add(p);}
        return list;
    }

    static class Place{String name;double lat,lng;boolean isRecent,isCustom;}

    static class PlaceAdapter extends RecyclerView.Adapter<PlaceAdapter.VH>{
        private final List<Place> items; private final java.util.function.Consumer<Place> onSelect;
        PlaceAdapter(List<Place> i, java.util.function.Consumer<Place> s){items=i;onSelect=s;}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int vt){
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_location_place,p,false));}
        @Override public void onBindViewHolder(@NonNull VH h,int pos){
            Place pl=items.get(pos); h.tvName.setText(pl.name);
            h.tvLabel.setText(pl.isRecent?"Recent":pl.isCustom?"Custom":"Place");
            h.itemView.setOnClickListener(v->onSelect.accept(pl));
        }
        @Override public int getItemCount(){return items.size();}
        static class VH extends RecyclerView.ViewHolder{
            TextView tvName,tvLabel;
            VH(View v){super(v);tvName=v.findViewById(R.id.tv_place_name);tvLabel=v.findViewById(R.id.tv_place_label);}
        }
    }
}
