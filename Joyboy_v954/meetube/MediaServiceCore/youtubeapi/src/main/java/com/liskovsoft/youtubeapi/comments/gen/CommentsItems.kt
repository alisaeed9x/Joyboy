package com.liskovsoft.youtubeapi.comments.gen

import com.liskovsoft.youtubeapi.common.models.gen.ButtonContentWrapper
import com.liskovsoft.youtubeapi.common.models.gen.NavigationEndpointItem
import com.liskovsoft.youtubeapi.common.models.gen.TextItem
import com.liskovsoft.youtubeapi.common.models.gen.ThumbnailItem

internal data class CommentItemWrapper(
    val commentThreadRenderer: CommentThreadRenderer?,
    val commentRenderer: CommentRenderer? // the active comment
) {
    data class CommentThreadRenderer(
        val comment: Comment?
    ) {
        data class Comment(
            val commentRenderer: CommentRenderer?
        )
    }
}

internal data class CommentRenderer(
    val commentId: String?,
    val authorText: TextItem?,
    val authorThumbnail: ThumbnailItem?,
    val publishedTimeText: TextItem?,
    val contentText: TextItem?,
    val detailViewEndpoint: NavigationEndpointItem?,
    val isLiked: Boolean?,
    val voteCount: TextItem?,
    val repliesCount: TextItem?,
    val actionButtons: ActionButtonsWrapper?,
    val pinnedCommentBadge: PinnedCommentBadge?, // NEWTUBE(comments-panel)
    val imageAttachmentViewModel: ImageAttachmentViewModel? // MEETUBE(issue #190): a GIF posted in the comment
) {
    data class ImageAttachmentViewModel(
        val commentImageAttachmentViewModel: CommentImageAttachmentViewModel?
    ) {
        data class CommentImageAttachmentViewModel(
            val image: ThumbnailItem?, // sources[]: an animated webp, signed (bare URLs 404)
            val aspectRatio: Float?
        )
    }
    data class PinnedCommentBadge(
        val pinnedCommentBadgeRenderer: PinnedCommentBadgeRenderer?
    ) {
        data class PinnedCommentBadgeRenderer(
            val label: TextItem? // "Pinned by @handle"
        )
    }
    data class ActionButtonsWrapper(
        val commentActionButtonsRenderer: CommentActionButtonsRenderer?
    ) {
        data class CommentActionButtonsRenderer(
            val likeButton: ButtonContentWrapper?,
            val dislikeButton: ButtonContentWrapper?
        )
    }
}