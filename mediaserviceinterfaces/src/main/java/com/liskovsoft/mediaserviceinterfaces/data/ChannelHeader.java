package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * Channel page header (banner, avatar, description, buttons). Emitted as one of the channel groups.
 */
public interface ChannelHeader {
    String getTitle();
    String getSubtitle();
    String getDescription();
    String getAvatarUrl();
    String getBannerUrl();
    String getMixVideoId();
    String getMixParams();
    boolean isSubscribed();
    String getChannelId();
}
