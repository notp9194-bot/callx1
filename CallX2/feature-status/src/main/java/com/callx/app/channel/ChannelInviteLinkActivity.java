package com.callx.app.channel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import android.graphics.Bitmap;
import android.widget.ImageView;

/**
 * ChannelInviteLinkActivity — WhatsApp-level invite link management for private channels.
 *
 * Features:
 *   - Display current invite link (or "no link yet" state)
 *   - Generate / regenerate link
 *   - Copy link to clipboard
 *   - Share link via Android share sheet
 *   - QR code display for the invite link
 *   - Revoke (disable) current link
 *   - Info about who can use the link
 */
public class ChannelInviteLinkActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private ChannelViewModel viewModel;
    private String channelId, channelName;
    private String currentInviteLink;
    private String currentInviteCode;

    private TextView       tvInviteLink, tvLinkStatus;
    private ImageView      ivQrCode;
    private MaterialButton btnGenerate, btnCopy, btnShare, btnRevoke;
    private View           layoutNoLink, layoutHasLink;

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
        tvLinkStatus = findViewById(R.id.tv_link_status);
        ivQrCode     = findViewById(R.id.iv_qr_code);
        btnGenerate  = findViewById(R.id.btn_generate_link);
        btnCopy      = findViewById(R.id.btn_copy_link);
        btnShare     = findViewById(R.id.btn_share_link);
        btnRevoke    = findViewById(R.id.btn_revoke_link);
        layoutNoLink = findViewById(R.id.layout_no_link);
        layoutHasLink= findViewById(R.id.layout_has_link);

        if (btnGenerate != null) btnGenerate.setOnClickListener(v -> generateLink());
        if (btnCopy     != null) btnCopy.setOnClickListener(v -> copyLink());
        if (btnShare    != null) btnShare.setOnClickListener(v -> shareLink());
        if (btnRevoke   != null) btnRevoke.setOnClickListener(v -> confirmRevoke());

        viewModel.inviteLink.observe(this, link -> {
            if (link != null && !link.isEmpty()) {
                currentInviteLink = link;
                showLinkUI(link);
            }
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        // Load current channel to check existing invite link
        viewModel.getChannel(channelId).observe(this, ch -> {
            if (ch == null) return;
            currentInviteCode = ch.inviteCode;
            if (ch.inviteLink != null && !ch.inviteLink.isEmpty()) {
                currentInviteLink = ch.inviteLink;
                showLinkUI(ch.inviteLink);
            } else {
                showNoLinkUI();
            }
        });
    }

    private void showNoLinkUI() {
        if (layoutNoLink  != null) layoutNoLink.setVisibility(View.VISIBLE);
        if (layoutHasLink != null) layoutHasLink.setVisibility(View.GONE);
    }

    private void showLinkUI(String link) {
        if (layoutNoLink  != null) layoutNoLink.setVisibility(View.GONE);
        if (layoutHasLink != null) layoutHasLink.setVisibility(View.VISIBLE);
        if (tvInviteLink  != null) tvInviteLink.setText(link);
        generateQrCode(link);
    }

    private void generateLink() {
        viewModel.generateInviteLink(channelId);
    }

    private void copyLink() {
        if (currentInviteLink == null || currentInviteLink.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Channel invite link", currentInviteLink));
            Toast.makeText(this, "Link copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareLink() {
        if (currentInviteLink == null || currentInviteLink.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,
            "Join " + channelName + " on CallX:\n" + currentInviteLink);
        startActivity(Intent.createChooser(share, "Share invite link via"));
    }

    private void confirmRevoke() {
        new AlertDialog.Builder(this)
            .setTitle("Revoke invite link?")
            .setMessage("This will permanently disable the current link. Anyone with the old link will no longer be able to join.")
            .setPositiveButton("Revoke", (d, w) -> viewModel.revokeInviteLink(channelId, currentInviteCode))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void generateQrCode(String content) {
        if (ivQrCode == null || content == null || content.isEmpty()) return;
        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bmp = encoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 400, 400);
            ivQrCode.setImageBitmap(bmp);
            ivQrCode.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            ivQrCode.setVisibility(View.GONE);
        }
    }
}
