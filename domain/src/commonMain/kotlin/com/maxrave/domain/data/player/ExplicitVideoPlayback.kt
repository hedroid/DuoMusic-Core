package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A one-track video request from "Watch MV". It does not change the user's video preference. */
object ExplicitVideoPlayback {
    private val _videoId = MutableStateFlow<String?>(null)
    val videoId: StateFlow<String?> = _videoId
    private var started = false

    fun select(id: String) {
        _videoId.value = id
        started = false
    }

    fun includes(id: String?): Boolean = id != null && _videoId.value == id

    /** Once the requested MV has played, a later track transition ends the override. */
    fun onCurrentMediaChanged(id: String?) {
        if (id == _videoId.value) {
            started = true
        } else if (started) {
            _videoId.value = null
            started = false
        }
    }
}
