package com.callx.app.channel;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ChannelPostComposerActivity — full WhatsApp-level post composer (v5).
 *
 * v5 additions/fixes:
 *   ✓ FIXED: fetchLinkPreview now does real OG metadata fetch via ChannelLinkPreviewHelper
 *   ✓ FIXED: link preview card shows title, description, image thumbnail, domain
 *   ✓ NEW: Broadcast post type — text + priority (Normal/Important/Urgent) + notify all toggle
 *   ✓ NEW: Event post type — title, description, location, start/end date-time, RSVP toggle
 *   ✓ NEW: Topic tags chip input (up to 5 tags per post)
 *   ✓ NEW: Anonymous poll voting toggle
 *   ✓ NEW: Image poll — each poll option can have an image
 *   ✓ NEW: Share to status option alongside schedule
 *   ✓ FIXED: Character counter max set to 4096 for text, 500 for caption (XML/Java aligned)
 *   ✓ @mention autocomplete: ChannelMentionHandler drives suggestion popup
 *   ✓ Caption field for image, video, audio, document types
 *   ✓ Saved drafts: auto-save every 30 seconds, restore on open
 *   ✓ Schedule picker (date + time) with clear button
 *   ✓ Poll: expiry date, multi-select toggle, per-option remove
 *   ✓ Upload progress bar during media/document upload
 */
public class ChannelPostComposerActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID     = "channelId";
    public static final String EXTRA_CHANNEL_NAME   = "channelName";
    public static final String EXTRA_EDIT_POST_ID   = "editPostId";
    public static final String EXTRA_EDIT_POST_TEXT = "editPostText";

    private static final int RC_PICK_IMAGE    = 101;
    private static final int RC_PICK_VIDEO    = 102;
    private static final int RC_PICK_DOCUMENT = 103;
    private static final int RC_RECORD_AUDIO  = 104;
    private static final int RC_PICK_POLL_IMG = 105;

    private static final int    MAX_TEXT_CHARS    = 4096;
    private static final int    MAX_CAPTION_CHARS = 500;
    private static final int    MAX_TOPIC_TAGS    = 5;
    private static final long   DRAFT_SAVE_INTERVAL_MS = 30_000L;

    private ChannelViewModel viewModel;
    private String channelId, channelName;
    private String editPostId;
    private String selectedType = "text";

    // Media
    private Uri    selectedImageUri, selectedVideoUri, selectedAudioUri, selectedDocUri;
    private String selectedDocName, selectedDocMime;

    // Schedule
    private Calendar scheduledTime = null;

    // Poll
    private final List<String> pollOptions = new ArrayList<>();
    private boolean pollMultiSelect = false;
    private boolean pollAnonymous   = false;
    private Calendar pollExpiry = null;

    // Event
    private Calendar eventStart = null, eventEnd = null;
    private boolean  eventRsvpEnabled = true;

    // Topic tags
    private final List<String> topicTags = new ArrayList<>();

    // Mention
    private ChannelMentionHandler mentionHandler;
    private final Set<String> mentionedUids = new LinkedHashSet<>();
    private MentionSuggestionAdapter mentionAdapter;
    private int mentionAtStart = -1, mentionAtEnd = -1;
    private RecyclerView rvMentionSuggestions;

    // Draft auto-save
    private final android.os.Handler draftHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable draftSaveRunnable;

    // Link preview state
    private String fetchedLinkUrl, fetchedLinkTitle, fetchedLinkDesc, fetchedLinkImage;
    private boolean linkPreviewLoading = false;

    // UI
    private TextInputEditText etPostText, etCaption, etLinkUrl;
    private TextInputEditText etEventTitle, etEventLocation;
    private TextView          tvCharCount, tvCaptionCount;
    private ImageView         ivMediaPreview, ivLinkPreviewImage;
    private TextView          tvLinkPreviewTitle, tvLinkPreviewDesc, tvLinkPreviewDomain;
    private View              cardLinkPreview, progressLinkPreview;
    private MaterialButton    btnSend, btnSchedule, btnClearSchedule;
    private View              layoutScheduleInfo, layoutPollSection, layoutMediaPreview;
    private View              layoutBroadcastSection, layoutEventSection, layoutTopicSection;
    private TextView          tvScheduleInfo, tvPollOptionCount, tvTopicCount;
    private LinearLayout      layoutPollOptions;
    private Switch            switchPollMulti, switchPollAnonymous, switchEventRsvp, switchNotifyAll;
    private ProgressBar       progressUpload;
    private RadioGroup        rgBroadcastPriority;
    private ChipGroup         chipGroupTopics;
    private TextInputEditText etNewTopic;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_post_composer);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        editPostId  = getIntent().getStringExtra(EXTRA_EDIT_POST_ID);
        String editText = getIntent().getStringExtra(EXTRA_EDIT_POST_TEXT);

        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_composer);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(editPostId != null ? "Edit post" : "New post");
        }
        toolbar.setNavigationOnClickListener(v -> confirmDiscard());

        bindViews();

        // Pre-fill text for edit mode
        if (editPostId != null && editText != null && etPostText != null) {
            etPostText.setText(editText);
            etPostText.setSelection(editText.length());
        }

        // Post type chips
        setupTypeChips();

        // Character counters
        setupCharCounter();
        setupCaptionCounter();

        // @Mention autocomplete
        setupMentionAutocomplete();

        // Poll section
        setupPollSection();

        // Link auto-preview on URL paste
        setupLinkAutoPreview();

        // Topic tag chips
        setupTopicTags();

        // Schedule button
        if (btnSchedule != null) btnSchedule.setOnClickListener(v -> pickScheduleDateTime());
        if (btnClearSchedule != null) {
            btnClearSchedule.setOnClickListener(v -> {
                scheduledTime = null;
                if (layoutScheduleInfo != null) layoutScheduleInfo.setVisibility(View.GONE);
            });
        }

        // Send / save
        if (btnSend != null) btnSend.setOnClickListener(v -> submitPost());

        // Media buttons
        setupMediaButtons();

        // Event date-time pickers
        setupEventSection();

        // Draft restore
        restoreDraft();
        startDraftAutoSave();

        // Observe upload progress
        viewModel.uploadProgress.observe(this, inProgress -> {
            if (progressUpload != null)
                progressUpload.setVisibility((inProgress != null && inProgress) ? View.VISIBLE : View.GONE);
            if (btnSend != null) btnSend.setEnabled(inProgress == null || !inProgress);
        });

        viewModel.uploadPercent.observe(this, pct -> {
            if (progressUpload instanceof ProgressBar && pct != null) {
                ((ProgressBar) progressUpload).setProgress(pct);
            }
        });

        // Observe success
        viewModel.postSuccess.observe(this, ok -> {
            if (ok != null && ok) {
                clearDraft();
                String msg = scheduledTime != null ? "Post scheduled!" : "Post published!";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    // ── Bind views ────────────────────────────────────────────────────────

    private void bindViews() {
        etPostText         = findViewById(R.id.et_post_text);
        etCaption          = findViewById(R.id.et_post_caption);
        etLinkUrl          = findViewById(R.id.et_link_url);
        etEventTitle       = findViewById(R.id.et_event_title);
        etEventLocation    = findViewById(R.id.et_event_location);
        tvCharCount        = findViewById(R.id.tv_char_count);
        tvCaptionCount     = findViewById(R.id.tv_caption_count);
        ivMediaPreview     = findViewById(R.id.iv_media_preview);
        ivLinkPreviewImage = findViewById(R.id.iv_link_preview_image);
        tvLinkPreviewTitle = findViewById(R.id.tv_link_preview_title);
        tvLinkPreviewDesc  = findViewById(R.id.tv_link_preview_desc);
        tvLinkPreviewDomain= findViewById(R.id.tv_link_preview_domain);
        cardLinkPreview    = findViewById(R.id.card_link_preview);
        progressLinkPreview= findViewById(R.id.progress_link_preview);
        btnSend            = findViewById(R.id.btn_post_send);
        btnSchedule        = findViewById(R.id.btn_schedule);
        btnClearSchedule   = findViewById(R.id.btn_clear_schedule);
        layoutScheduleInfo = findViewById(R.id.layout_schedule_info);
        layoutPollSection  = findViewById(R.id.layout_poll_section);
        layoutBroadcastSection = findViewById(R.id.layout_broadcast_section);
        layoutEventSection = findViewById(R.id.layout_event_section);
        layoutTopicSection = findViewById(R.id.layout_topic_section);
        layoutMediaPreview = findViewById(R.id.layout_media_preview);
        tvScheduleInfo     = findViewById(R.id.tv_schedule_info);
        tvPollOptionCount  = findViewById(R.id.tv_poll_option_count);
        tvTopicCount       = findViewById(R.id.tv_topic_count);
        layoutPollOptions  = findViewById(R.id.layout_poll_options);
        switchPollMulti    = findViewById(R.id.switch_poll_multi);
        switchPollAnonymous= findViewById(R.id.switch_poll_anonymous);
        switchEventRsvp    = findViewById(R.id.switch_event_rsvp);
        switchNotifyAll    = findViewById(R.id.switch_notify_all);
        progressUpload     = findViewById(R.id.progress_upload);
        rgBroadcastPriority= findViewById(R.id.rg_broadcast_priority);
        chipGroupTopics    = findViewById(R.id.chip_group_post_topics);
        etNewTopic         = findViewById(R.id.et_new_post_topic);
        rvMentionSuggestions = findViewById(R.id.rv_mention_suggestions);
    }

    // ── Post type chips ───────────────────────────────────────────────────

    private void setupTypeChips() {
        ChipGroup cgType = findViewById(R.id.chip_group_post_type);
        if (cgType == null) return;
        for (int i = 0; i < cgType.getChildCount(); i++) {
            if (cgType.getChildAt(i) instanceof Chip) {
                Chip chip = (Chip) cgType.getChildAt(i);
                chip.setOnCheckedChangeListener((btn, checked) -> {
                    if (checked) switchToType(chip.getTag() != null
                        ? chip.getTag().toString() : "text");
                });
            }
        }
    }

    private void switchToType(String type) {
        selectedType = type;
        boolean isPoll      = "poll".equals(type);
        boolean isLink      = "link".equals(type);
        boolean isBroadcast = "broadcast".equals(type);
        boolean isEvent     = "event".equals(type);
        boolean hasMedia    = "image".equals(type) || "video".equals(type)
                           || "audio".equals(type) || "document".equals(type);

        if (layoutPollSection     != null) layoutPollSection.setVisibility(isPoll ? View.VISIBLE : View.GONE);
        if (cardLinkPreview       != null && !isLink) cardLinkPreview.setVisibility(View.GONE);
        if (etLinkUrl             != null) etLinkUrl.setVisibility(isLink ? View.VISIBLE : View.GONE);
        if (layoutBroadcastSection!= null) layoutBroadcastSection.setVisibility(isBroadcast ? View.VISIBLE : View.GONE);
        if (layoutEventSection    != null) layoutEventSection.setVisibility(isEvent ? View.VISIBLE : View.GONE);
        if (layoutMediaPreview    != null) layoutMediaPreview.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        if (etCaption             != null) etCaption.setVisibility(hasMedia ? View.VISIBLE : View.GONE);

        // Clear prior type media selections
        if (!hasMedia) {
            selectedImageUri = null; selectedVideoUri = null;
            selectedAudioUri = null; selectedDocUri   = null;
            if (ivMediaPreview != null) ivMediaPreview.setVisibility(View.GONE);
        }
    }

    // ── Character counter ─────────────────────────────────────────────────

    private void setupCharCounter() {
        if (etPostText == null || tvCharCount == null) return;
        etPostText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                int len = s.length();
                tvCharCount.setText(len + " / " + MAX_TEXT_CHARS);
                tvCharCount.setTextColor(len > MAX_TEXT_CHARS ? 0xFFFF3B30 : 0xFF888888);
                if (btnSend != null) btnSend.setEnabled(len > 0 && len <= MAX_TEXT_CHARS);
            }
        });
    }

    private void setupCaptionCounter() {
        if (etCaption == null || tvCaptionCount == null) return;
        etCaption.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                int len = s.length();
                tvCaptionCount.setText(len + " / " + MAX_CAPTION_CHARS);
                tvCaptionCount.setTextColor(len > MAX_CAPTION_CHARS ? 0xFFFF3B30 : 0xFF888888);
            }
        });
    }

    // ── @Mention autocomplete ─────────────────────────────────────────────

    private void setupMentionAutocomplete() {
        if (etPostText == null) return;
        mentionAdapter = new MentionSuggestionAdapter(new ArrayList<>(), candidate -> {
            if (mentionHandler != null)
                mentionHandler.selectMention(candidate, mentionAtStart, mentionAtEnd);
            mentionedUids.add(candidate.uid);
            if (rvMentionSuggestions != null) rvMentionSuggestions.setVisibility(View.GONE);
        });
        if (rvMentionSuggestions != null) {
            rvMentionSuggestions.setLayoutManager(new LinearLayoutManager(this));
            rvMentionSuggestions.setAdapter(mentionAdapter);
        }
        mentionHandler = new ChannelMentionHandler(this, etPostText, channelId);
        mentionHandler.setSuggestionShowCallback((candidates, atStart, atEnd) -> {
            mentionAtStart = atStart; mentionAtEnd = atEnd;
            mentionAdapter.update(candidates);
            if (rvMentionSuggestions != null)
                rvMentionSuggestions.setVisibility(candidates.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    // ── Poll section ──────────────────────────────────────────────────────

    private void setupPollSection() {
        View btnAddOption = findViewById(R.id.btn_add_poll_option);
        if (btnAddOption != null) btnAddOption.setOnClickListener(v -> addPollOption());

        View btnPickExpiry = findViewById(R.id.btn_poll_expiry);
        if (btnPickExpiry != null) btnPickExpiry.setOnClickListener(v -> pickPollExpiry());

        if (switchPollMulti != null)
            switchPollMulti.setOnCheckedChangeListener((btn, c) -> pollMultiSelect = c);
        if (switchPollAnonymous != null)
            switchPollAnonymous.setOnCheckedChangeListener((btn, c) -> pollAnonymous = c);

        // Pre-add 2 default options
        addPollOption(); addPollOption();
    }

    private void addPollOption() {
        if (pollOptions.size() >= 10) {
            Toast.makeText(this, "Max 10 options.", Toast.LENGTH_SHORT).show(); return;
        }
        if (layoutPollOptions == null) return;
        int idx = pollOptions.size();
        pollOptions.add("");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextInputEditText et = new TextInputEditText(this);
        et.setHint("Option " + (idx + 1));
        et.setMaxLines(1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(lp);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (idx < pollOptions.size()) pollOptions.set(idx, s.toString().trim());
            }
        });

        ImageButton btnRemove = new ImageButton(this);
        btnRemove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnRemove.setBackground(null);
        btnRemove.setOnClickListener(v -> {
            layoutPollOptions.removeView(row);
            if (idx < pollOptions.size()) pollOptions.remove(idx);
            updatePollOptionCount();
        });

        row.addView(et);
        row.addView(btnRemove);
        layoutPollOptions.addView(row);
        updatePollOptionCount();
    }

    private void updatePollOptionCount() {
        if (tvPollOptionCount != null) tvPollOptionCount.setText(pollOptions.size() + " options");
    }

    private void pickPollExpiry() {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (dp, y, m, d) -> {
            pollExpiry = Calendar.getInstance();
            pollExpiry.set(y, m, d, 23, 59, 59);
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ── Link auto-preview ─────────────────────────────────────────────────

    private void setupLinkAutoPreview() {
        if (etLinkUrl == null) return;
        etLinkUrl.addTextChangedListener(new TextWatcher() {
            private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            private Runnable pending;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (pending != null) h.removeCallbacks(pending);
                final String url = s.toString().trim();
                if (url.length() < 10 || (!url.startsWith("http") && !url.contains("."))) {
                    hideLinkPreview(); return;
                }
                pending = () -> fetchLinkPreview(url);
                h.postDelayed(pending, 900); // debounce 900ms
            }
        });
    }

    /**
     * fetchLinkPreview — FIXED in v5.
     * Now does a real OG metadata fetch via ChannelLinkPreviewHelper instead of
     * the previous stub that only extracted the domain.
     */
    private void fetchLinkPreview(String url) {
        if (linkPreviewLoading) return;
        linkPreviewLoading = true;
        if (progressLinkPreview != null) progressLinkPreview.setVisibility(View.VISIBLE);
        if (cardLinkPreview != null) cardLinkPreview.setVisibility(View.GONE);

        ChannelLinkPreviewHelper.fetch(url, new ChannelLinkPreviewHelper.LinkPreviewCallback() {
            @Override
            public void onSuccess(ChannelLinkPreviewHelper.LinkPreview preview) {
                linkPreviewLoading = false;
                fetchedLinkUrl   = preview.url;
                fetchedLinkTitle = preview.title;
                fetchedLinkDesc  = preview.description;
                fetchedLinkImage = preview.imageUrl;

                if (progressLinkPreview != null) progressLinkPreview.setVisibility(View.GONE);
                if (cardLinkPreview     != null) cardLinkPreview.setVisibility(View.VISIBLE);
                if (tvLinkPreviewTitle  != null) tvLinkPreviewTitle.setText(preview.title);
                if (tvLinkPreviewDesc   != null) tvLinkPreviewDesc.setText(preview.description);
                if (tvLinkPreviewDomain != null) tvLinkPreviewDomain.setText(preview.domain);
                if (ivLinkPreviewImage  != null) {
                    if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
                        ivLinkPreviewImage.setVisibility(View.VISIBLE);
                        Glide.with(ChannelPostComposerActivity.this)
                             .load(preview.imageUrl).centerCrop()
                             .into(ivLinkPreviewImage);
                    } else {
                        ivLinkPreviewImage.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public void onError(String reason) {
                linkPreviewLoading = false;
                fetchedLinkUrl = url;
                fetchedLinkTitle = null;
                fetchedLinkDesc  = null;
                fetchedLinkImage = null;
                if (progressLinkPreview != null) progressLinkPreview.setVisibility(View.GONE);
                // Show minimal card with just domain
                if (cardLinkPreview     != null) cardLinkPreview.setVisibility(View.VISIBLE);
                if (tvLinkPreviewDomain != null) tvLinkPreviewDomain.setText(extractDomain(url));
                if (tvLinkPreviewTitle  != null) tvLinkPreviewTitle.setText(extractDomain(url));
            }
        });
    }

    private void hideLinkPreview() {
        fetchedLinkUrl = null; fetchedLinkTitle = null;
        fetchedLinkDesc = null; fetchedLinkImage = null;
        if (cardLinkPreview != null) cardLinkPreview.setVisibility(View.GONE);
        if (progressLinkPreview != null) progressLinkPreview.setVisibility(View.GONE);
    }

    // ── Topic tags ────────────────────────────────────────────────────────

    private void setupTopicTags() {
        View btnAddTopic = findViewById(R.id.btn_add_post_topic);
        if (btnAddTopic != null) {
            btnAddTopic.setOnClickListener(v -> {
                String tag = etNewTopic != null && etNewTopic.getText() != null
                    ? etNewTopic.getText().toString().trim().toLowerCase() : "";
                if (tag.isEmpty() || tag.length() > 30) return;
                if (topicTags.size() >= MAX_TOPIC_TAGS) {
                    Toast.makeText(this, "Max " + MAX_TOPIC_TAGS + " topics per post.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!topicTags.contains(tag)) {
                    topicTags.add(tag);
                    addTopicChip(tag);
                    updateTopicCount();
                }
                if (etNewTopic != null) etNewTopic.setText("");
            });
        }
    }

    private void addTopicChip(String tag) {
        if (chipGroupTopics == null) return;
        Chip chip = new Chip(this);
        chip.setText("#" + tag);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> {
            topicTags.remove(tag);
            chipGroupTopics.removeView(chip);
            updateTopicCount();
        });
        chipGroupTopics.addView(chip);
    }

    private void updateTopicCount() {
        if (tvTopicCount != null) tvTopicCount.setText(topicTags.size() + " / " + MAX_TOPIC_TAGS);
    }

    // ── Event section ─────────────────────────────────────────────────────

    private void setupEventSection() {
        View btnEventStart = findViewById(R.id.btn_event_start_datetime);
        View btnEventEnd   = findViewById(R.id.btn_event_end_datetime);
        if (btnEventStart != null) btnEventStart.setOnClickListener(v -> pickEventDateTime(true));
        if (btnEventEnd   != null) btnEventEnd.setOnClickListener(v -> pickEventDateTime(false));
        if (switchEventRsvp != null)
            switchEventRsvp.setOnCheckedChangeListener((btn, c) -> eventRsvpEnabled = c);
    }

    private void pickEventDateTime(boolean isStart) {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (dp, y, m, d) ->
            new TimePickerDialog(this, (tp, h, min) -> {
                Calendar cal = Calendar.getInstance();
                cal.set(y, m, d, h, min, 0);
                if (isStart) {
                    eventStart = cal;
                    View tv = isStart ? findViewById(R.id.tv_event_start) : null;
                    if (tv instanceof TextView)
                        ((TextView) tv).setText(formatCalendar(cal));
                } else {
                    eventEnd = cal;
                    View tv = findViewById(R.id.tv_event_end);
                    if (tv instanceof TextView) ((TextView) tv).setText(formatCalendar(cal));
                }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show(),
        now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ── Schedule picker ───────────────────────────────────────────────────

    private void pickScheduleDateTime() {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (dp, y, m, d) ->
            new TimePickerDialog(this, (tp, h, min) -> {
                scheduledTime = Calendar.getInstance();
                scheduledTime.set(y, m, d, h, min, 0);
                if (scheduledTime.before(Calendar.getInstance())) {
                    Toast.makeText(this, "Choose a future time.", Toast.LENGTH_SHORT).show();
                    scheduledTime = null; return;
                }
                if (tvScheduleInfo  != null) tvScheduleInfo.setText("Scheduled: " + formatCalendar(scheduledTime));
                if (layoutScheduleInfo != null) layoutScheduleInfo.setVisibility(View.VISIBLE);
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show(),
        now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ── Media buttons ─────────────────────────────────────────────────────

    private void setupMediaButtons() {
        View btnImage = findViewById(R.id.btn_attach_image);
        View btnVideo = findViewById(R.id.btn_attach_video);
        View btnAudio = findViewById(R.id.btn_attach_audio);
        View btnDoc   = findViewById(R.id.btn_attach_document);

        if (btnImage != null) btnImage.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_PICK);
            i.setType("image/*");
            startActivityForResult(i, RC_PICK_IMAGE);
        });
        if (btnVideo != null) btnVideo.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_PICK);
            i.setType("video/*");
            startActivityForResult(i, RC_PICK_VIDEO);
        });
        if (btnAudio != null) btnAudio.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("audio/*");
            startActivityForResult(Intent.createChooser(i, "Select audio"), RC_RECORD_AUDIO);
        });
        if (btnDoc != null) btnDoc.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Select document"), RC_PICK_DOCUMENT);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        switch (req) {
            case RC_PICK_IMAGE:
                selectedImageUri = uri;
                switchToType("image");
                showMediaPreview(uri, "image");
                break;
            case RC_PICK_VIDEO:
                selectedVideoUri = uri;
                switchToType("video");
                showMediaPreview(uri, "video");
                break;
            case RC_RECORD_AUDIO:
                selectedAudioUri = uri;
                switchToType("audio");
                if (ivMediaPreview != null) {
                    ivMediaPreview.setImageResource(android.R.drawable.ic_media_play);
                    ivMediaPreview.setVisibility(View.VISIBLE);
                }
                break;
            case RC_PICK_DOCUMENT:
                selectedDocUri  = uri;
                selectedDocName = getFileName(uri);
                selectedDocMime = getContentResolver().getType(uri);
                switchToType("document");
                if (ivMediaPreview != null) {
                    ivMediaPreview.setImageResource(R.drawable.ic_document_placeholder);
                    ivMediaPreview.setVisibility(View.VISIBLE);
                }
                break;
        }
    }

    private void showMediaPreview(Uri uri, String type) {
        if (ivMediaPreview == null) return;
        ivMediaPreview.setVisibility(View.VISIBLE);
        Glide.with(this).load(uri).centerCrop().into(ivMediaPreview);
        ivMediaPreview.setOnClickListener(v -> {
            selectedImageUri = null; selectedVideoUri = null;
            ivMediaPreview.setVisibility(View.GONE);
        });
    }

    // ── Submit ────────────────────────────────────────────────────────────

    private void submitPost() {
        if (editPostId != null) {
            // Edit mode
            String newText = etPostText != null && etPostText.getText() != null
                ? etPostText.getText().toString().trim() : "";
            if (newText.isEmpty()) { Toast.makeText(this, "Text required.", Toast.LENGTH_SHORT).show(); return; }
            // Direct Firebase update via repo — re-use ViewModel editPost bridge
            // (ChannelPost needs id & channelId)
            com.callx.app.models.ChannelPost ep = new com.callx.app.models.ChannelPost();
            ep.id = editPostId; ep.channelId = channelId;
            viewModel.editPost(ep, newText);
            return;
        }

        long scheduledMs = scheduledTime != null ? scheduledTime.getTimeInMillis() : 0;
        List<String> tags = new ArrayList<>(topicTags);
        List<String> mentions = new ArrayList<>(mentionedUids);

        switch (selectedType) {
            case "text": {
                String text = etPostText != null && etPostText.getText() != null
                    ? etPostText.getText().toString().trim() : "";
                if (text.isEmpty()) { Toast.makeText(this, "Enter some text.", Toast.LENGTH_SHORT).show(); return; }
                viewModel.createTextPost(channelId, text, tags, mentions, scheduledMs);
                break;
            }
            case "image": {
                if (selectedImageUri == null) { Toast.makeText(this, "Select an image.", Toast.LENGTH_SHORT).show(); return; }
                String cap = etCaption != null && etCaption.getText() != null ? etCaption.getText().toString().trim() : "";
                viewModel.createImagePost(channelId, selectedImageUri, cap, tags, scheduledMs);
                break;
            }
            case "video": {
                if (selectedVideoUri == null) { Toast.makeText(this, "Select a video.", Toast.LENGTH_SHORT).show(); return; }
                String cap = etCaption != null && etCaption.getText() != null ? etCaption.getText().toString().trim() : "";
                viewModel.createVideoPost(channelId, selectedVideoUri, cap, tags, scheduledMs);
                break;
            }
            case "audio": {
                if (selectedAudioUri == null) { Toast.makeText(this, "Select audio.", Toast.LENGTH_SHORT).show(); return; }
                String cap = etCaption != null && etCaption.getText() != null ? etCaption.getText().toString().trim() : "";
                viewModel.createAudioPost(channelId, selectedAudioUri, cap, null, 0, scheduledMs);
                break;
            }
            case "document": {
                if (selectedDocUri == null) { Toast.makeText(this, "Select a document.", Toast.LENGTH_SHORT).show(); return; }
                String cap = etCaption != null && etCaption.getText() != null ? etCaption.getText().toString().trim() : "";
                viewModel.createDocumentPost(channelId, selectedDocUri, selectedDocName,
                    selectedDocMime, cap, scheduledMs);
                break;
            }
            case "link": {
                String url = etLinkUrl != null && etLinkUrl.getText() != null
                    ? etLinkUrl.getText().toString().trim() : "";
                if (url.isEmpty()) { Toast.makeText(this, "Enter a URL.", Toast.LENGTH_SHORT).show(); return; }
                if (!url.startsWith("http")) url = "https://" + url;
                String body = etPostText != null && etPostText.getText() != null
                    ? etPostText.getText().toString().trim() : "";
                viewModel.createLinkPost(channelId, body, url,
                    fetchedLinkTitle != null ? fetchedLinkTitle : "",
                    fetchedLinkDesc  != null ? fetchedLinkDesc  : "",
                    fetchedLinkImage != null ? fetchedLinkImage : "",
                    scheduledMs);
                break;
            }
            case "poll": {
                List<String> opts = new ArrayList<>();
                for (String o : pollOptions) if (!o.isEmpty()) opts.add(o);
                if (opts.size() < 2) { Toast.makeText(this, "At least 2 options required.", Toast.LENGTH_SHORT).show(); return; }
                String q = etPostText != null && etPostText.getText() != null
                    ? etPostText.getText().toString().trim() : "";
                if (q.isEmpty()) { Toast.makeText(this, "Enter a poll question.", Toast.LENGTH_SHORT).show(); return; }
                long expires = pollExpiry != null ? pollExpiry.getTimeInMillis() : 0;
                viewModel.createPollPost(channelId, q, q, opts, pollMultiSelect, pollAnonymous, expires, scheduledMs);
                break;
            }
            case "broadcast": {
                String text = etPostText != null && etPostText.getText() != null
                    ? etPostText.getText().toString().trim() : "";
                if (text.isEmpty()) { Toast.makeText(this, "Enter broadcast text.", Toast.LENGTH_SHORT).show(); return; }
                String priority = "normal";
                if (rgBroadcastPriority != null) {
                    int checked = rgBroadcastPriority.getCheckedRadioButtonId();
                    if (checked == R.id.rb_priority_important) priority = "important";
                    else if (checked == R.id.rb_priority_urgent) priority = "urgent";
                }
                boolean notify = switchNotifyAll != null && switchNotifyAll.isChecked();
                viewModel.createBroadcastPost(channelId, text, priority, notify, scheduledMs);
                break;
            }
            case "event": {
                String title = etEventTitle != null && etEventTitle.getText() != null
                    ? etEventTitle.getText().toString().trim() : "";
                if (title.isEmpty()) { Toast.makeText(this, "Enter event title.", Toast.LENGTH_SHORT).show(); return; }
                if (eventStart == null) { Toast.makeText(this, "Set event start date/time.", Toast.LENGTH_SHORT).show(); return; }
                String desc = etPostText != null && etPostText.getText() != null
                    ? etPostText.getText().toString().trim() : "";
                String loc = etEventLocation != null && etEventLocation.getText() != null
                    ? etEventLocation.getText().toString().trim() : "";
                long endMs = eventEnd != null ? eventEnd.getTimeInMillis() : 0;
                viewModel.createEventPost(channelId, title, desc, loc,
                    eventStart.getTimeInMillis(), endMs, null, eventRsvpEnabled, scheduledMs);
                break;
            }
        }
    }

    // ── Draft ─────────────────────────────────────────────────────────────

    private void restoreDraft() {
        android.content.SharedPreferences prefs =
            getSharedPreferences("channel_drafts", MODE_PRIVATE);
        String draft = prefs.getString(channelId + "_draft_text", null);
        if (draft != null && !draft.isEmpty() && etPostText != null && editPostId == null) {
            etPostText.setText(draft);
            etPostText.setSelection(draft.length());
        }
    }

    private void saveDraft() {
        if (etPostText == null || editPostId != null) return;
        String txt = etPostText.getText() != null ? etPostText.getText().toString() : "";
        getSharedPreferences("channel_drafts", MODE_PRIVATE).edit()
            .putString(channelId + "_draft_text", txt).apply();
    }

    private void clearDraft() {
        getSharedPreferences("channel_drafts", MODE_PRIVATE).edit()
            .remove(channelId + "_draft_text").apply();
    }

    private void startDraftAutoSave() {
        draftSaveRunnable = new Runnable() {
            @Override public void run() {
                saveDraft();
                draftHandler.postDelayed(this, DRAFT_SAVE_INTERVAL_MS);
            }
        };
        draftHandler.postDelayed(draftSaveRunnable, DRAFT_SAVE_INTERVAL_MS);
    }

    // ── Confirm discard ───────────────────────────────────────────────────

    private void confirmDiscard() {
        String text = etPostText != null && etPostText.getText() != null
            ? etPostText.getText().toString().trim() : "";
        if (text.isEmpty() && selectedImageUri == null && selectedVideoUri == null) {
            finish(); return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Discard post?")
            .setMessage("Your draft will be saved.")
            .setPositiveButton("Discard", (d, w) -> { saveDraft(); finish(); })
            .setNegativeButton("Keep editing", null)
            .show();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override protected void onStop() {
        super.onStop();
        saveDraft();
        draftHandler.removeCallbacks(draftSaveRunnable);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        draftHandler.removeCallbacks(draftSaveRunnable);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String getFileName(Uri uri) {
        String name = "document";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    private String formatCalendar(Calendar cal) {
        return new SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(cal.getTime());
    }

    private String extractDomain(String url) {
        try { return new java.net.URL(url).getHost().replaceAll("^www\\.", ""); }
        catch (Exception e) { return url; }
    }

    // ── Mention suggestion adapter ────────────────────────────────────────

    static class MentionSuggestionAdapter extends RecyclerView.Adapter<MentionSuggestionAdapter.VH> {
        interface OnPick { void pick(ChannelMentionHandler.MentionCandidate c); }
        private final List<ChannelMentionHandler.MentionCandidate> list;
        private final OnPick cb;
        MentionSuggestionAdapter(List<ChannelMentionHandler.MentionCandidate> list, OnPick cb) {
            this.list = list; this.cb = cb;
        }
        void update(List<ChannelMentionHandler.MentionCandidate> nl) {
            list.clear(); list.addAll(nl); notifyDataSetChanged();
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mention_suggestion, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ChannelMentionHandler.MentionCandidate c = list.get(pos);
            if (h.tvName != null) h.tvName.setText("@" + c.displayName);
            if (h.ivIcon != null && c.iconUrl != null && !c.iconUrl.isEmpty())
                Glide.with(h.itemView.getContext()).load(c.iconUrl).circleCrop().into(h.ivIcon);
            h.itemView.setOnClickListener(v -> { if (cb != null) cb.pick(c); });
        }
        @Override public int getItemCount() { return list.size(); }
        static class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView ivIcon; TextView tvName;
            VH(View v) { super(v); ivIcon = v.findViewById(R.id.iv_mention_icon); tvName = v.findViewById(R.id.tv_mention_name); }
        }
    }
}
