package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.ContactsContract;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.ContactSharePickerActivity;
import com.callx.app.chat.R;
import com.callx.app.models.Message;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Handles:
 *   1. Launching ContactSharePickerActivity (via Activity.startActivityForResult)
 *   2. Receiving the result in onActivityResult and building + pushing the message
 *   3. Binding a "contact" bubble in MessageAdapter / MessagePagingAdapter
 *
 * Wiring (in ChatActivity / GroupChatActivity):
 *
 *   // field
 *   private ChatContactShareController contactShareController;
 *
 *   // in onCreate (after buildOutgoing / pushMessage are available)
 *   contactShareController = new ChatContactShareController(
 *       this, this::buildOutgoing, this::pushMessage);
 *
 *   // in showAttachSheet opt_contact click:
 *   contactShareController.launch();
 *
 *   // in onActivityResult:
 *   if (contactShareController.handleResult(requestCode, resultCode, data)) return;
 */
public class ChatContactShareController {

    public static final int REQUEST_CODE = 3001;

    public interface BuildOutgoing   { Message build(); }
    public interface PushMessage     { void push(Message m, String preview); }

    private final Activity       activity;
    private final BuildOutgoing  buildOutgoing;
    private final PushMessage    pushMessage;

    public ChatContactShareController(Activity activity,
                                      BuildOutgoing buildOutgoing,
                                      PushMessage pushMessage) {
        this.activity      = activity;
        this.buildOutgoing = buildOutgoing;
        this.pushMessage   = pushMessage;
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    public void launch() {
        Intent i = new Intent(activity, ContactSharePickerActivity.class);
        activity.startActivityForResult(i, REQUEST_CODE);
    }

    // ── Result ────────────────────────────────────────────────────────────────

    /**
     * @return true if this controller consumed the result.
     */
    public boolean handleResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_CODE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;

        String name   = data.getStringExtra(ContactSharePickerActivity.EXTRA_CONTACT_NAME);
        String phone  = data.getStringExtra(ContactSharePickerActivity.EXTRA_CONTACT_PHONE);
        String phone2 = data.getStringExtra(ContactSharePickerActivity.EXTRA_CONTACT_PHONE2);

        if (name == null || phone == null) return true;

        Message m      = buildOutgoing.build();
        m.type         = "contact";
        m.text         = "📇 " + name;        // fallback text for notification / quote
        m.contactName  = name;
        m.contactPhone = phone;
        m.contactPhone2 = phone2;

        String preview = "📇 " + name;
        pushMessage.push(m, preview);
        return true;
    }

    // ── Bubble binding ────────────────────────────────────────────────────────

    /**
     * Bind a contact bubble.
     *
     * @param bubbleRoot  root view inflated from item_msg_contact.xml
     * @param message     the contact message
     */
    public static void bindBubble(View bubbleRoot, Message message) {
        CircleImageView ivPhoto   = bubbleRoot.findViewById(R.id.ivContactPhoto);
        TextView        tvName    = bubbleRoot.findViewById(R.id.tvContactName);
        TextView        tvPhone   = bubbleRoot.findViewById(R.id.tvContactPhone);
        TextView        btnView   = bubbleRoot.findViewById(R.id.btnViewContact);

        tvName.setText(message.contactName != null ? message.contactName : "");
        tvPhone.setText(message.contactPhone != null ? message.contactPhone : "");

        // Photo: contactPhotoUrl (not set in current impl — placeholder shown)
        if (message.contactPhotoUrl != null && !message.contactPhotoUrl.isEmpty()) {
            Glide.with(bubbleRoot.getContext())
                    .load(message.contactPhotoUrl)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .into(ivPhoto);
        } else {
            ivPhoto.setImageResource(R.drawable.ic_person);
        }

        // "View Contact" → open Contacts app for this phone number
        btnView.setOnClickListener(v -> {
            if (message.contactPhone == null) return;
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(message.contactPhone));
            Intent viewIntent = new Intent(Intent.ACTION_VIEW, uri);
            if (viewIntent.resolveActivity(v.getContext().getPackageManager()) != null) {
                v.getContext().startActivity(viewIntent);
            } else {
                // fallback: dial pad
                Intent dial = new Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:" + message.contactPhone));
                v.getContext().startActivity(dial);
            }
        });
    }
}
