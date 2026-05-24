package com.callx.app.utils;

  import android.content.Context;
  import android.net.Uri;
  import com.callx.app.utils.Constants;
  import org.json.JSONObject;
  import java.io.DataOutputStream;
  import java.io.InputStream;
  import java.net.HttpURLConnection;
  import java.net.URL;

  /**
   * Cloudinary upload helper for the X module.
   * Uses the same server-signed upload flow as the main app (CloudinaryUploader).
   * Signs via: POST {SERVER_URL}/cloudinary/sign  → gets api_key, signature, timestamp, cloud_name
   * Then uploads directly to Cloudinary with those credentials.
   */
  public class XCloudinaryUtils {

      private static final String SIGN_URL = Constants.SERVER_URL + "/cloudinary/sign";

      public interface XUploadListener {
          void onSuccess(String publicId, String secureUrl);
          void onError(String message);
          void onProgress(int percent);
      }

      public static void uploadTweetImage(Context ctx, Uri imageUri, XUploadListener listener) {
          upload(ctx, imageUri, "image", "x_tweets", listener);
      }

      public static void uploadTweetVideo(Context ctx, Uri videoUri, XUploadListener listener) {
          upload(ctx, videoUri, "video", "x_tweets", listener);
      }

      private static void upload(Context ctx, Uri uri, String resourceType,
                                 String folder, XUploadListener listener) {
          new Thread(() -> {
              try {
                  // ── Step 1: Get signature from server ──────────────────
                  JSONObject signPayload = new JSONObject();
                  signPayload.put("folder", folder);
                  signPayload.put("resource_type", resourceType);

                  HttpURLConnection signConn = (HttpURLConnection)
                      new URL(SIGN_URL).openConnection();
                  signConn.setRequestMethod("POST");
                  signConn.setDoOutput(true);
                  signConn.setRequestProperty("Content-Type", "application/json");
                  signConn.setConnectTimeout(15000);
                  signConn.setReadTimeout(15000);

                  try (DataOutputStream dos = new DataOutputStream(signConn.getOutputStream())) {
                      dos.writeBytes(signPayload.toString());
                      dos.flush();
                  }

                  if (signConn.getResponseCode() != 200) {
                      InputStream err = signConn.getErrorStream();
                      String msg = err != null ? new String(err.readAllBytes()) : "Sign failed: HTTP " + signConn.getResponseCode();
                      signConn.disconnect();
                      if (listener != null) listener.onError(msg);
                      return;
                  }

                  String signBody = new String(signConn.getInputStream().readAllBytes());
                  signConn.disconnect();

                  JSONObject signJson = new JSONObject(signBody);
                  String apiKey    = signJson.getString("api_key");
                  String signature = signJson.getString("signature");
                  String timestamp = signJson.getString("timestamp");
                  String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
                  String signedFolder = signJson.optString("folder", folder);

                  // ── Step 2: Upload to Cloudinary ───────────────────────
                  String boundary = "XBoundary_" + System.currentTimeMillis();
                  String uploadUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/" + resourceType + "/upload";

                  HttpURLConnection upConn = (HttpURLConnection) new URL(uploadUrl).openConnection();
                  upConn.setRequestMethod("POST");
                  upConn.setDoOutput(true);
                  upConn.setDoInput(true);
                  upConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                  upConn.setConnectTimeout(60000);
                  upConn.setReadTimeout(120000);

                  try (DataOutputStream dos = new DataOutputStream(upConn.getOutputStream())) {
                      writeField(dos, boundary, "api_key",   apiKey);
                      writeField(dos, boundary, "timestamp", timestamp);
                      writeField(dos, boundary, "signature", signature);
                      writeField(dos, boundary, "folder",    signedFolder);

                      // File
                      dos.writeBytes("--" + boundary + "\r\n");
                      dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"x_media\"\r\n");
                      dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                      InputStream is = ctx.getContentResolver().openInputStream(uri);
                      if (is != null) {
                          byte[] buf = new byte[8192];
                          int totalRead = 0;
                          int read;
                          while ((read = is.read(buf)) != -1) {
                              dos.write(buf, 0, read);
                              totalRead += read;
                              if (listener != null) listener.onProgress(Math.min(90, totalRead / 1024));
                          }
                          is.close();
                      }
                      dos.writeBytes("\r\n--" + boundary + "--\r\n");
                      dos.flush();
                  }

                  int code = upConn.getResponseCode();
                  if (code == 200) {
                      String resp = new String(upConn.getInputStream().readAllBytes());
                      JSONObject json = new JSONObject(resp);
                      String publicId  = json.optString("public_id");
                      String secureUrl = json.optString("secure_url");
                      upConn.disconnect();
                      if (listener != null) {
                          listener.onProgress(100);
                          listener.onSuccess(publicId, secureUrl);
                      }
                  } else {
                      InputStream err = upConn.getErrorStream();
                      String msg = err != null ? new String(err.readAllBytes()) : "Upload failed: HTTP " + code;
                      upConn.disconnect();
                      if (listener != null) listener.onError(msg);
                  }

              } catch (Exception e) {
                  if (listener != null) listener.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
              }
          }).start();
      }

      private static void writeField(DataOutputStream dos, String boundary,
                                     String name, String value) throws Exception {
          dos.writeBytes("--" + boundary + "\r\n");
          dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
          dos.writeBytes(value + "\r\n");
      }
  }
