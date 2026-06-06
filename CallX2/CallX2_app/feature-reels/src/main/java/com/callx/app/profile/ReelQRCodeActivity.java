package com.callx.app.profile;

import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.callx.app.reels.R;
import java.io.*;

public class ReelQRCodeActivity extends AppCompatActivity {
    public static final String EXTRA_REEL_ID="reel_id",EXTRA_REEL_CAPTION="reel_caption";
    private ImageView ivQr; private TextView tvLink,tvCaption;
    private Button btnShare,btnSave,btnCopy; private ProgressBar progress;
    private String reelId,caption,deepLink; private Bitmap qrBmp;

    @Override protected void onCreate(Bundle s){
        super.onCreate(s); setContentView(R.layout.activity_reel_qr_code);
        reelId=getIntent().getStringExtra(EXTRA_REEL_ID); caption=getIntent().getStringExtra(EXTRA_REEL_CAPTION);
        if(reelId==null||reelId.isEmpty()){finish();return;}
        deepLink = com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/" + reelId;
        bindViews(); generate();
    }

    private void bindViews(){
        ((ImageButton)findViewById(R.id.btn_qr_back)).setOnClickListener(v->finish());
        ivQr=findViewById(R.id.iv_qr_code); tvLink=findViewById(R.id.tv_qr_link);
        tvCaption=findViewById(R.id.tv_qr_caption); btnShare=findViewById(R.id.btn_qr_share);
        btnSave=findViewById(R.id.btn_qr_save); btnCopy=findViewById(R.id.btn_qr_copy);
        progress=findViewById(R.id.progress_qr);
        tvLink.setText(deepLink);
        if(caption!=null&&!caption.isEmpty()) tvCaption.setText(caption); else tvCaption.setVisibility(View.GONE);
        btnShare.setEnabled(false); btnSave.setEnabled(false);
        btnShare.setOnClickListener(v->share()); btnSave.setOnClickListener(v->save()); btnCopy.setOnClickListener(v->copy());
    }

    private void generate(){
        progress.setVisibility(View.VISIBLE);
        new Thread(()->{Bitmap b=buildQr(deepLink,600);runOnUiThread(()->{if(isFinishing()||isDestroyed()) return;progress.setVisibility(View.GONE);qrBmp=b;ivQr.setImageBitmap(b);btnShare.setEnabled(true);btnSave.setEnabled(true);});}).start();
    }

    private Bitmap buildQr(String content,int size){
        int m=29,cs=size/(m+4),mg=cs*2,total=cs*(m+4);
        Bitmap bmp=Bitmap.createBitmap(total,total,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bmp); canvas.drawColor(0xFFFFFFFF);
        Paint dark=new Paint();dark.setColor(0xFF111111);
        Paint lite=new Paint();lite.setColor(0xFFFFFFFF);
        Paint brand=new Paint();brand.setColor(0xFFFF3B5C);
        byte[]bytes=content.getBytes();
        for(int row=0;row<m;row++){for(int col=0;col<m;col++){boolean filled;boolean inF=(row<7&&col<7)||(row<7&&col>=m-7)||(row>=m-7&&col<7);if(inF){int r=row%7,c=col%7;filled=r==0||r==6||c==0||c==6||(r>=2&&r<=4&&c>=2&&c<=4);}else{int idx=(row*m+col)%Math.max(1,bytes.length);filled=((row*31+col*17+bytes[idx])%2)==0;}float l=mg+col*cs,t=mg+row*cs;canvas.drawRect(l,t,l+cs,t+cs,filled?dark:lite);}}
        int cx=total/2,cy=total/2,c2=cs*4;
        canvas.drawRoundRect(cx-c2,cy-c2,cx+c2,cy+c2,8,8,brand);
        Paint tp=new Paint();tp.setColor(0xFFFFFFFF);tp.setTextSize(cs*1.5f);tp.setFakeBoldText(true);tp.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("CX",cx,cy+cs*0.5f,tp);
        return bmp;
    }

    private void share(){if(qrBmp==null) return;try{File dir=new File(getCacheDir(),"qr");if(!dir.exists()) dir.mkdirs();File file=new File(dir,"reel_qr_"+reelId+".png");FileOutputStream fos=new FileOutputStream(file);qrBmp.compress(Bitmap.CompressFormat.PNG,100,fos);fos.close();Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",file);Intent i=new Intent(Intent.ACTION_SEND);i.setType("image/png");i.putExtra(Intent.EXTRA_STREAM,uri);i.putExtra(Intent.EXTRA_TEXT,"Check out this reel: "+deepLink);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Share QR Code"));}catch(Exception e){Toast.makeText(this,"Share failed: "+e.getMessage(),Toast.LENGTH_SHORT).show();}}
    private void save(){if(qrBmp==null) return;try{MediaStore.Images.Media.insertImage(getContentResolver(),qrBmp,"CallX_Reel_QR_"+reelId,deepLink);Toast.makeText(this,"Saved to gallery",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Save failed",Toast.LENGTH_SHORT).show();}}
    private void copy(){android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm!=null){cm.setPrimaryClip(android.content.ClipData.newPlainText("Reel Link",deepLink));Toast.makeText(this,"Link copied!",Toast.LENGTH_SHORT).show();}}
}
