package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PageErrorTest {
    @Test fun `web view codes become plain words`() {
        assertEquals(Str.pg_connect, PageError.text(-6))
        assertEquals(Str.pg_dns, PageError.text(-2))
        assertEquals(Str.pg_timeout, PageError.text(-8))
        assertEquals(Str.pg_ssl, PageError.text(-11))
        assertEquals(Str.pg_other, PageError.text(-1))
    }

    @Test fun `a dev server that stopped answering is named`() {
        assertEquals(Str.pg_dev_server, PageError.text(0, 502))
        assertEquals(Str.pg_dev_server, PageError.text(0, 504))
        assertEquals(Str.pg_gone, PageError.text(0, 404))
    }

    @Test fun `a localhost preview names its port`() {
        assertEquals(Str.pg_dev_server_port, PageError.text(0, 502, port = 5173))
        assertEquals(Str.pg_gone, PageError.text(0, 404, port = 5173))
    }
}
