package com.callx.app.chat.translate;

  import com.google.mlkit.nl.translate.TranslateLanguage;
  import com.google.mlkit.nl.translate.Translation;
  import com.google.mlkit.nl.translate.Translator;
  import com.google.mlkit.nl.translate.TranslatorOptions;

  /**
   * MessageTranslationHelper — Translate messages using ML Kit on-device.
   *
   * No API key needed for ML Kit on-device translation.
   * Supports 50+ languages, model downloaded on first use (~30MB per language).
   *
   * Usage:
   *   MessageTranslationHelper.translate(sourceText, targetLang, callback)
   *
   * Dependencies to add in build.gradle:
   *   implementation 'com.google.mlkit:translate:17.0.2'
   */
  public class MessageTranslationHelper {

      public interface TranslationCallback {
          void onSuccess(String translatedText);
          void onFailure(String error);
      }

      public static void translate(String sourceText, String targetLanguageCode,
                                    TranslationCallback cb) {
          TranslatorOptions options = new TranslatorOptions.Builder()
              .setSourceLanguage(TranslateLanguage.ENGLISH)
              .setTargetLanguage(targetLanguageCode)
              .build();

          Translator translator = Translation.getClient(options);
          translator.downloadModelIfNeeded()
              .addOnSuccessListener(v ->
                  translator.translate(sourceText)
                      .addOnSuccessListener(result -> {
                          cb.onSuccess(result);
                          translator.close();
                      })
                      .addOnFailureListener(e -> {
                          cb.onFailure(e.getMessage());
                          translator.close();
                      })
              )
              .addOnFailureListener(e -> cb.onFailure("Model download failed: " + e.getMessage()));
      }

      /** Common target language codes */
      public static final String HINDI   = TranslateLanguage.HINDI;
      public static final String ENGLISH = TranslateLanguage.ENGLISH;
      public static final String ARABIC  = TranslateLanguage.ARABIC;
      public static final String FRENCH  = TranslateLanguage.FRENCH;
      public static final String SPANISH = TranslateLanguage.SPANISH;
  }