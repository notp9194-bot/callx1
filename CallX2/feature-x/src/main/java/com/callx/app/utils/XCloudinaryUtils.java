package com.callx.app.utils;

  import android.content.Context;
  import android.net.Uri;
  import android.os.AsyncTask;
  import org.json.JSONObject;
  import java.io.DataOutputStream;
  import java.io.InputStream;
  import java.net.HttpURLConnection;
  import java.net.URL;

  /**
   * Cloudinary upload helper for the X module.
   *
   * Uses the unsigned upload preset "x_tweet_media" with the cloud name "callx-app".
   * No server-side signing needed — the preset must be configured as "unsigned" in
   * the Cloudinary dashboard.
   *
   * Replace CLOUD_NAME and UPLOAD_PRESET with your actual values if different.
   */
  public class XCloudinaryUtils {

      private static final String CLOUD_NAME    = "callx-app";
      private static final String UPLOAD_PRESET = "x_tweet_media";

      private static final String BASE_URL =
          "https://api.cloudinary.com/v1_1/" + CLOUD_NAME;

      public interface XUploadListener {
          void onSuccess(String publicId, String secureUrl);
          void onError(String message);
          void onProgress(int percent);
      }

      public static void uploadTweetImage(Context ctx, Uri imageUri, XUploadListener listener) {
          upload(ctx, imageUri, "image", listener);
      }

      public static void uploadTweetVideo(Context ctx, Uri videoUri, XUploadListener listener) {
          upload(ctx, videoUri, "video", listener);
      }

      private static void upload(Context ctx, Uri uri, String resourceType, XUploadListener listener) {
          new Thread(() -> {
              HttpURLConnection conn = null;
              try {
                  String boundary = "XCloudinaryBoundary_" + System.currentTimeMillis();
                  URL url = new URL(BASE_URL + "/" + resourceType + "/upload");
                  conn = (HttpURLConnection) url.openConnection();
                  conn.setRequestMethod("POST");
                  conn.setDoInput(true);
                  conn.setDoOutput(true);
                  conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                  try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                      // upload_preset field
                      writeFormField(dos, boundary, "upload_preset", UPLOAD_PRESET);
                      // folder field
                      writeFormField(dos, boundary, "folder", "x_tweets");

                      // File
                      dos.writeBytes("--" + boundary + "\r\n");
                      dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"tweet_media\"\r\n");
                      dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");

                      InputStream is = ctx.getContentResolver().openInputStream(uri);
                      if (is != null) {
                          byte[] buf = new byte[4096];
                          int read;
                          while ((read = is.read(buf)) != -1) dos.write(buf, 0, read);
                          is.close();
                      }
                      dos.writeBytes("\r\n--" + boundary + "--\r\n");
                      dos.flush();
                  }

                  if (conn.getResponseCode() == 200) {
                      InputStream respStream = conn.getInputStream();
                      String resp = new String(respStream.readAllBytes());
                      JSONObject json = new JSONObject(resp);
                      String publicId  = json.optString("public_id");
                      String secureUrl = json.optString("secure_url");
                      if (listener != null) listener.onSuccess(publicId, secureUrl);
                  } else {
                      InputStream err = conn.getErrorStream();
                      String msg = err != null ? new String(err.readAllBytes()) : "HTTP " + conn.getResponseCode();
                      if (listener != null) listener.onError(msg);
                  }
              } catch (Exception e) {
                  if (listener != null) listener.onError(e.getMessage());
              } finally {
                  if (conn != null) conn.disconnect();
              }
          }).start();
      }

      private static void writeFormField(DataOutputStream dos, String boundary,
                                         String name, String value) throws Exception {
          dos.writeBytes("--" + boundary + "\r\n");
          dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
          dos.writeBytes(value + "\r\n");
      }
  }