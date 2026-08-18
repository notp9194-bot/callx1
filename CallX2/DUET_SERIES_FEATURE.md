# Duet Series Feature — v11

  ## New Feature: Duet Series

  Creator can now group their Duets into a numbered episode series (Part 1, 2, 3 …).
  Viewers can subscribe to any series and receive push + in-app notifications whenever
  a new episode drops.

  ---

  ### New Files

  #### Models
  - `core/…/models/DuetSeriesModel.java`
    Firebase POJO: seriesId, creatorUid, title, description, coverThumbUrl,
    episodeCount, subscriberCount, createdAt.

  #### Activities
  - `feature-reels/…/social/DuetSeriesCreateActivity.java`
    Creator enters series title + description → writes to `duetSeries/{id}` and
    `userDuetSeries/{uid}/{id}` in Firebase. Returns seriesId + title via Activity result.

  - `feature-reels/…/social/DuetSeriesActivity.java`
    Public view of a series: cover art, title, episode/subscriber counts,
    Subscribe/Unsubscribe button, episode list. Opens SingleReelPlayerActivity on tap.

  - `feature-reels/…/social/DuetSeriesEpisodeAdapter.java`
    RecyclerView adapter: "Part N" label, thumbnail, caption, views, duration.

  #### Bottom Sheet
  - `feature-reels/…/social/DuetSeriesPickerBottomSheet.java`
    Shown at post-details step. Lists creator's existing series (from
    `userDuetSeries/{uid}`). Each row shows series title; tap picks it (fetches
    current episodeCount → sets nextEpisodeNumber = count + 1). Also has
    "+ Create New Series" (launches DuetSeriesCreateActivity for result) and
    "No Series" clear option.

  #### Workers
  - `feature-reels/…/workers/DuetSeriesNotificationWorker.java`
    WorkManager job. Reads `duetSeriesSubscriptions/{seriesId}`, skips creator,
    calls PushNotify.notifyDuetSeriesEpisode() per subscriber (FCM),
    writes `reel_notifications/{uid}` in-app entry and `reelNotifQueue` fallback,
    increments `duetSeries/{id}/episodeCount` and updates `coverThumbUrl`.

  #### Layouts
  - `activity_duet_series_create.xml`
  - `activity_duet_series.xml`
  - `item_duet_series_episode.xml`
  - `bottom_sheet_series_picker.xml`

  #### Firebase rules
  - `firebase_duet_series_rules.json` — rules for the 4 new Firebase paths.

  ---

  ### Updated Files

  - **ReelModel.java** — new fields: `seriesId`, `seriesEpisodeNumber`, `seriesTitle`
  - **ReelUploadActivity.java** — reads EXTRA_SERIES_* extras; tags ReelModel fields;
    enqueues DuetSeriesNotificationWorker after successful upload.
  - **ReelPostDetailsActivity.java** — "Add to Duet Series" row opens
    DuetSeriesPickerBottomSheet; passes RESULT_SERIES_* back to upload.
  - **PushNotify.java** — new `notifyDuetSeriesEpisode()` method.
  - **ReelFCMNotificationHandler.java** — new `"duet_series_episode"` case →
    BigPicture notification with reel thumbnail, deep-links to SingleReelPlayerActivity.

  ---

  ### Firebase Data Structure

  ```
  duetSeries/{seriesId}                         DuetSeriesModel
  duetSeriesSubscriptions/{seriesId}/{uid}      true
  userSubscribedSeries/{uid}/{seriesId}         true
  userDuetSeries/{uid}/{seriesId}               seriesTitle   (creator's index)
  reels/{reelId}.seriesId                       String
  reels/{reelId}.seriesEpisodeNumber            int
  reels/{reelId}.seriesTitle                    String
  ```

  ---

  ### How to integrate DuetSeriesPickerBottomSheet in ReelPostDetailsActivity layout

  Add this row inside `activity_reel_post_details.xml` (after the stitch toggle row):

  ```xml
  <TextView
      android:id="@+id/tv_series_picker"
      android:layout_width="match_parent"
      android:layout_height="48dp"
      android:text="Add to Duet Series"
      android:textColor="#CCFFFFFF"
      android:textSize="15sp"
      android:gravity="center_vertical"
      android:paddingStart="16dp"
      android:paddingEnd="16dp"
      android:background="?attr/selectableItemBackground"
      android:drawableEnd="@android:drawable/arrow_down_float"/>
  ```

  ### How to pass series data from ReelPostDetailsActivity → ReelUploadActivity

  In `ReelPostDetailsActivity` result building:
  ```java
  intent.putExtra(ReelUploadActivity.EXTRA_SERIES_ID,      selectedSeriesId);
  intent.putExtra(ReelUploadActivity.EXTRA_SERIES_TITLE,   selectedSeriesTitle);
  intent.putExtra(ReelUploadActivity.EXTRA_EPISODE_NUMBER, selectedEpisodeNumber);
  ```

  ### How to open DuetSeriesActivity (e.g. from reel feed "Part N of …" chip)

  ```java
  Intent i = new Intent(ctx, DuetSeriesActivity.class);
  i.putExtra(DuetSeriesActivity.EXTRA_SERIES_ID, reel.seriesId);
  startActivity(i);
  ```
  

  ---

  ## Series Profile Tab — v11.1

  ### Additional new files

  - `profile/UserSeriesGridAdapter.java`  
    2-column grid adapter for DuetSeriesModel cards. Shows cover art, title,
    episode count pill, subscriber count, and "NEW" badge (series < 72 h old).
    Tap opens `DuetSeriesActivity`.

  - `res/layout/item_duet_series_card.xml`  
    Card layout: 160 dp cover image, episode count overlay, NEW badge, title, subscriber count.

  - `res/drawable/ic_duet_series.xml`  
    Vector icon (two stacked frames with play triangle) used for the Series tab.

  ### Updated files

  - `profile/UserReelsActivity.java`  
    Added `TAB_SERIES = 4`. New `rvSeries` RecyclerView, `UserSeriesGridAdapter`,  
    `seriesTabData` list, `seriesLoaded` flag. Methods updated:  
    `activeTabData()`, `loadCurrentTab()`, `onTabSelected()` — rvSeries is shown /  
    rvReels hidden when Series tab is active.  
    New `loadSeriesTab()` — reads `userDuetSeries/{uid}` → fetches each  
    `duetSeries/{id}` in parallel → renders sorted grid.

  - `res/layout/activity_user_reels.xml`  
    TabLayout `tabMode` changed from `fixed` → `scrollable` (to fit 5 tabs).  
    Added "Reposted" and "Series" TabItems.  
    Added `rv_series` RecyclerView (visibility=gone by default).
  