package com.mapbox.vision.ar

import com.mapbox.vision.ar.core.VisionArEventsListener
import com.mapbox.vision.ar.core.models.ArCamera
import com.mapbox.vision.ar.core.models.ArLane
import com.mapbox.vision.utils.listeners.CompositeListener

class CompositeVisionArEventsListener : CompositeListener.WeakRefImpl<VisionArEventsListener>(),
    VisionArEventsListener {

    override fun onArCameraUpdated(arCamera: ArCamera) {
        forEach {onArCameraUpdated(arCamera) }
    }

    override fun onArLaneUpdated(arLane: ArLane) {
        forEach {onArLaneUpdated(arLane) }
    }
}