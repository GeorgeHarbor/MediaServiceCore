package com.liskovsoft.youtubeapi.common.models.impl.mediagroup

import com.liskovsoft.mediaserviceinterfaces.data.ChannelHeader
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.youtubeapi.browse.v2.gen.ChannelHeaderRenderer
import com.liskovsoft.youtubeapi.common.models.gen.getHighResThumbnailUrl
import com.liskovsoft.youtubeapi.common.models.gen.getText
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem

internal class ChannelHeaderMediaGroup(header: ChannelHeaderRenderer) : YouTubeMediaGroup(MediaGroup.TYPE_CHANNEL), ChannelHeader {
    private val mixEndpoint = header.buttons?.firstNotNullOfOrNull { it?.buttonRenderer?.navigationEndpoint?.watchEndpoint }
    private val subscribeButton = header.buttons?.firstNotNullOfOrNull { it?.subscribeButtonRenderer }
    private val subtitle = header.subtitle?.lineRenderer?.items?.mapNotNull { it?.lineItemRenderer?.text?.getText() }?.joinToString(" ")
    private val description = header.selectableDescription?.selectableTextRenderer?.compactDescription?.getText()
    // The header comes with a tiny avatar (s72)
    private val avatarUrl = header.avatar?.getHighResThumbnailUrl()?.replace(Regex("=s\\d+-"), "=s288-")
    private val bannerUrl = header.backgroundImage?.getHighResThumbnailUrl()

    init {
        title = header.title?.getText()
        // Not empty, so the group isn't treated as a section that should be continued
        mediaItems = listOf(YouTubeMediaItem().also {
            it.title = title
            it.channelId = subscribeButton?.channelId
            it.cardImageUrl = avatarUrl
        })
    }

    override fun getSubtitle() = subtitle
    override fun getDescription() = description
    override fun getAvatarUrl() = avatarUrl
    override fun getBannerUrl() = bannerUrl
    override fun getMixVideoId() = mixEndpoint?.videoId
    override fun getMixParams() = mixEndpoint?.params
    override fun isSubscribed() = subscribeButton?.subscribed == true
    override fun getChannelId(): String? = subscribeButton?.channelId
}
