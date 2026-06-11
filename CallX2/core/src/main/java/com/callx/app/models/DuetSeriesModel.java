package com.callx.app.models;

  import com.google.firebase.database.IgnoreExtraProperties;

  /**
   * DuetSeriesModel — Firebase data model for a Duet Series.
   *
   * Firebase structure:
   *   duetSeries/{seriesId}  →  this object
   *   duetSeriesSubscriptions/{seriesId}/{uid}  →  true (subscriber index)
   *   userSubscribedSeries/{uid}/{seriesId}  →  true (reverse index)
   *   userDuetSeries/{uid}/{seriesId}  →  seriesTitle (creator's series index)
   *
   * Episodes are regular ReelModel objects with:
   *   reel.seriesId == this.seriesId
   *   reel.seriesEpisodeNumber == 1, 2, 3 …
   *   reel.seriesTitle == this.title
   */
  @IgnoreExtraProperties
  public class DuetSeriesModel {

      public String seriesId;
      public String creatorUid;
      public String creatorName;
      public String creatorPhoto;
      public String title;
      public String description;
      /** Thumbnail URL of the cover episode (first or latest episode thumb). */
      public String coverThumbUrl;
      /** Total episodes published so far. Incremented at upload. */
      public int    episodeCount;
      /** Running count of subscribers. Incremented/decremented on subscribe/unsubscribe. */
      public int    subscriberCount;
      public long   createdAt;

      public DuetSeriesModel() {}

      public DuetSeriesModel(String seriesId, String creatorUid, String creatorName,
                             String creatorPhoto, String title, String description) {
          this.seriesId      = seriesId;
          this.creatorUid    = creatorUid;
          this.creatorName   = creatorName;
          this.creatorPhoto  = creatorPhoto;
          this.title         = title;
          this.description   = description;
          this.episodeCount  = 0;
          this.subscriberCount = 0;
          this.createdAt     = System.currentTimeMillis();
      }
  }
  