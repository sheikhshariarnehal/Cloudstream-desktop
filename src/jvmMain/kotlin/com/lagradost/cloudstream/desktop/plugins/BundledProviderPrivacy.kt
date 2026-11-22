package com.lagradost.cloudstream.desktop.plugins

internal fun disableBundledProviderTelemetry() {
    // Disable third-party usage reporting before provider constructors or requests run.
    com.redowan.AnalyticsTracker.ENDPOINT = ""
    com.nehal.ctgmovies.AnalyticsTracker.ENDPOINT = ""
    com.nehal.cineplexbd.AnalyticsTracker.ENDPOINT = ""
    com.nehal.dhakaflix.AnalyticsTracker.ENDPOINT = ""
    com.nehal.dhakaflixbdix.AnalyticsTracker.ENDPOINT = ""
    com.nehal.discoveryftp.AnalyticsTracker.ENDPOINT = ""
    com.nehal.ftpbd.AnalyticsTracker.ENDPOINT = ""
    com.nehal.fmftp.AnalyticsTracker.ENDPOINT = ""
    com.nehal.jellyfinbd.AnalyticsTracker.ENDPOINT = ""
    com.nehal.showtimebd.AnalyticsTracker.ENDPOINT = ""
    com.nehal.vegamovies.AnalyticsTracker.ENDPOINT = ""
    com.allwish.AnalyticsTracker.ENDPOINT = ""
    com.aniwatch.AnalyticsTracker.ENDPOINT = ""
    com.cncverse.AnalyticsTracker.ENDPOINT = ""
}
