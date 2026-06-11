package com.callx.app.social;

  import android.os.Bundle;
  import android.text.TextUtils;
  import android.view.View;
  import android.widget.*;
  import androidx.appcompat.app.AppCompatActivity;

  import com.callx.app.models.DuetSeriesModel;
  import com.callx.app.reels.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.database.DatabaseReference;
  import com.google.firebase.database.FirebaseDatabase;
  import com.callx.app.utils.Constants;

  import java.util.HashMap;
  import java.util.Map;
  import java.util.UUID;

  /**
   * DuetSeriesCreateActivity — Creator creates a new Duet Series.
   *
   * Result: If creation is successful, returns RESULT_OK with:
   *   EXTRA_SERIES_ID    → String  (new series ID)
   *   EXTRA_SERIES_TITLE → String  (series title entered by user)
   *
   * Firebase writes:
   *   duetSeries/{seriesId}          → DuetSeriesModel
   *   userDuetSeries/{uid}/{seriesId} → title  (creator's series index)
   */
  public class DuetSeriesCreateActivity extends AppCompatActivity {

      public static final String EXTRA_SERIES_ID    = "series_id";
      public static final String EXTRA_SERIES_TITLE = "series_title";

      private EditText  etTitle, etDescription;
      private TextView  btnCreate, btnCancel;
      private ProgressBar progressCreate;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_duet_series_create);

          etTitle         = findViewById(R.id.et_series_title);
          etDescription   = findViewById(R.id.et_series_description);
          btnCreate       = findViewById(R.id.btn_create_series);
          btnCancel       = findViewById(R.id.btn_cancel_series);
          progressCreate  = findViewById(R.id.progress_series_create);

          btnCancel.setOnClickListener(v -> finish());
          btnCreate.setOnClickListener(v -> attemptCreate());
      }

      private void attemptCreate() {
          String title = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
          String desc  = etDescription.getText() != null ? etDescription.getText().toString().trim() : "";

          if (TextUtils.isEmpty(title)) {
              etTitle.setError("Series title required");
              etTitle.requestFocus();
              return;
          }

          String myUid   = FirebaseUtils.getCurrentUid();
          String myName  = FirebaseUtils.getCurrentName();
          if (TextUtils.isEmpty(myUid)) {
              Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
              return;
          }

          btnCreate.setEnabled(false);
          progressCreate.setVisibility(View.VISIBLE);

          String seriesId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);

          DuetSeriesModel series = new DuetSeriesModel(
              seriesId, myUid, myName, "", title, desc
          );

          DatabaseReference db = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();

          Map<String, Object> updates = new HashMap<>();
          updates.put("duetSeries/" + seriesId,                series);
          updates.put("userDuetSeries/" + myUid + "/" + seriesId, title);

          db.updateChildren(updates)
            .addOnSuccessListener(unused -> {
                if (isFinishing() || isDestroyed()) return;
                android.content.Intent result = new android.content.Intent();
                result.putExtra(EXTRA_SERIES_ID, seriesId);
                result.putExtra(EXTRA_SERIES_TITLE, title);
                setResult(RESULT_OK, result);
                finish();
            })
            .addOnFailureListener(e -> {
                if (isFinishing() || isDestroyed()) return;
                progressCreate.setVisibility(View.GONE);
                btnCreate.setEnabled(true);
                Toast.makeText(this, "Failed to create series", Toast.LENGTH_SHORT).show();
            });
      }
  }
  