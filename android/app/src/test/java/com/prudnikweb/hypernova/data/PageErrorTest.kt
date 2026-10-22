package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PageErrorTest {
    @Test fun `web view codes become plain words`() {
        assertEquals(R.string.pg_connect, PageError.text(-6))
        assertEquals(R.string.pg_dns, PageError.text(-2))
        assertEquals(R.string.pg_timeout, PageError.text(-8))
        assertEquals(R.string.pg_ssl, PageError.text(-11))
        assertEquals(R.string.pg_other, PageError.text(-1))
    }

    @Test fun `a dev server that stopped answering is named`() {
        assertEquals(R.string.pg_dev_server, PageError.text(0, 502))
        assertEquals(R.string.pg_dev_server, PageError.text(0, 504))
        assertEquals(R.string.pg_gone, PageError.text(0, 404))
    }
}
