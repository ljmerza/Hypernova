package com.prudnikweb.hypernova.data



/** A web view's failure in plain words (never "net::ERR_CONNECTION_REFUSED"): the string to show, from the view's error codes. */
object PageError {
    // android.webkit.WebViewClient.ERROR_*
    private const val HOST_LOOKUP = -2
    private const val CONNECT = -6
    private const val TIMEOUT = -8
    private const val FAILED_SSL = -11
    private const val IO = -7

    /**
     * [code]: a WebViewClient error code on the page; [httpStatus]: the page's own answer when it
     * loaded with one (0 if none); [port]: the dev server's, for a localhost preview (its string then
     * takes the port as its argument).
     */
    fun text(code: Int, httpStatus: Int = 0, port: Int? = null): Str = when {
        httpStatus in intArrayOf(502, 503, 504) -> if (port != null) Str.pg_dev_server_port else Str.pg_dev_server
        httpStatus == 404 -> Str.pg_gone
        httpStatus != 0 -> Str.pg_other
        code == HOST_LOOKUP -> Str.pg_dns
        code == CONNECT || code == IO -> Str.pg_connect
        code == TIMEOUT -> Str.pg_timeout
        code == FAILED_SSL -> Str.pg_ssl
        else -> Str.pg_other
    }
}
