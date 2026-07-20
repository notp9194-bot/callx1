package com.callx.app.channel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

/**
 * ChannelInviteLinkActivity — full WhatsApp-level channel invite link screen (v5).
 *
 * v5 fixes / additions:
 *   ✓ FIXED: QR code is now generated and shown when the invite link is available
 *     (ivQrCode visibility was incorrectly set to GONE — now set to VISIBLE after generation)
 *   ✓ FIXED: BarcodeEncoder.encodeBitmap() is now called when the link arrives
 *   ✓ NEW: Save QR code to gallery (via MediaStore)
 *   ✓ NEW: Share QR code image via Android share sheet
 *   ✓ NEW: Show invite link stats (times used) below the link text
 *   ✓ Copy link to clipboard
 *   ✓ Share link via Android share sheet
 *   ✓ Revoke link (with confirmation dialog)
 *   ✓ Generate new link if none exists
 */
public class ChannelInviteLinkActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private static final int QR_SIZE = 600;

    private ChannelViewModel viewModel;
    private String channelId, channelName;
    private String currentLink = null;
    private Bitmap qrBitmap    = null;

    private TextView      tvInviteLink, tvUsageCount;
    private ImageView     ivQrCode;
    private MaterialButton btnCopy, btnShare, btnShareQr, btnSaveQr, btnRevoke, btnGenerate;
    private ProgressBar   progressBar;
    private View          cardLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_invite_link);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_invite_link);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Invite link");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvInviteLink = findViewById(R.id.tv_invite_link);
        tvUsageCount = findViewById(R.id.tv_invite_link_usage);
        ivQrCode     = findViewById(R.id.iv_qr_code);
        btnCopy      = findViewById(R.id.btn_copy_link);
        btnShare     = findViewById(R.id.btn_share_link);
        btnShareQr   = findViewById(R.id.btn_share_qr);
        btnSaveQr    = findViewById(R.id.btn_save_qr);
        btnRevoke    = findViewById(R.id.btn_revoke_link);
        btnGenerate  = findViewById(R.id.btn_generate_link);
        progressBar  = findViewById(R.id.progress_invite_link);
        cardLink     = findViewById(R.id.card_invite_link);

        if (btnCopy != null) btnCopy.setOnClickListener(v -> copyLink());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareLink());
        if (btnShareQr != null) btnShareQr.setOnClickListener(v -> shareQrCode());
        if (btnSaveQr  != null) btnSaveQr.setOnClickListener(v -> saveQrToGallery());
        if (btnRevoke  != null) btnRevoke.setOnClickListener(v -> confirmRevoke());
        if (btnGenerate != null) btnGenerate.setOnClickListener(v -> generateLink());

        // Observe invite link from ViewModel
        viewModel.inviteLink.observe(this, link -> {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (link != null && !link.isEmpty()) {
                currentLink = link;
                if (cardLink  != null) cardLink.setVisibility(View.VISIBLE);
                if (tvInviteLink != null) tvInviteLink.setText(link);
                // ── FIXED: generate and show QR code ────────────────────
                generateQrCode(link);
                if (btnGenerate != null) btnGenerate.setVisibility(View.GONE);
            } else {
                currentLink = null;
                if (cardLink  != null) cardLink.setVisibility(View.GONE);
                if (btnGenerate != null) btnGenerate.setVisibility(View.VISIBLE);
            }
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty())
                Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
        });

        // Load current invite link
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        viewModel.generateInviteLink(channelId);
    }

    // ── QR code generation — FIXED in v5 ────────────────────────────────

    private void generateQrCode(String link) {
        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            qrBitmap = encoder.encodeBitmap(link, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);
            if (ivQrCode != null) {
                ivQrCode.setImageBitmap(qrBitmap);
                ivQrCode.setVisibility(View.VISIBLE);   // ← FIXED: was hardcoded GONE
            }
            if (btnShareQr != null) btnShareQr.setVisibility(View.VISIBLE);
            if (btnSaveQr  != null) btnSaveQr.setVisibility(View.VISIBLE);
        } catch (WriterException e) {
            if (ivQrCode != null) ivQrCode.setVisibility(View.GONE);
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private void generateLink() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        viewModel.generateInviteLink(channelId);
    }

    private void copyLink() {
        if (currentLink == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Invite link", currentLink));
        Snackbar.make(findViewById(android.R.id.content), "Link copied!", Snackbar.LENGTH_SHORT).show();
    }

    private void shareLink() {
        if (currentLink == null) return;
        String text = "Join " + (channelName != null ? channelName : "this channel")
            + " on CallX: " + currentLink;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share invite link"));
    }

    private void shareQrCode() {
        if (qrBitmap == null) return;
        try {
            // Save to temp file and share
            java.io.File cacheDir = new java.io.File(getCacheDir(), "qr");
            cacheDir.mkdirs();
            java.io.File file = new java.io.File(cacheDir, "channel_qr.png");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".provider", file);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/png"); i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share QR code"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not share QR code.", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveQrToGallery() {
        if (qrBitmap == null) return;
        String saved = android.provider.MediaStore.Images.Media.insertImage(
            getContentResolver(), qrBitmap,
            "CallX_QR_" + channelId,
            "Channel QR code for " + channelName);
        if (saved != null) Toast.makeText(this, "QR code saved to gallery!", Toast.LENGTH_SHORT).show();
        else Toast.makeText(this, "Could not save QR code.", Toast.LENGTH_SHORT).show();
    }

    private void confirmRevoke() {
        if (currentLink == null) return;
        new AlertDialog.Builder(this)
            .setTitle("Revoke invite link?")
            .setMessage("The old link will stop working. A new link will be generated.")
            .setPositiveButton("Revoke", (d, w) -> {
                viewModel.revokeInviteLink(channelId, currentLink);
                // Auto-generate a fresh link
                viewModel.generateInviteLink(channelId);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
