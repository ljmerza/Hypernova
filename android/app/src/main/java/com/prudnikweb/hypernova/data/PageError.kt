package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.R

/** A web view's failure in plain words (never "net::ERR_CONNECTION_REFUSED"): the string to show, from the view's error codes. */
object PageError {
    // android.webkit.WebViewClient.ERROR_*
    private const val HOST_LOOKUP = -2
    private const val CONNECT = -6
    private const val TIMEOUT = -8
    private const val FAILED_SSL = -11
    private const val IO = -7

    /** [code]: a WebViewClient error code on the page; [httpStatus]: the page's own answer when it loaded with one (0 if none). */
    fun text(code: Int, httpStatus: Int = 0): Int = when {
        httpStatus in intArrayOf(502, 503, 504) -> R.string.pg_dev_server
        httpStatus == 404 -> R.string.pg_gone
        httpStatus != 0 -> R.string.pg_other
        code == HOST_LOOKUP -> R.string.pg_dns
        code == CONNECT || code == IO -> R.string.pg_connect
        code == TIMEOUT -> R.string.pg_timeout
        code == FAILED_SSL -> R.string.pg_ssl
        else -> R.string.pg_other
    }
}
