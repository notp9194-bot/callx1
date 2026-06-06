package com.callx.app.editor;

import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;

public class ReelTextOverlayActivity extends AppCompatActivity {
    public static final String EXTRA_INITIAL_TEXT="initial_text",EXTRA_INITIAL_COLOR="initial_color";
    public static final String RESULT_TEXT="result_text",RESULT_COLOR="result_color",RESULT_FONT_INDEX="result_font_index",RESULT_SIZE_SP="result_size_sp",RESULT_BG_STYLE="result_bg_style",RESULT_ALIGNMENT="result_alignment";
    private static final int[] PALETTE={0xFFFFFFFF,0xFF000000,0xFFFF3B5C,0xFFFF9500,0xFFFFCC00,0xFF34C759,0xFF00C7BE,0xFF007AFF,0xFF5856D6,0xFFAF52DE,0xFFFF2D55,0xFFFF6B35,0xFFFFD60A,0xFF30D158,0xFF40CBE0,0xFFBF5AF2};
    private static final String[] FONTS={"Default","Bold","Italic","Script","Mono"};
    private static final String[] BGST={"None","Semi","Solid"};

    private EditText etText; private TextView tvPreview,tvSizeLabel;
    private SeekBar seekSize; private LinearLayout layoutColors,layoutFonts,layoutBgStyles,layoutAlignments;
    private int selColor=0xFFFFFFFF,selFont=0,selBg=0,selAlign=0,textSp=24;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_text_overlay);
        bindViews();
        String it=getIntent().getStringExtra(EXTRA_INITIAL_TEXT),ic=getIntent().getStringExtra(EXTRA_INITIAL_COLOR);
        if(it!=null) etText.setText(it);
        if(ic!=null){try{selColor=Color.parseColor(ic);}catch(Exception ignored){}}
        updatePreview();
    }

    private void bindViews(){
        ((ImageButton)findViewById(R.id.btn_overlay_back)).setOnClickListener(v->finish());
        etText=findViewById(R.id.et_overlay_text); tvPreview=findViewById(R.id.tv_overlay_preview);
        seekSize=findViewById(R.id.seek_overlay_size); tvSizeLabel=findViewById(R.id.tv_overlay_size_label);
        layoutColors=findViewById(R.id.layout_overlay_colors); layoutFonts=findViewById(R.id.layout_overlay_fonts);
        layoutBgStyles=findViewById(R.id.layout_overlay_bg); layoutAlignments=findViewById(R.id.layout_overlay_align);
        seekSize.setMax(60); seekSize.setProgress(12);
        seekSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar sb,int p,boolean u){textSp=12+p;tvSizeLabel.setText(textSp+"sp");updatePreview();}
            @Override public void onStartTrackingTouch(SeekBar sb){} @Override public void onStopTrackingTouch(SeekBar sb){}});
        etText.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            @Override public void afterTextChanged(android.text.Editable s){}
            @Override public void onTextChanged(CharSequence s,int st,int b,int c){updatePreview();}});
        ((Button)findViewById(R.id.btn_overlay_done)).setOnClickListener(v->returnResult());
        buildPalette(); buildFonts(); buildBg(); buildAlign();
    }

    private void buildPalette(){layoutColors.removeAllViews();for(int color:PALETTE){View sw=new View(this);int sz=dp(32);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(sz,sz);lp.setMargins(dp(4),0,dp(4),0);sw.setLayoutParams(lp);sw.setBackgroundColor(color);final int c=color;sw.setOnClickListener(v->{selColor=c;updatePreview();});layoutColors.addView(sw);}}
    private void buildFonts(){layoutFonts.removeAllViews();for(int i=0;i<FONTS.length;i++){final int idx=i;TextView tv=new TextView(this);tv.setText(FONTS[i]);tv.setTextSize(13);tv.setPadding(dp(10),dp(5),dp(10),dp(5));tv.setTextColor(0xFFFFFFFF);tv.setBackgroundColor(0xFF333333);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.setMargins(dp(3),0,dp(3),0);tv.setLayoutParams(lp);tv.setOnClickListener(v->{selFont=idx;updatePreview();});layoutFonts.addView(tv);}}
    private void buildBg(){layoutBgStyles.removeAllViews();for(int i=0;i<BGST.length;i++){final int idx=i;TextView tv=new TextView(this);tv.setText(BGST[i]);tv.setTextSize(13);tv.setPadding(dp(10),dp(5),dp(10),dp(5));tv.setTextColor(0xFFFFFFFF);tv.setBackgroundColor(0xFF555555);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.setMargins(dp(3),0,dp(3),0);tv.setLayoutParams(lp);tv.setOnClickListener(v->{selBg=idx;updatePreview();});layoutBgStyles.addView(tv);}}
    private void buildAlign(){layoutAlignments.removeAllViews();String[]labels={"Left","Center","Right"};for(int i=0;i<labels.length;i++){final int idx=i;TextView tv=new TextView(this);tv.setText(labels[i]);tv.setTextSize(13);tv.setPadding(dp(10),dp(5),dp(10),dp(5));tv.setTextColor(0xFFFFFFFF);tv.setBackgroundColor(0xFF444444);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);lp.setMargins(dp(2),0,dp(2),0);tv.setLayoutParams(lp);tv.setGravity(Gravity.CENTER);tv.setOnClickListener(v->{selAlign=idx;updatePreview();});layoutAlignments.addView(tv);}}

    private void updatePreview(){
        String t=etText.getText()!=null?etText.getText().toString():"";
        tvPreview.setText(t.isEmpty()?"Preview text...":t); tvPreview.setTextColor(selColor); tvPreview.setTextSize(textSp);
        int g=selAlign==1?Gravity.CENTER:selAlign==2?Gravity.END:Gravity.START; tvPreview.setGravity(g);
        switch(selBg){case 1:tvPreview.setBackgroundColor(0x88000000);break;case 2:tvPreview.setBackgroundColor(0xFF000000);break;default:tvPreview.setBackgroundColor(Color.TRANSPARENT);}
        Typeface tf=selFont==1?Typeface.DEFAULT_BOLD:selFont==4?Typeface.MONOSPACE:Typeface.DEFAULT;
        tvPreview.setTypeface(tf,selFont==2?Typeface.ITALIC:Typeface.NORMAL);
    }

    private void returnResult(){
        String t=etText.getText()!=null?etText.getText().toString().trim():"";
        if(t.isEmpty()){finish();return;}
        Intent r=new Intent(); r.putExtra(RESULT_TEXT,t); r.putExtra(RESULT_COLOR,String.format("#%06X",0xFFFFFF&selColor));
        r.putExtra(RESULT_FONT_INDEX,selFont); r.putExtra(RESULT_SIZE_SP,textSp); r.putExtra(RESULT_BG_STYLE,selBg); r.putExtra(RESULT_ALIGNMENT,selAlign);
        setResult(RESULT_OK,r); finish();
    }
    private int dp(int d){return(int)(d*getResources().getDisplayMetrics().density);}
}
