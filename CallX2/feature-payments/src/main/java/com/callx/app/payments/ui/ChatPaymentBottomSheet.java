package com.callx.app.payments.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * Chat entry point for the payment surface. It intentionally launches the
 * same full-screen flows as Payments Home so chat and standalone navigation
 * share one source of truth.
 */
public final class ChatPaymentBottomSheet {
    private ChatPaymentBottomSheet() {}

    public static void show(Activity activity, String partnerUid, String partnerName,
                            String chatId) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 18, 24, 28);
        root.setBackgroundColor(Color.rgb(7, 27, 22));

        TextView title = new TextView(activity);
        title.setText("Payments in chat");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, 56));

        TextView subtitle = new TextView(activity);
        subtitle.setText("Choose an action. Real payments are disabled in demo mode.");
        subtitle.setTextColor(Color.rgb(174, 207, 192));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, 48));

        addAction(root, "Send Money", v -> launch(activity, SendMoneyActivity.class,
                partnerUid, partnerName, chatId));
        addAction(root, "Request Money", v -> launch(activity, RequestMoneyActivity.class,
                partnerUid, partnerName, chatId));
        addAction(root, "Scan QR", v -> launch(activity, ScanQrActivity.class,
                partnerUid, partnerName, chatId));
        addAction(root, "Payments Home", v -> launch(activity, PaymentsHomeActivity.class,
                partnerUid, partnerName, chatId));

        dialog.setContentView(root);
        dialog.show();
    }

    private static void addAction(LinearLayout root, String title, View.OnClickListener listener) {
        Button button = new Button(root.getContext());
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setOnClickListener(listener);
        root.addView(button, new LinearLayout.LayoutParams(-1, 56));
    }

    private static void launch(Activity activity, Class<?> target, String uid,
                               String name, String chatId) {
        Intent intent = new Intent(activity, target);
        intent.putExtra("counterpartyUid", uid);
        intent.putExtra("counterpartyName", name);
        intent.putExtra("chatId", chatId);
        activity.startActivity(intent);
    }
}