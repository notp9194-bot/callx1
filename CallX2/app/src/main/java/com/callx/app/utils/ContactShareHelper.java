package com.callx.app.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import com.callx.app.models.Message;

/**
 * Feature 3 (NEW): Contact / vCard Sharing
 * - Pick a contact from the system contacts app.
 * - Build a vCard 3.0 string for import by the recipient.
 * - Provide intent to add received vCard to contacts.
 */
public class ContactShareHelper {

    private static final String TAG = "ContactShare";

    public static class ContactData {
        public String name;
        public String phone;
        public String email;
        public String vCard;
    }

    /** Intent to open the system contact picker. */
    public static Intent pickContactIntent() {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE);
        return i;
    }

    /** Read contact details from picker result Uri. */
    public static ContactData readFromUri(Context ctx, Uri contactUri) {
        ContentResolver cr = ctx.getContentResolver();
        ContactData data = new ContactData();
        try (Cursor c = cr.query(contactUri,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                data.name  = c.getString(0);
                data.phone = c.getString(1);
            }
        } catch (Exception e) { Log.e(TAG, "Read contact failed", e); }
        // Try email
        if (data.name != null) {
            try (Cursor em = cr.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " = ?",
                    new String[]{data.name}, null)) {
                if (em != null && em.moveToFirst()) {
                    data.email = em.getString(0);
                }
            } catch (Exception ignored) {}
        }
        data.vCard = buildVCard(data.name, data.phone, data.email);
        return data;
    }

    /** Build a minimal vCard 3.0 string. */
    public static String buildVCard(String name, String phone, String email) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCARD\r\n");
        sb.append("VERSION:3.0\r\n");
        if (name  != null) sb.append("FN:").append(name).append("\r\n");
        if (phone != null) sb.append("TEL;TYPE=CELL:").append(phone).append("\r\n");
        if (email != null) sb.append("EMAIL:").append(email).append("\r\n");
        sb.append("END:VCARD\r\n");
        return sb.toString();
    }

    /** Intent to import a received vCard into system contacts. */
    public static Intent importVCardIntent(String vCard) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        Uri uri = Uri.parse("data:text/x-vcard;charset=utf-8," +
                Uri.encode(vCard));
        i.setDataAndType(uri, "text/x-vcard");
        return i;
    }

    /** Populate a Message with contact fields. */
    public static void applyToMessage(Message msg, ContactData data) {
        msg.type         = "contact";
        msg.contactName  = data.name;
        msg.contactPhone = data.phone;
        msg.contactEmail = data.email;
        msg.contactVCard = data.vCard;
    }
}
