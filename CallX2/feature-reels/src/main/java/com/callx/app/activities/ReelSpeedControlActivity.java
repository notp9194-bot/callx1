package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;

public class ReelSpeedControlActivity extends AppCompatActivity {
    public static final String EXTRA_CURRENT_SPEED="current_speed",RESULT_SPEED="result_speed";
    private static final float[]  SPEEDS={0.3f,0.5f,1.0f,2.0f,3.0f};
    private static final String[] LABELS={"0.3x","0.5x","1x","2x","3x"};
    private static final String[] DESCS={"Very slow motion — dramatic effects","Slow motion — cinematic","Normal speed","2× fast — energetic","3× fast — time-lapse style"};
    private float sel=1.0f;
    private TextView tvLabel,tvDesc;
    private View[] btns;

    @Override protected void onCreate(Bundle s){
        super.onCreate(s);
        setContentView(R.layout.activity_reel_speed_control);
        sel=getIntent().getFloatExtra(EXTRA_CURRENT_SPEED,1.0f);
        bindViews();
    }

    private void bindViews(){
        ((ImageButton)findViewById(R.id.btn_speed_back)).setOnClickListener(v->finish());
        tvLabel=findViewById(R.id.tv_speed_label); tvDesc=findViewById(R.id.tv_speed_desc);
        int[]ids={R.id.btn_speed_03,R.id.btn_speed_05,R.id.btn_speed_1,R.id.btn_speed_2,R.id.btn_speed_3};
        btns=new View[ids.length];
        for(int i=0;i<ids.length;i++){final int idx=i;btns[i]=findViewById(ids[i]);btns[i].setOnClickListener(v->pick(idx));}
        ((Button)findViewById(R.id.btn_speed_apply)).setOnClickListener(v->returnResult());
        ((Button)findViewById(R.id.btn_speed_cancel)).setOnClickListener(v->finish());
        for(int i=0;i<SPEEDS.length;i++) if(Math.abs(SPEEDS[i]-sel)<0.01f){pick(i);break;}
    }

    private void pick(int idx){
        sel=SPEEDS[idx]; tvLabel.setText(LABELS[idx]); tvDesc.setText(DESCS[idx]);
        for(int i=0;i<btns.length;i++){btns[i].setSelected(i==idx);btns[i].setAlpha(i==idx?1f:0.45f);}
    }

    private void returnResult(){Intent r=new Intent();r.putExtra(RESULT_SPEED,sel);setResult(RESULT_OK,r);finish();}
}
