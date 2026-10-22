#!/bin/sh
# A stand-in for Claude Code in tests (and a test daemon), so nothing ever signs the owner in or
# out: `--version`, `auth status --json` (signed in once a sign-in here has finished, as a file in
# $FAKE_CLAUDE_STATE says), and `auth login`, which prints what Claude Code 2.1.300's `auth login`
# prints and takes a pasted code ("code#state", as the sign-in page gives it). Anything else just
# waits, like an interactive session nobody types into.
#
# The login, as the real command behaves (a plain line prompt, not the interactive /login screen):
# each run is a sign-in with an address of its own (its own state and PKCE challenge). A line
# without both halves of a code gets "Invalid code…" and the prompt waits for another. A code the
# server refuses (a wrong or spent code, or another sign-in's: here one starting "Wrong", or with
# another state) ends the command: "Login failed: …", exit 1. A right one for an account on hold
# (here one starting "Held") ends it too, with why and no "Login failed". The right one: "Login
# successful.".
S="${FAKE_CLAUDE_STATE:-/tmp}"
case "$1 $2" in
  "--version "*)
    echo "9.9.9 (Claude Code)"
    ;;
  "--help "*)
    printf 'Usage: claude [options] [command] [prompt]\n\nCommands:\n  auth            Manage authentication\n'
    ;;
  "auth status")
    if [ -f "$S/signed-in" ]; then
      echo '{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"someone@example.com","orgId":"org-1","orgName":"Example Org","subscriptionType":"max"}'
    else
      echo '{"loggedIn":false,"authMethod":"none","apiProvider":"firstParty"}'
      exit 1
    fi
    ;;
  "auth login")
    # Each run a new sign-in: the runs are counted in $S/logins.
    n=$(( $(cat "$S/logins" 2>/dev/null || echo 0) + 1 ))
    echo "$n" > "$S/logins"
    state="FakeState${n}x0123456789abcdefghijklmnopqrstuvw"
    url="https://claude.ai/oauth/authorize?code=true&client_id=00000000-0000-4000-8000-000000000000&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&scope=user%3Ainference+user%3Aprofile+user%3Asessions%3Aclaude_code&code_challenge=FakeChallenge${n}x0123456789abcdefghijklmnopqrs&code_challenge_method=S256&state=${state}"
    printf 'Opening browser to sign in\342\200\246\n'
    printf "If the browser didn't open, visit: %s\n" "$url"
    printf 'Paste code here if prompted > '
    while read -r code; do
      case "$code" in
        ?*"#"?*)
          # The server takes a moment to answer.
          sleep 1
          case "$code" in
            Wrong*) ;;
            # A right code for an account Anthropic has put on hold: not about the code at all.
            Held*)
              echo "Your account is on hold and can't sign in to Claude Code. View details or appeal: https://claude.ai/settings/account" >&2
              exit 1
              ;;
            ?*"#$state")
              touch "$S/signed-in"
              echo "Login successful."
              exit 0
              ;;
          esac
          echo "Login failed: Authentication failed: Invalid authorization code" >&2
          exit 1
          ;;
        *) echo "Invalid code. Please make sure the full code was copied." >&2 ;;
      esac
    done
    exit 1
    ;;
  *)
    # An interactive session: say how it was started (whether a provider key came, never the key), then wait.
    echo "$$ args=$* base=${ANTHROPIC_BASE_URL:-none} key=${ANTHROPIC_AUTH_TOKEN:+set}" >> "$S/runs.log"
    echo "Claude Code (stand-in) is waiting."
    sleep 3600
    ;;
esac
