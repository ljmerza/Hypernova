package com.prudnikweb.hypernova.widget

/**
 * What [Secrets] must get right, checked by a debug build once per install (WidgetSync logs the
 * outcome under "HypernovaSecrets"), since the app has no JVM tests. Each case is what the home
 * screen must show: credentials masked, and commands and prose left exactly as they are (a
 * command that's left alone keeps Allow on the widget; masked, it gets Open). Then the shapes that
 * would make a pattern slow, if any of its repeats weren't bounded.
 */
internal object SecretsCheck {
    /** [input] must come out as [want]: masked as a command line, or as prose. */
    private class Case(val input: String, val want: String, val command: Boolean = true)

    private fun same(s: String, command: Boolean = true) = Case(s, s, command)

    /**
     * A request body of 544 characters with "&" all through it: longer than the old bound on a
     * quoted string (400), inside the window the widget masks ([Secrets.WINDOW]). Declared before
     * [CASES], which reads it as it's built.
     */
    private val BODY = "a=1&".repeat(136)

    private val CASES = listOf(
        // curl's and wget's -u, within that one command (the review's fifteen first).
        Case("curl -u admin:hunter2 https://x.com", "curl -u ••• https://x.com"),
        Case("curl \"https://api.x.com/items?limit=10&offset=0\" -u admin:hunter2", "curl \"https://api.x.com/items?limit=10&offset=0\" -u •••"),
        Case("curl 'https://api.x.com/items?a=1&b=2' --user admin:hunter2", "curl 'https://api.x.com/items?a=1&b=2' --user •••"),
        Case("curl -H 'Content-Type: application/json; charset=utf-8' -u admin:hunter2 https://x.com", "curl -H 'Content-Type: application/json; charset=utf-8' -u ••• https://x.com"),
        Case("curl -u \"admin:p w\" https://x", "curl -u ••• https://x"),
        Case("curl -uadmin:pw https://x", "curl -u••• https://x"),
        Case("/usr/bin/curl --user=admin:pw https://x", "/usr/bin/curl --user=••• https://x"),
        Case("curl -u 1234:567890 https://api.x.com", "curl -u ••• https://api.x.com"),
        same("docker run -u 1000:1000 img"),
        same("curl https://x && docker run -u 1000:1000 img"),
        same("curl https://x | sudo -u app:app tee f"),
        same("curl https://x\ndocker run -u app:app img"),
        same("ssh -u a:b host"),
        same("curly -u a:b"),
        Case("wget --user=admin:hunter2 https://x", "wget --user=••• https://x"),
        // A "|", ";" or "&" inside quotes ends nothing.
        Case("curl -H 'X-Filter: a|b' -u admin:hunter2 https://x", "curl -H 'X-Filter: a|b' -u ••• https://x"),
        Case("curl -d \"a=1;b=2&c=3|d\" --user admin:hunter2 https://x", "curl -d \"a=1;b=2&c=3|d\" --user ••• https://x"),
        Case("curl 'https://x/?q=a;b' -u admin:hunter2", "curl 'https://x/?q=a;b' -u •••"),
        Case("curl -d \"line1\nline2\" -u admin:hunter2 https://x", "curl -d \"line1\nline2\" -u ••• https://x"),
        // An escaped quote doesn't close a double-quoted body, and a quote that can't be closed
        // doesn't end the command: the "-u" after them is still found.
        Case(
            "curl -X POST -d \"{\\\"user\\\":\\\"bob\\\"}\" -u admin:hunter2 https://api.x.com",
            "curl -X POST -d \"{\\\"user\\\":\\\"bob\\\"}\" -u ••• https://api.x.com",
        ),
        Case(
            "curl -X POST -H \"Content-Type: application/json\" -d \"{\\\"text\\\": \\\"\$MSG\\\"}\" -u admin:hunter2 https://hooks.x.com",
            "curl -X POST -H \"Content-Type: application/json\" -d \"{\\\"text\\\": \\\"\$MSG\\\"}\" -u ••• https://hooks.x.com",
        ),
        Case("curl -d '${"x".repeat(450)}' -u admin:hunter2 https://x", "curl -d '${"x".repeat(450)}' -u ••• https://x"),
        // A body of 544 characters with "&" in it, inside the window the widget masks: read whole,
        // in either quotes, so its "&" ends nothing and the "-u" after it is found.
        Case("curl -d \"$BODY\" -u admin:hunter2 https://x", "curl -d \"$BODY\" -u ••• https://x"),
        Case("curl -d '$BODY' -u admin:hunter2 https://x", "curl -d '$BODY' -u ••• https://x"),
        // A value cluster ("-XPUT": the U is the method's) isn't "-u"; flags without a value are.
        same("curl -XPUT https://x"),
        same("curl -sXPUT localhost:9200/idx"),
        Case("curl -XPUT -u admin:hunter2 https://x", "curl -XPUT -u ••• https://x"),
        Case("curl -fsSLu admin:hunter2 https://x", "curl -fsSLu ••• https://x"),
        Case("curl -u \"admin:p\\\"w rest\" https://x", "curl -u ••• https://x"),
        // A line continued with "\", flags run together, a key as the user, the next command.
        Case("curl https://x \\\n  -u admin:hunter2", "curl https://x \\\n  -u •••"),
        Case("curl https://x \\\n-u admin:hunter2", "curl https://x \\\n-u •••"),
        Case("curl -su elastic:changeme localhost:9200", "curl -su ••• localhost:9200"),
        Case("curl https://api.stripe.com/v1/charges -u sk_test_4eC39HqLyjWDarjtT1zdp7dc:", "curl https://api.stripe.com/v1/charges -u •••"),
        Case("curl -u admin:hunter2 https://x; docker run -u 1000:1000 img", "curl -u ••• https://x; docker run -u 1000:1000 img"),
        same("curl --user-agent 'Mozilla/5.0 (X11; Linux)' https://x"),
        Case("curl -U proxy:pw --oauth2-bearer abc https://x", "curl -U ••• --oauth2-bearer ••• https://x"),
        Case("curl --proxy-user p:q -u admin:hunter2 https://x", "curl --proxy-user ••• -u ••• https://x"),
        Case("curl --oauth2-bearer abc https://x", "curl --oauth2-bearer ••• https://x"),

        // A value after a key's name, as the Mac masks titles: only what looks like a credential.
        Case("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl", "Authorization: Bearer •••", command = false),
        Case("curl -H \"Authorization: Bearer abcDEF1234567890xyz\" https://x", "curl -H \"Authorization: Bearer •••\" https://x"),
        Case("Authorization: Basic YWRtaW46aHVudGVyMg==", "Authorization: Basic •••", command = false),
        Case("Bearer abcdefGHIJKLmnopqrst", "Bearer •••", command = false),
        Case("TOKEN=abc12345", "TOKEN=•••", command = false),
        Case("X-Api-Key: 9f8e7d6c5b4a", "X-Api-Key: •••", command = false),
        Case("BASIC_AUTH=user:pass1234", "BASIC_AUTH=•••", command = false),
        Case("X-Auth-Key: abcd1234efgh", "X-Auth-Key: •••", command = false),
        Case("client_secret: AbCdEfGhIjKlMnOpQr", "client_secret: •••", command = false),
        Case("{\"api_key\": \"abc123def456\"}", "{\"api_key\": •••}", command = false),
        Case("--password hunter22", "--password •••", command = false),
        Case("token: abc12345.", "token: •••.", command = false),
        Case("key sk-ant-api03-abcdefghijklmnopqrstuv", "key •••", command = false),
        Case("https://user:pa55word@host/x", "https://•••@host/x", command = false),
        // After a key's name and an explicit scheme, 8 or more token characters are a credential.
        Case("Authorization: Token abcdefghij", "Authorization: Token •••", command = false),
        Case("X-Api-Key: ApiKey lowercaseonly", "X-Api-Key: ApiKey •••", command = false),
        Case("curl -H 'Authorization: Bot MTk4NjIyNDgzNDcxOTI1MjQ4.Cl2FMQ.ZnCjm1XVW7vRze4b7Cq4se7kKWs' https://discord.com/api", "curl -H 'Authorization: Bot •••' https://discord.com/api"),
        Case("curl -H 'Authorization: Token abc123def' https://x", "curl -H 'Authorization: Token •••' https://x"),
        // Prose stays as it is.
        same("auth flow is broken", command = false),
        same("Bearer of bad news", command = false),
        same("password reset page", command = false),
        same("Fix the auth: login loop", command = false),
        same("Token: refresh on expiry", command = false),
        same("Authorization: Bearer header missing", command = false),
        same("Authorization: Token expired", command = false),
        same("Bearer authentication-scheme", command = false),
        same("OAuth: redirect fails", command = false),
        same("AUTH=abc12345", command = false),
        same("api_key: lowercaseonlyvaluehere", command = false),
        same("password: hunter2", command = false),
        same("export GITHUB_TOKEN=\$GH", command = false),

        // On a command line a key's value is a key, whatever it looks like; a variable shows nothing.
        Case("PGPASSWORD=hunter2 psql -h db", "PGPASSWORD=••• psql -h db"),
        Case("mysql --password=hunter2 -e 'select 1'", "mysql --password=••• -e 'select 1'"),
        same("curl -H \"Authorization: Bearer \$GITHUB_TOKEN\" https://api.github.com/user"),
        same("docker login -u bob --password-stdin"),
        same("git commit -m \"Bearer of bad news\""),
        // The curly quotes the widget puts around a Grep pattern are never part of a value.
        same("“API_KEY=” in src"),
        same("“password:” in config"),
        Case("“password: hunter2” in config", "“password: •••” in config"),
        // A value in smart quotes right after the name: the opening quote stays with the name.
        Case("PASSWORD=“s3cretpass”", "PASSWORD=“•••”", command = false),
        Case("password: “hunter2abc”", "password: “•••”", command = false),
        Case("AUTH_TOKEN: ‘abc12345’", "AUTH_TOKEN: ‘•••’", command = false),
        Case("export API_KEY=“lowercaseonly”", "export API_KEY=“•••”"),
        same("password: “reset”", command = false),
        // After the plural "tokens" a count is a count, in prose and on a command line.
        same("tokens: 1,234,567", command = false),
        same("Used input_tokens: 12_345_678 today", command = false),
        same("curl -d '{\"model\":\"claude\",\"max_tokens\":4096}' https://api.x.com/v1/messages"),
        same("MAX_TOKENS=100000 claude -p hi"),
        Case("tokens: abc12345", "tokens: •••", command = false),
        Case("TOKEN=12345678", "TOKEN=•••", command = false),
        Case("GITHUB_TOKEN=1234567890 gh pr list", "GITHUB_TOKEN=••• gh pr list"),

        // The Mac's own cases for the shared rule (daemon/test/normalize.test.ts), as it masks
        // titles: the two must agree on every one.
        Case("api_key: ‘abc123def456’ was in the log", "api_key: ‘•••’ was in the log", command = false),
        Case("set PASSWORD= “s3cretpass\" first", "set PASSWORD= “•••\" first", command = false),
        Case("client_secret:“AbCdEfGhIjKlMnOpQr”.", "client_secret:“•••”.", command = false),
        same("password: “forgotten”", command = false),
        same("the TOKEN: “none”", command = false),
        same("TOKEN=” abc12345", command = false),
        same("PASSWORD=““abc12345”", command = false),
        same("{\"output_tokens\": 12345678}", command = false),
        same("MAX_TOKENS=1_000_000_000", command = false),
        same("cache_read_input_tokens: 98765432.", command = false),
        same("Tokens: “1,234,567”", command = false),
        Case("TOKENS=abc12345", "TOKENS=•••", command = false),
        Case("tokens: 1234-5678-90", "tokens: •••", command = false),
        Case("token: 12345678", "token: •••", command = false),
        Case("PASSWORD=12,345,678", "PASSWORD=•••", command = false),
        Case("Authorization: Bearer 12345678", "Authorization: Bearer •••", command = false),
        Case("curl -u admin https://example.com", "curl -u ••• https://example.com", command = false),
        Case("curl -x proxy -U me:pw --oauth2-bearer abc x", "curl -x proxy -U ••• --oauth2-bearer ••• x", command = false),
        same("sudo -u postgres psql", command = false),
        same("curl x | ssh -u admin:pw host", command = false),
        same("make curlish -u a:b", command = false),
    )

    /** [input], as the widget shows it ([Secrets.shown]), must not show [secret]. */
    private class Leak(val input: String, val secret: String, val command: Boolean = true)

    /**
     * Texts longer than [Secrets.WINDOW] with a credential where the cut falls. Cut first, a
     * quoted "-u" value split at a space inside it was half a value, which nothing masked: the
     * review's case is k = 141. Each runs across the cut, in both quote styles, as prose too,
     * and after keys that masking shortens, so the masked text shifts against the raw one.
     */
    private val LEAKS = buildList {
        for (k in 120..160) {
            val body = "a=1&".repeat(k)
            add(Leak("curl -d '$body' -u \"deploy:s3cretpass9 x\" https://hooks.example.com/run", "s3cret"))
            add(Leak("curl -d \"$body\" --user 'deploy:s3cretpass9 x y' https://hooks.example.com/run", "s3cret"))
            add(Leak("curl -d '$body' -u \"deploy:s3cretpass9 x\" https://hooks.example.com/run", "s3cret", command = false))
            add(Leak("note ${"word ".repeat(k)}{\"api_key\": \"abc123def456ghi789\"} done", "abc123", command = false))
            add(Leak("${"sk-${"x".repeat(60)} ".repeat(k / 20)}curl -d '$body' -u \"deploy:s3cretpass9 x\" https://x", "s3cret"))
        }
    }

    /**
     * Inputs of [SIZE] characters shaped to make a pattern backtrack: a long run of name
     * characters, many starts ("curl", "Bearer", "TOKEN-"), an unclosed quote, a run of escapes.
     * Bounded repeats keep each linear; an unbounded one would take seconds here. A line of long
     * quoted strings checks the stack too: a repeat that nested a call per character overflowed it.
     */
    private const val SIZE = 10_000
    private val SLOW = listOf(
        "a".repeat(SIZE),
        "a-".repeat(SIZE / 2),
        "TOKEN-".repeat(SIZE / 6),
        "AUTH_".repeat(SIZE / 5),
        "curl ".repeat(SIZE / 5),
        "curl \"" + "a".repeat(SIZE),
        "curl \"" + "\\a".repeat(SIZE / 2),
        "curl " + ("\"" + "a".repeat(Secrets.WINDOW - 2) + "\" ").repeat(SIZE / (Secrets.WINDOW + 1)),
        "curl " + ("'" + "a".repeat(Secrets.WINDOW - 2) + "' ").repeat(SIZE / (Secrets.WINDOW + 1)),
        "curl " + "\"\\'".repeat(SIZE / 3),
        "curl '" + "a;".repeat(SIZE / 2),
        "curl " + " -u".repeat(SIZE / 3),
        "Bearer ".repeat(SIZE / 7),
        "x://a:".repeat(SIZE / 6),
        "password=" + "1".repeat(SIZE),
        "\"a\":".repeat(SIZE / 4),
        "Authorization: Bearer ".repeat(SIZE / 22),
        "Authorization: Token ".repeat(SIZE / 21),
        // Smart quotes after a name, and a count after "tokens".
        "PASSWORD=“".repeat(SIZE / 10),
        "TOKEN: “" + "a".repeat(SIZE),
        "tokens: " + "1,".repeat(SIZE / 2),
        "max_tokens=".repeat(SIZE / 11),
    )

    /** Each shape's budget: linear work over [SIZE] characters takes a few milliseconds. */
    private const val BUDGET_MS = 200L

    class Outcome(val cases: Int, val failures: List<String>, val slowestMs: Long)

    fun run(): Outcome {
        val failures = ArrayList<String>()
        for (c in CASES) {
            val got = if (c.command) Secrets.command(c.input) else Secrets.mask(c.input)
            if (got != c.want) failures += "${if (c.command) "command" else "prose"} ${show(c.input)} -> ${show(got)}, want ${show(c.want)}"
        }
        for (l in LEAKS) {
            val got = Secrets.shown(l.input, l.command)
            if (l.secret in got || got.length > Secrets.WINDOW) failures += "shown ${show(l.input.take(40))}… -> ${show(got)}"
        }
        // The review's case, as it must read: everything up to the cut, the password masked.
        val review = "curl -d '${"a=1&".repeat(141)}' -u \"deploy:s3cretpass9 x\" https://hooks.example.com/run"
        val want = "curl -d '${"a=1&".repeat(141)}' -u •••"
        Secrets.shown(review, command = true).let { if (it != want) failures += "shown review case -> ${show(it)}" }
        // Within the window, the whole text is masked and nothing is cut.
        for (c in CASES) {
            val got = Secrets.shown(c.input, c.command)
            if (got != c.want) failures += "shown ${show(c.input)} -> ${show(got)}, want ${show(c.want)}"
        }
        var slowest = 0L
        for (s in SLOW) {
            val t0 = System.nanoTime()
            // A shape that runs the matcher out of stack is a failure to log, not a crash.
            val error = runCatching { Secrets.command(s) }.exceptionOrNull()
            val ms = (System.nanoTime() - t0) / 1_000_000
            slowest = maxOf(slowest, ms)
            if (error != null) failures += "${show(s.take(24))}… threw $error"
            else if (ms > BUDGET_MS) failures += "slow: ${show(s.take(24))}… took $ms ms"
        }
        return Outcome(CASES.size * 2 + LEAKS.size + 1 + SLOW.size, failures, slowest)
    }

    private fun show(s: String) = "\"" + s.replace("\n", "\\n") + "\""
}
