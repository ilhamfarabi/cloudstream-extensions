package com.tokusatsu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TokusatsuPlugin: Plugin() {
    override fun load(context: Context) {
        // Registers the main API for this plugin
        registerMainAPI(Tokusatsu())
        registerExtractorAPI(Tokusatsu.P2pplay())
    }
}