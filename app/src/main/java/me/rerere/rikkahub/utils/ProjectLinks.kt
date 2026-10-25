package me.rerere.rikkahub.utils

/** Links owned by this fork; upstream attribution is kept separately in the About page. */
internal object ProjectLinks {
    const val REPOSITORY = "yaselli/watermelon-chat"
    const val LEGACY_REPOSITORY = "yaselli/orangechat"
    const val HOME = "https://github.com/$REPOSITORY"
    const val ISSUES = "$HOME/issues"
    const val DOCUMENTATION = "$HOME/blob/build/stable-release/docs/USER_GUIDE_ZH.md"
    const val LICENSE = "$HOME/blob/build/stable-release/LICENSE"
    const val QQ_GROUP = "728342625"

    // The new name may not exist yet while the compatibility build is being distributed.
    val updateRepositories = listOf(REPOSITORY, LEGACY_REPOSITORY)
}
