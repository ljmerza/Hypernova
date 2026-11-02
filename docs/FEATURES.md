# Features and the tests that guard them

A checklist of every user-facing capability of the Android app, and of the daemon features the app
depends on, with the tests that guard each one. It is the fourth layer of the safety net built
before a move to Kotlin Multiplatform and Compose Multiplatform (docs/PLATFORMS.md). The other three
are the contract fixtures (docs/PROTOCOL.md, "Contract fixtures"), the data-layer rig and the screen
tests. Built from the code, docs/PROTOCOL.md, docs/DECISIONS.md and the screens under
`android/app/src/main/java/com/prudnikweb/hypernova/ui/`, on 2026-10-31. Both suites last passed on
2026-11-01: 1181 app tests and 592 in `android/shared` (each on both of its targets), 1065 daemon tests
(1061 pass, 4 skipped: Mac-only). The 1569 tests from before the move are all among them.

**Update this file whenever a feature or its tests change:** add the row, move its mark, name the test.

## How to read it

- **covered**: the behaviour is asserted.
- **partial**: some paths only; the row says what is missing.
- **gap**: nothing guards it.

Test names, shortened:

- `Class.test` is an app test, under `android/app/src/test/java/com/prudnikweb/hypernova/`, or a data
  test that moved with its code to `android/shared/src/` (docs/PLATFORMS.md, "The shared module"):
  plain ones and `ContractTest` in `commonTest/`, which run on Android's and the desktop's JVM; the
  rig tests that need nothing of Android in `hubTest/`, which run off Android in `:shared` and on
  Robolectric in `:app`.
  - Layer 2, the real `LiveHub`/`Core` against `FakeDaemon`: `data/live/` (ActionsTest,
    AllServersSettingsTest, AppUpdaterTest, AutomationNotesTest, ConnectTest, ConnectionServiceTest,
    CoreServersTest, DeviceCertStoreTest, ErrorsTest, MacOnlyTest, MultiServerTest, MutualTlsTest,
    NotificationControlsTest, NotifyActionsTest, PairTest, ReconnectTest, SettingsCallsTest, SocketEventsTest,
    UsageRefreshCallTest), and `widget/WidgetSyncTest`. `FakeDaemon("z2", lan = true)`
    adds the local listener: HTTPS with its own pinned certificate, a client certificate asked for,
    and a device CA that signs the phone's request; `FakeKeyStore` stands in for AndroidKeyStore.
  - Layer 3, Robolectric and Roborazzi: `ui/screens/`. Two classes share a name, so they are
    written `screens/SessionSheetTest` and `session/SessionSheetTest`.
  - Every other app class is a plain unit test.
- `file.test.ts › "…"` is a daemon test in `daemon/test/`, named by the start of its title.
- **Layer 1** is `ContractTest` with `contract.test.ts`. A fixture is named by its file in
  `protocol/fixtures/`.
- "(all)" means every test in that class or file.

## 1. Pairing and connection

| # | Capability | Tests | Status |
|---|---|---|---|
| P1 | Pair from the server's QR code or link: its `/pair` page hands over to the app; paste a link; `hypernova://` links; an older `hypernova.observer` link read from its fragment | PairLinksTest (all 11); PairTest (all 8: `Pairer.pair` against FakeDaemon, the listener first with its pin, then the base URL, each refusal); PairingScreensTest (all 12: the steps, Paste, adding a server, every outcome); lan.test.ts › "the pairing page on the listener…", "pairing over the listener: open while a code is shown…", "the pairing links point at this server…"; linux-cli.test.ts › "pair: the QR code and its link…", "pair waits for the phone and names it"; fixture rest-pair.json (also PairTest's answer) | **covered** |
| P2 | Pair the same computer again, or another one | ReplacePairingTest (all); ServersTest.pairingAnotherComputerAddsIt, pairingTheSameComputerAgainTakesItsPlace, theSameComputerByIdOrByName; pair.test.ts (all) | **covered** |
| P3 | Device certificate and mutual TLS on the local route (the CSR, POST /v1/device/cert, the pinned server certificate) | CsrTest (all); DeviceCertStoreTest (all 6); MutualTlsTest (all 6: FakeDaemon's HTTPS listener, a real handshake with the client certificate: asked for, issued, shown, renewed, refused, a 401, a wrong pin); LanRouteTest.pinAcceptsTheRightCertificate, pinRefusesAWrongCertificate, pinRefusesAnExpiredCertificate, theDeviceCertificateGoesToTheListenerAlone, theWebViewGoesOnOnlyForThePinnedCertificate; lan.test.ts › "POST /v1/device/cert…", "with its certificate the phone reaches the whole API…", "a new device certificate closes the WebSockets…", "the daemon keeps a device certificate's expiry…"; fixture rest-device-cert.json | **covered** (the key store is `FakeKeyStore`: AndroidKeyStore exists only on a device, so a hardware-held key is never exercised on the JVM) |
| P4 | Pick a route: the local listener first, then the base URL; hotspot sweep; recheck back-off | LanRouteTest (probe and chooser tests); LocalFirstTest (all 11); RouteRecheckTest (all); ConnectTest.theRouterProbesTheBaseUrlFirst; lan.test.ts › "only the local network, the hotspot…", "the advertised addresses are private IPv4 and ULA only…", "a phone on another private subnet…"; linux-fixes.test.ts › "the local route skips bridges…" | **covered** |
| P5 | Connect: the snapshot, then the socket, the token on every request | ConnectTest.theSnapshotFillsSessionsApprovalsAndUsageThenTheSocketOpens, everyRequestCarriesTheToken; fixture snapshot.json; http.test.ts › "/v1/ws takes the token in its query…" | **covered** |
| P6 | Ping and pong; a silent socket is declared dead | ConnectTest.aPingIsAnsweredWithAPong; ReconnectTest.aSilentSocketIsDeclaredDeadAfterSeventySeconds, backOnScreenASocketSilentForThirtySecondsIsReplacedAtOnce; ws-ping.test.ts (both: a ping every 25 s after the snapshot, the pong taken, each socket its own interval); fixture ws-ping.json | **covered** |
| P7 | Reconnect: once at once, then every 30 s; on a new network, screen on, an alarm, Retry now | ReconnectTest (all 13); RetryTest (all); ErrorsTest.aServerThatIsntThereIsOfflineAndTriedAgainAtOnceThenEveryThirtySeconds, aServerErrorOnTheSnapshotIsRetried, anActionWhileTheServerIsAwaySaysItIsntAnswering | **covered** |
| P8 | A 401 anywhere unpairs | ErrorsTest.a401OnTheSnapshotUnpairsAndNothingIsTriedAgain, a401OnTheSocketUnpairs, a401OnAnActionUnpairs, a401WhileOpeningASessionUnpairs | **covered** |
| P9 | Forget a server (DELETE /v1/devices/me) | AllServersSettingsTest.eachServersBlockActsOnItsOwnServer; ServersTest.forgettingTheFirstServerClearsTheOldKeysToo, forgettingTheActiveOneShowsTheNext; lan.test.ts › "forgetting a device revokes its certificate…" | **covered** |
| P10 | Stay connected in the background (`ConnectionService`, its permanent notification and Disconnect, `BootReceiver`) | ConnectionServiceTest (all 6); ReconnectTest.offScreenWithoutTheBackgroundServiceTheNextTryWaitsForTheApp | **covered** |
| P11 | Connection log in Settings | ConnLogTest (all); ConnectionLogScreenTest (both: the screen, TalkBack's sentences, Copy) | **covered** |
| P12 | Older stores still read (the one-pairing keys, Tailscale-era lists) | ServersTest.theOnePairingBecomesTheFirstServerWithItsOldKeys, aListKeptByABuildWithTailscaleStillReadsWithItsTailnetFieldsLeftOut, aBrokenListIsReadAsEmptyAndBadEntriesAreDropped; PairLinksTest.anOlderComputersTailnetHintsAreIgnored, twoServersPairedTheOldWayStillGetBasesOfTheirOwn | **covered** |

## 2. Multi-server and All servers

| # | Capability | Tests | Status |
|---|---|---|---|
| M1 | Several servers, each with its own hub, token and sessions; the others watched in the background | MultiServerTest.eachHubHasItsOwnServersSessionsAndToken, oneServerGoingAwayLeavesTheOtherLinked; ServersTest.anotherServersKeysAreItsOwn; CoreServersTest.twoComputersPairedTheSwitcherAllServersAndAnswersGoingToTheRightOne | **covered** |
| M2 | Server switcher: names, needs-you count, the chip's link | ServerSwitcherTest (all); ServersTest.needsYouCountsLiveSessionsWaitingOnTheOwner, theChipCountsOnlyTheOtherServersThatAreLinked; MultiServerTest.theChipCountsTheOtherServersNeedsYouWhileTheyAreFresh; AllServersTest.theChipIsTheWorstLink, countsTheOnesNotLinked | **covered** |
| M3 | "All servers" offered with two or more, and kept across restarts | AllServersTest.offUntilChosen, theChoiceSurvivesARestart, showsOnlyWithTwoOrMorePaired, theChoiceLeavesTheListAndThePickAlone | **covered** |
| M4 | All servers Home: merged groups, a tag per card, offline cards dimmed, actions go to their own computer | AllHomeTest (all); HomeScreenTest.allServersTagsEachCardAndSaysAll | **covered** |
| M5 | New session in All servers asks which computer | HomeScreenTest.allServersNewSessionAsksWhichComputer, oneComputerNewSessionGoesStraightToTheSheet; AllHomeTest.theNewSessionPickerListsEveryComputerOnlyLinkedOnesToPick | **covered** |
| M6 | Opening a session from All visits its computer; Back returns to All | CoreServersTest.twoComputersPairedTheSwitcherAllServersAndAnswersGoingToTheRightOne (`Core.visit`, `backToPicked`) | **covered** |
| M7 | All servers in Settings: one block per server, then This phone | SettingsPlanTest (all); AllServersSettingsTest.eachServersBlockActsOnItsOwnServer | **covered** |
| M8 | Automations stay one server's under All ("pick a server") | AllServersAutomationsTest (all 3); AutomationsScreenTest.inAllServersTheAutomationsTabSaysToPickAServerAndStaysOnSessions | **covered** |

## 3. Home

| # | Capability | Tests | Status |
|---|---|---|---|
| H1 | Three groups and their order (A to Z in each by default since H14; Completed was newest first) | HomeGroupsTest (all); HomeScreenTest.groupsInOrderEachSorted | **covered** |
| H2 | A Working card says what it does now and "for 12 min" | CardNowTest (all); HomeScreenTest.workingCardSaysItsTodoAndForHowLong; chat.test.ts › "the list in short for Home…"; turn-clock.test.ts (all) | **covered** |
| H3 | A Needs-you card: Allow and Deny, a terminal dialog denied through the menu, no blind Allow | HomeCardTest.aWholeCommandCanBeAllowedOnTheCard, noBlindAllow, questionsAndPlansOpenTheSession; HomeScreenTest.needsYouCardAllowAnswersItsApproval, needsYouCardDenyAnswersItsApproval, anAnswerThatDidNotGetThroughBringsThePillsBack, terminalDialogCardDeniesThroughTheMenu; TerminalAskTest.homeCardAllowsAndDeniesIt | **covered** |
| H4 | Unread dot and "finished … ago" on Completed cards | UnreadTest (all); HomeScreenTest.unreadCompletedCardHasTheDot; HomeMoreTest.completedCardsAreUnreadTillOpenedAndAReplyAfterBringsTheDotBack (Home reads the read marks; opening the chat clears the dot, a later reply brings it back, a reply while it's on screen doesn't), aHubWithoutAServerKeepsNoMarksAndShowsNoDots | **covered** |
| H5 | Long-press menu: Rename, Stop, Delete (any session at rest, saying what it closes), Restart session (any job or shared terminal running; asks only if something would stop) (and Codex's) | RestartOfferTest (all); HomeMoreTest.anIdleTerminalRestartsAtOnceWithAToast, aTerminalAtWorkAsksFirstSayingTheTurnStops, aTerminalOpenOnTheServerAsksFirstSayingItCloses, aTerminalItDoesntHoldSaysWhyItCantRestart, aSessionWithNothingRunningHasNoRestart; ActionsTest.restartPostsAndTakesTheSessionBack, restartOnAnOlderServerSaysItNeedsUpdating, restartOfASessionGoneSaysSoNotUpdate; claude-agents.test.ts › "Restart: …" (both: a stand-in claude); HomeCardTest.theMenuOffersWhatTheMacCanDo, deleteSaysWhatItWillDoForEveryKindOfSession, theMenuOffersRenameStopAndDeleteForCodex, readsTheJob; HomeMoreTest.theMenuRenamesWithItsSheet, theMenusStopGoesAtOnce, theMenusDeleteAsksFirst, anIdleTerminalSessionHasDeleteAndSaysItsTerminalCloses, aRunningJobsDeleteSaysItStops, aSessionWithNothingRunningSaysWhereItGoes, aTerminalItDoesntHoldSaysWhyItCantBeDeleted, aSessionAtWorkThatIsNoJobHasNoDelete, anOutdatedJobsMenuRestartsItAtOnceWhenItsAtRest; ActionsTest.renameTrimsTheName, stopRespawnAndDelete, deleteOnAnOlderServerSaysItNeedsUpdating, deleteRefusedSaysTheServersOwnWords; claude-agents.test.ts › "Delete: …" (all 5); contract.test.ts › "POST …/rename on a claude agents job", "POST …/stop, …/respawn and /v1/jobs/respawn"; codex.test.ts › "the session menu renames…", "…deletes a Codex session…" | **covered** |
| H5a | Long-press menu: MCP servers, for a Claude session with a folder: that folder's project and local servers with their transport, command's base name or host, and a count of the user's; empty and older-server states; not on the chat screen | agent-settings.test.ts ("a session's folder", parents, local scope and worktrees, what can't be said, links and trouble, the route's raw body and auth); contract.test.ts and ContractTest, fixture rest-session-mcp; SettingsCallsTest (the two session MCP tests); SessionMcpScreenTest (all) | **covered** |
| H6 | Card swipes: Smart, and Settings' picks for left and right | CardSwipeChoiceTest (all); HomeScreenTest.smartSwipeOpensANeedsInputCard, smartSwipeStopsACompletedJobThatRuns, smartSwipeDeletesAStoppedJobAfterAsking, workingCardHasNoSmartSwipe, rightSwipeFollowsTheSetting, aPickThatDoesNotFitLeavesThatWayWithoutASwipe | **covered** |
| H7 | Search Home's sessions: local matching plus the server's full text | HomeSearchTest (all); HomeScreenTest.searchOpensFiltersAndCloses | **covered** |
| H8 | A job that died: "Stopped with an error" on its card, a banner or notification | JobDiedTest (all); SocketEventsTest.aDeadJobIsSaidOnce; fixture ws-job-died.json; job-deaths.test.ts (all); claude-agents.test.ts › "a job that crashes mid-turn…" | **covered** |
| H9 | "Restart all" for jobs on an older Claude Code | OutdatedJobsTest (all); RestartOfferTest (all); HomeMoreTest.theOutdatedJobsNoticeRestartsThemAllAfterAskingAndSaysWhichRefused; ActionsTest.respawnOutdatedJobsUsesTheSlowRoute; fixture rest-jobs-respawn.json; claude-agents.test.ts › "the respawn-all route…" | **covered** |
| H10 | Cards above the sessions (account, providers, prompts for gone sessions, automation questions), dismiss and undo | AttentionTest (all); HomeGroupsTest.theAccountCardFollowsWhatsOverIt; ProvidersTest (all); HomeMoreTest.aPromptWhoseSessionEndedStartsANewSessionOrGoes, aProvidersTroubleIsACardThatGoesWithASwipeAndComesBackWithUndo; SessionRoutesTest.aWaitingPromptWhoseSessionIsGoneStartsANewSessionInItsFolder | **partial**: the account card (signed out, a limit, no plan) and an automation's question card (the Automations area's) aren't drawn in a screen test |
| H11 | Usage cards (Claude, Codex, one per computer) | UsageCardsTest (all); AllUsageTest (all); UsageWindowTest (all); UsageMeterTest (all); FiveHourReportedTest (all); ModelWeeksTest (all); HomeMoreTest.whereOnlyCodexIsInUseHomesUsageCardIsCodexs, eachModelsOwnWeekHasItsMeter; the `home_groups` baseline | **covered** |
| H12 | The sky: the sun as usage, planets as projects, Codex's star, companions | SkyPlanTest, SkyLayoutTest, SkyPhysicsTest, SkySplitTest, AgentLeadTest, PaceTest, CountdownTest (all); SkyTest.theSunThePlanetsAndTheFarSky (the far sky, the sun's readout, the planets and the belt drawn, baseline `sky`, with the sky's seed and the wall clock pinned) | **covered** (Home hides the system since 2026-10-30; the drawing is guarded on its own. Codex's star and the companion aren't in the baseline: their placement is SkySplitTest's) |
| H13 | Home filters: the button and sheet (Active, states, agent, project, server), the chip with its hidden needs-you count, Clear, the empty result, kept on the phone, search within it, the top bar at a large font | HomeFilterTest (all); HomeFilterScreenTest (all) | **partial**: the Agent row (both agents in use) isn't drawn in a screen test; the sky's project filter with Home's together is only reasoned, not tested |
| H14 | Home sort: the button and sheet (Name by default, Recent, Project, Server in All servers), within the groups, across every computer's cards, in search results, kept on the phone, the lit button; the top bar at both fonts | HomeSortTest (all); HomeGroupsTest.completedByNameByDefault, completedNewestFirstByRecent; AllHomeTest.byServerGroupsEachComputersCardsTogetherThenByName; HomeSortScreenTest (all); HomeFilterScreenTest.theTopBarFitsAtTheUsualFont, theTopBarFitsAtTheUsualFontWithAllServers, theTopBarFitsAtALargeFontWithAllServers, aWiderPhoneKeepsTheWordsWithAllServers, aWiderPhoneSaysNewWithOneServer | **covered** |

## 4. Session chat

| # | Capability | Tests | Status |
|---|---|---|---|
| C1 | Transcript rows: user, reply, tool, terminal output, diff, command, thinking, notices | SessionScreenTest.messagesRenderEachAsItsKind; ToolRunsTest (all); AsidesTest (all); AnswersTest.thinkingIsOngoingOnlyWhileItIsTheNewestThingInARunningSession; normalize.test.ts (all); contract.test.ts › "GET …/events: the transcript as the chat draws it"; fixture rest-session-events.json | **covered** |
| C2 | Markdown, tables, Mermaid diagrams, links that ask first | MermaidTest (all); LinkedTextTest (all); LinksTest (all); ChatMoreTest.aReplysMarkdownIsDrawnNotShown (a heading, a table and a code block drawn, not their marks) | **partial**: Mermaid's WebView drawing never runs (Robolectric has no WebView engine) |
| C3 | Live updates: events grow, `event.removed`, a resync reloads the open chat, a failed load can be retried | SocketEventsTest.eventsGrowTheOpenTranscriptAndEventRemovedTakesOneBack, aResyncSnapshotReloadsTheOpenSession, aHistoryFailureIsSaidAndCanBeRetried; EventRemovedTest (all); fixtures ws-event*.json | **covered** |
| C4 | Load earlier events | SessionRoutesTest.aLongHistoryOpensOnItsLatestAndLoadEarlierPutsTheRestInFront, aHistoryTheMacRewroteMeanwhileIsTakenWholeWithWhatCameSince, aShortHistoryHasNothingEarlierAndAFailedLoadEarlierIsSaid (the limit fixture); ChatMoreTest.aLongSessionOffersItsStartAndAsksForIt, aSessionThatHasItAllOffersNothingEarlier; fixture rest-session-events-limit.json | **covered** |
| C5 | Status bar: what it's doing, the workflow's tally, the red bar, Stop; opened, half of the room above the prompt box, which it never squeezes; closed again once you leave the chat | StatusModelTest (all); StatusPanelResetTest (all); SessionScreenTest.anOpenPanelTakesAboutHalfOfTheRoomAndScrollsToItsLastRow, theKeyboardLeavesTheBoxAndSomeOfTheChat, aShortPhoneWithTheKeyboardKeepsTheBox, aLargeFontKeepsTheBox, landscapeWithTheKeyboardKeepsTheBox, aGrownBoxWinsOverThePanel, aGrownBoxOnAShortPhoneStaysOnScreen, aGrownBoxInLandscapeStaysOnScreen | **covered** |
| C6 | Top bar: the owner's parts and order, 5H only when reported, a tap opens the sheet | TopBarTest (all); SessionScreenTest.topBarShowsTheDefaultItems, topBarHidesFiveHoursWhenNotReported, topBarFollowsTheChosenItemsAndOrder, topBarOpensTheSessionSheet | **covered** |
| C7 | Usage strip: context and cost, Compact at 85% | ChatTest.readsTheUsage, theStripNamesTheModelTheSessionRunsNow, money; RowFitTest (all); ChatMoreTest.aNearlyFullContextOffersCompactInOneTap, aContextWithRoomHasNoCompact; ChatRepoTest.theUsageStripReadsTheSessionsUsage; fixture rest-session-usage.json; chat.test.ts › "knows the models…", "prices a call…", "adds up a session and its agents…" | **covered** |
| C8 | To-do list | ChatTest.readsTheTodoList; ChatMoreTest.theToDoListShowsWhatsDoneWhatsUnderWayAndWhatsLeft; ChatRepoTest.theToDoListReadsClaudesList; fixture rest-session-todos.json; chat.test.ts › "the to-do list TodoWrite last wrote", "the task list TaskCreate and TaskUpdate keep" | **covered** |
| C9 | Rewind from a message: the copy replaces the session on every screen, the old transcript hidden on disk; asks first if it is at work; a stop that fails keeps both; whatever names the old id follows | ChatMoreTest.rewindAsksFirstSayingWhatGoesAndSaysWhenItCant; RewindSheetTest (all); ChatRepoTest.rewindGoesOnInTheNewSessionWithTheMessageForThePromptBox, aRewindSaysWhenTheEarlierSessionStaysInTheServersWordsOrThatTheServerIsOlder, whatTheyCantDoIsTheServersWordsOrSaysTheServerIsOlder; SocketEventsTest.aRewindsCopyReplacesTheSessionAndWhatThePhoneKeptGoesOnThere, anOlderServersPlainRemovalReplacesNothing, aPhoneThatWasAwayHearsOfTheRewindInTheSnapshotWithoutHidingLaterReplies; UnreadTest.aRewindMovesTheMarkOnceAndOnlyIfThereWasOne; OfflineQueueTest.whatWaitsForASessionARewindReplacedWaitsForTheNewOne; fixtures rest-session-rewind.json, ws-session-replaced.json; contract.test.ts › "POST …/rewind…" (first_message, the old id followed); chat.test.ts › "rewinds to just before a message…" and "Rewind: the copy replaces the original" (terminal, job, SDK, stop failures, twice, what carries over); resume.test.ts › "a session a rewind replaced…"; sdk-missing.test.ts › "what runs through it answers sdk_missing…" | **covered** |
| C10 | Message menu: copy, quote, read aloud | ChatTest.quotesLineByLine, readsARepliesWordsNotItsMarkdown; ChatMoreTest.aMessagesMenuCopiesQuotesAndSavesItAsAQuickPrompt | **covered** |
| C11 | Live terminal: one-minute ticket, xterm in a WebView, the key bar | TermTicketTest (all); TermFramesTest (all); KeyBarTest (all); term-scroll.test.ts (all); SessionRoutesTest.theTerminalsTicketIsOnTheRouteInUse; fixture rest-session-terminal.json; contract.test.ts › "POST …/terminal and …/interrupt…" (and 409 no_terminal) | **partial**: `TerminalScreen` never runs (its xterm is a WebView, which Robolectric can't run) |
| C12 | Never a session over a session: Back goes Home | NavTest (all) | **covered** |
| C13 | Limits: the PAUSED notice, Continue, carry on by itself | ProvidersTest.aBlockStaysWhileItCarriesOnByItselfOrWaitsForContinue; SessionsTest.readsWhyItEnded; SessionRoutesTest.afterALimitContinueSaysWhyAndCarryingOnByItselfCanBeTurnedOff; ChatMoreTest.carryingOnByItselfCanBeTurnedOffOrDoneNow, carryingOnByItselfTurnedOffCanBeTurnedBackOn, aSessionStoppedWhileAwayContinues; fixtures ws-session-limited.json, ws-usage-limited.json, rest-session-auto-continue.json; contract.test.ts › "POST …/auto-continue and …/provider…" (bad_on, not_limited); resume.test.ts (all); account.test.ts (limit tests) | **covered** |
| C14 | Folded runs: 3+ built-in calls fold, MCP calls (alone or with lookups) from 1, named by server ("home-assistant ×3"); the live run folds too ("Using", the planet, its latest call under the line); diffs and failed calls stand between folds; a tap opens one and it stays open; an MCP call's row and summary say what it was called with, secrets never | ToolRunsTest (all); McpRowTest (all); SessionScreenTest.aLiveMcpRunFoldsWithItsLatestCallUnderIt, aFinishedMcpRunReadsAsItsServer, aTapOpensTheFoldToEachCall; mcp-summary.test.ts (all) | **covered** |
| C15 | The terminal's add-ons: emoji and wide characters by Claude Code's widths, a tap on an address opens it, finding words in its history (the page's `hn.find`; nothing on screen starts it yet, docs/LATER.md L99) | term-widths.test.ts (all); TermLinkTest (all); LinksTest (all); `scripts/term-page-check.mjs` by hand, no suite | **partial**: the page's checks need a Chromium, so no suite runs them, and nothing has run on a phone's WebView |

## 5. Session sheet

| # | Capability | Tests | Status |
|---|---|---|---|
| S1 | Opens on Overview (or Permissions from the blocked mark); tabs; summary line; Close | screens/SessionSheetTest.opensOnOverviewAndEachTabSwitches, closeDismisses; session/SessionSheetTest.theSheetOpensOnPermissionsFromTheBlockedMarkElseOnOverview, theTabsAreInTheOwnersOrder, the three tab-width tests, the four summary tests | **covered** |
| S2 | Model picker, paid models confirmed first | ActionsTest.setModelAndTheChipFollowsAtOnce, theDefaultModelIsSentAsNull; ErrorsTest.aPaidModelAsksFirst; ModelCatalogTest (all); ModelNamesTest (all); session/SessionSheetTest.theModelIdFollowsASwitchBeforeTheUsageDoes; SheetMoreTest.theSheetsModelOpensThePickerAndSwitchesTheModel; fixture rest-session-model.json; model.test.ts (all) | **covered** |
| S3 | Effort | ActionsTest.setEffort; EffortTest (all); fixture rest-session-effort.json; mode.test.ts › "a session the daemon runs takes its effort…", "an ended session's effort…"; claude-agents.test.ts › "the effort route…" | **covered** |
| S4 | Mode picker, with the think-twice card for Auto and Bypass | screens/SessionSheetTest.modePickerChangesTheMode; SheetMoreTest.autoAsksToThinkTwiceFirst, bypassAsksTwiceThenForTheOwner (no screen lock, a cancelled check, the owner's check), planNeedsNoSecondThought; ActionsTest.setModeSaysWhetherItAppliesNextTurn; session/SessionSheetTest.aClaudeAgentsJobsModeChangesWithoutATerminalUntilItEnds; OwnerCheckManifestTest; fixture rest-session-mode.json; mode.test.ts (all); claude-agents.test.ts › "the mode route…" | **covered** (the system's own BiometricPrompt is stood in for: OwnerCheck.prompt) |
| S5 | Blocked calls: list, Allow & retry, Dismiss | BlockedTest (all); session/SessionSheetTest.theSummaryCountsAgentsAndBlockedCallsOnlyWhenThereAreAny; SessionRoutesTest.blockedCallsAreListedRetriedAndDismissed, aRetryTheSessionCantTakeSaysWhy; SheetMoreTest.blockedCallsAreAllowedAndRetriedOrDismissedFromTheSheet; fixtures rest-blocked.json, rest-blocked-retry.json, rest-blocked-dismiss.json; blocked.test.ts (all); http.test.ts › "a session's Blocked tab…" | **covered** |
| S6 | Rules in effect | RulesModelTest (all); screens/SessionSheetTest.rulesInEffectFoldOpen; ActionsTest.permissionRulesAreTheSessionsOwn; fixture rest-permissions.json; permission-rules.test.ts (all) | **covered** |
| S7 | Why it asked | WhyAskedTest (all); screens/SessionSheetTest.whyItAskedForTheTerminalDialog; why.test.ts (all) | **covered** |
| S8 | Allow these N | AllowSeveralTest (all); screens/SessionSheetTest.allowTheseNAllowsEveryWaitingAskAfterAsking; ActionsTest.allowSeveralAtOnce, allowSeveralThatFailsPutsTheAsksBack; fixture rest-allow.json; always.test.ts › "allow these N…" | **covered** |
| S9 | Agents tab: agents at work, each workflow's done of started | session/SessionSheetTest (the seven agent and workflow tests); TopBarTest.theSheetOpensOnAgentsFromTheCount | **covered** |
| S10 | Commands tab: /btw, recent and pinned, helpers for /effort, /model, /rename and /compact | CommandsTabTest (all); screens/SessionSheetTest.opensOnOverviewAndEachTabSwitches (the tab's baseline); ActionsTest.aSideQuestionGoesToTheBtwRoute, theSideQuestionsAnswerIsRead; SessionRoutesTest.theSlashListIsTheProjectsOwnAndKeptAFewMinutes; fixtures rest-btw.json, rest-btw-ask.json, rest-commands.json (a stand-in Claude Code answering the SDK's initialize); aside.test.ts (all); claude-agents.test.ts › "/btw…" | **covered** |
| S11 | Session tab: TIME, GIT, the line to What changed | SessionInfoTest (all); HistoryTabTest.theSessionTabsTimeSaysHowLongItHasRunAndWhenItLastDidSomething; git-line.test.ts (all); contract.test.ts › "GET …/changes (with the git line)…" | **covered** (no baseline on purpose: its Started is a wall-clock time) |
| S12 | Session tab actions: Rename, Stop, Delete, Restart, the version note, another provider | RestartOfferTest (all); HomeCardTest menu tests; HomeMoreTest menu tests; ActionsTest.stopRespawnAndDelete; SessionRoutesTest.aSessionMovesToAnotherProviderHereOrInAFork; fixtures rest-session-rename.json, rest-session-stop.json, rest-session-respawn.json, rest-session-provider.json; http.test.ts › "a session continues on another provider…" | **covered** |

## 6. Approvals

| # | Capability | Tests | Status |
|---|---|---|---|
| A1 | Allow and Deny; shown at once; answered elsewhere; a failed answer comes back | ActionsTest.allow, deny, theAnswerShowsAtOnceBeforeTheServerConfirms; SessionScreenTest.approvalCardAllowAnswersIt, approvalCardDenyAnswersIt; ErrorsTest.anApprovalAnsweredElsewhereSaysSoAndCountsAsDone, anAnswerThatFailsComesBackAndSaysSo; SocketEventsTest.aNewApprovalIsPendingAndAnnouncedThenResolvedTakesItAway, theAnswerShownAtOnceIsReplacedByTheServersResolution; ApprovalChangeTest (all); fixtures ws-approval.json, ws-approval-resolved.json | **covered** |
| A2 | Always sheet: rule, widenings, This session / This project / Everywhere | AlwaysRulesTest (all); SessionScreenTest.alwaysOpensTheAlwaysSheetWithTheSuggestedRule; SheetMoreTest.anAlwaysRuleIsSavedOnlyAfterTheOwnersCheck; ActionsTest.always, alwaysWithAScopeAndRules, forTheSession; always.test.ts (all) | **covered** (the system's own BiometricPrompt is stood in for: OwnerCheck.prompt) |
| A3 | Plans: approve, keep planning | ActionsTest.approveAPlan, keepPlanningIsADenyWithAMessage; ChatMoreTest.aPlanIsApproved, aPlanCanBeKeptPlanning | **covered** |
| A4 | Questions one at a time: steps, multi-select, Other | QuestionStepsTest (all); ActionsTest.answersToQuestions, menuStepsMoveOrJump; TermMenuStepsTest (all); ChatMoreTest.aQuestionIsAnsweredWithTheOptionPicked; question-form.test.ts (all) | **covered** |
| A5 | Claude Code's own dialog in the terminal as a card | TerminalAskTest (all); SessionScreenTest.terminalAskCardAllowPressesYes, terminalAskCardOpensTheTerminal; ActionsTest.answerAMenuChoiceAndCancelOne; SocketEventsTest.waitingInTheTerminalIsSaidWithItsQuestion; permission-dialog.test.ts (all); claude-agents.test.ts dialog tests | **covered** |
| A6 | Waiting asks stack under the chat; a countdown only where Hypernova will say no | AskStackTest (all); AsksLastTest (all) | **covered** |
| A7 | Never auto-deny: an unanswered ask goes back to the terminal with one notice | blocked.test.ts › "nobody answered for 10 minutes…", "asks handed back to the terminal…", "a session the daemon runs through the SDK…"; claude-agents.test.ts › "an ask handed back on a claude agents job…"; EventRemovedTest (all) | **covered** |
| A8 | An agent's ask waits 10 s before it notifies | SocketEventsTest.anAgentsApprovalWaitsTenSecondsBeforeItNotifiesAndSaysNothingIfSettledMeanwhile | **covered** |
| A9 | Codex approvals | codex.test.ts › "an approval Codex asks shows as a card…", "an approval a terminal answered first…"; codex-units.test.ts › "approval cards and Codex's answers…"; AskStackTest.onlyAnAskTheMacWillDenyCountsDown; SessionRoutesTest.aCodexApprovalIsAnsweredFromThePhoneAndGoesWhenResolved, aCodexApprovalAnsweredElsewhereFirstCountsAsDone; fixtures rest-approval-answer.json, rest-approval-already.json | **covered** |

## 7. Prompts and queue

| # | Capability | Tests | Status |
|---|---|---|---|
| Q1 | Send a prompt: "sending" until the server's copy, one send per `clientId` | ActionsTest.aSentPromptShowsAsSendingUntilTheServersCopyArrives; SentTextTest (all); SessionScreenTest.enterSendsWhenTheSettingIsSend; dedup.test.ts (all) | **covered** |
| Q2 | Enter key: Send or New line | EnterKeyTest (all); SessionScreenTest.enterSendsWhenTheSettingIsSend, enterIsANewLineWhenTheSettingIsNewLine; SettingsTest.enterKeyNewLineAndBack | **covered** |
| Q3 | Held prompts: behind an open question, edit, take back | HeldTest (all); ActionsTest.aPromptAsksToBeHeldAndCarriesItsClientId, aPromptTheServerHeldIsShownByTheSessionsHeldNotACopy, anImmediateCommandTheServerTypesAtOnceIsNotHeld, editAndRemoveAHeldPrompt; SocketEventsTest.heldPromptsShowAsHeldAndBecomeSentCopiesWhenTheServerTypesThemIn, aHeldCopyNobodyClaimsGoesAfterFifteenSeconds; fixture rest-prompt-held.json; held.test.ts, held-route.test.ts (all) | **covered** |
| Q4 | Offline queue on the phone | OfflineQueueTest (all); ActionsTest.whileTheServerIsAwayAPromptWaitsOnThePhoneAndGoesWhenItIsBack, aPromptNothingAnsweredWaitsUnderTheSameNameAndGoesAgain; ErrorsTest.aWaitingPromptWhoseSessionIsGoneWaitsForANewSession; SessionRoutesTest.aWaitingPromptWhoseSessionIsGoneStartsANewSessionInItsFolder; ChatMoreTest.aWaitingPromptsMenuEditsOrRemovesIt; HomeMoreTest.aPromptWhoseSessionEndedStartsANewSessionOrGoes | **covered** |
| Q5 | Claude Code's own queue (`session.queue`, popAll) | normalize.test.ts queue tests; ContractTest (the snapshot's queue); SessionRoutesTest.promptsInClaudeCodesQueueShowAsQueuedAndGoWhenItTakesThem; ChatMoreTest.aQueuedPromptSaysItWaitsForTheStepToFinish | **covered** |
| Q6 | Text already typed in the terminal (409 `typing` → Replace) and the other refusals | ErrorsTest.unsentTextInTheTerminalIsOfferedToReplace, aConversationThatWentOnElsewhereSaysWhere, anyOther409IsTheServersOwnWords, aRefusalWithoutWordsHasTheAppsOwn; ActionsTest.replaceSendsWithoutHold | **covered** |
| Q7 | Attachments: paperclip, camera, pasted images (any clip, in every box with a paperclip, a failure said) | PastedTest (all); PasteBoxTest (all 9: Compose's own paste through the New session box, on the receiver the session's box shares; a keyboard's content and a real provider only on a phone); PickedTest (all: the 30 MB limit, an unreadable document); SessionRoutesTest.anUploadStreamsTheFileUnderItsName, anUploadTheServerRefusesSaysWhy, aPromptCarriesItsFilesPathsAndAGoneFileIsSaid, aWaitingPromptWhoseFilesAreGoneGoesWithoutThem; fixtures rest-attachment.json, rest-prompt.json, rest-prompt-attachment-gone.json; attachments.test.ts (all); contract.test.ts › "POST /v1/attachments, then a prompt…" (the @-mention typed); normalize.test.ts › "a command typed after pasting a picture…" | **covered** |
| Q8 | Stop the turn (Esc) | ActionsTest.interruptPosts; SessionRoutesTest.stopThatTheServerCantDoIsSaid; contract.test.ts › "POST …/terminal and …/interrupt…" (Esc reaches the job's terminal); fixture rest-session-interrupt.json; codex.test.ts › "Stop interrupts the turn…" | **covered** |
| Q9 | Quick prompts | ChatMoreTest.aQuickPromptIsSentInOneTap, aQuickPromptIsEditedWithALongPressAndTheListIsKept, aMessagesMenuCopiesQuotesAndSavesItAsAQuickPrompt (Save as quick prompt) | **covered** (note: the chips aren't on the chat since 8a36f54, "no quick prompts"; Save as quick prompt still fills their list) |

## 8. New session

| # | Capability | Tests | Status |
|---|---|---|---|
| N1 | The sheet: folder, mode, effort, model, Start | NewSessionSheetTest (all); NewSessionMemoryTest (all); ProjectPickTest (all); SessionRoutesTest.startSendsTheSheetsChoicesAndTheNewSessionIsThereAtOnce, aNewProjectGoesByItsNameAndTheProviderAndFilesGoWithIt, aCodexSessionNamesItsAgentAndNoProvider, theRefusalsAreTheServersOwnWordsAndStartNothing (bad_project, mac_privacy, sdk_missing, codex_unavailable); fixtures rest-sessions-start.json, rest-sessions-bad-project.json; contract.test.ts › "POST /v1/sessions…" (no_prompt, no_project, bad_agent, unknown_effort); files.test.ts › "a session isn't started in a folder Hypernova can't open…", "…in a folder that isn't there…"; claude-agents.test.ts › "createAgentsSession…", "a new session with an effort Claude Code doesn't have…" | **covered** |
| N2 | Folder browser (GET /v1/folders) | FolderPickTest (all); NewSessionSheetTest.eachChipOpensItsPickerAndStartSendsThePicks; SessionRoutesTest.theFolderBrowserAsksForAFolderAndItsHiddenOnes; fixture rest-folders.json; folders.test.ts (all) | **covered** |
| N3 | New project folder (POST /v1/projects) | ProjectPickTest.newProjectNamesAreFolderNames; SessionRoutesTest.aNewProjectGoesByItsNameAndTheProviderAndFilesGoWithIt (the sheet's `newProject`); contract.test.ts › "POST /v1/sessions…" (a new project's folder, a git repository), "GET and POST /v1/projects…"; fixtures rest-projects.json, rest-project-new.json | **covered** |
| N4 | "New sessions start in" setting (never Bypass) | NewSessionMemoryTest.newSessionsStartInTheChosenModeNeverBypass | **covered** |
| N5 | Providers' model lists (GET /v1/models) | ModelCatalogTest (all); ActionsTest.anOlderServerWithoutModelsListsNone; fixture rest-models.json; models.test.ts (all) | **covered** |
| N6 | A Codex session: its modes, models and effort | CodexTest.theNewSessionSheetKeepsCodexModes; NewSessionMemoryTest.theAgentIsTheLastOneUsedElseTheLeadingOne; SessionRoutesTest.codexsModelsAreAskedForOnceInAWhile, codexsModelsWhileItIsOffSayTheServersWords, aCodexSessionNamesItsAgentAndNoProvider; codex.test.ts › "a new Codex session starts its thread…"; codex-only.test.ts › "without Claude Code…" | **partial**: GET /v1/codex/models has no fixture (the contract run has Codex off; a list needs the fake app-server there, the Codex area's) |
| N7 | Share into Hypernova; launcher shortcuts | ShareTest (all: the shared text and files, the files copied while lent, the share screen's destinations drawn, the hand-over to the new-session sheet, the static New session and the three recent projects) | **covered** |

## 9. History and search

| # | Capability | Tests | Status |
|---|---|---|---|
| Y1 | History: ended sessions by day, an automation's runs folded | HistorySearchTest.daysReadAsTodayYesterdayOrTheDate; SummaryTest.historyFoldsAnAutomationsSessions; ResetLinkTest (all); HistoryTabTest.endedSessionsByDayAndAnAutomationsRunsFolded, nothingEndedYetSaysSo; reboot.test.ts › "History reaches back 30 days…" | **covered** |
| Y2 | Search: local matching plus the server's full text; partial results; an older server | HistorySearchTest (all); ActionsTest.historySearchAsksTheServerAndAnOlderServerMeansFilterOnThePhone; fixture rest-history-search.json; search.test.ts (all) | **covered** |
| Y3 | All servers History | AllHistoryTest (all) | **covered** |
| Y4 | Go on with an ended session | SessionRoutesTest.goingOnWithASessionPostsContinueAndHidesItsContinueAtOnce; ChatMoreTest.aSessionStoppedWhileAwayContinues; fixture rest-session-continue.json; reboot.test.ts › "a prompt resumes a cut-short conversation…"; claude-agents.test.ts › "promptSession: a finished job…" | **covered** |
| Y5 | Sessions a restart cut short | SessionsTest (all); reboot.test.ts (all) | **covered** |

## 10. Automations

| # | Capability | Tests | Status |
|---|---|---|---|
| U1 | The tab: list, switch on and off, pause all, delete | AutomationsLiveTest (all 13); AutomationsScreenTest (the tab tests, the editor's deleteAsksFirstThenGoesBack); SummaryTest.readsTheSnapshotAndTheSocket; EditorRulesTest.aRunsMessageUpdatesTheBook, theRunsCountsKeepOneWindowAsRunsCome; fixtures rest-automation.json, rest-automation-enabled.json, rest-automations-paused.json, rest-automation-runs.json, rest-automations-check.json, ws-automation.json, ws-automation-removed.json, ws-automations.json, ws-run.json, ws-note.json; automations.test.ts › "routes: list, create, replace, enable…"; http.test.ts › "automations: the routes, their errors…" | **covered** |
| U2 | Editor: triggers, templates, actions, steps, paid providers | EditorRulesTest, PaidTest, SummaryTest, WorkflowRulesTest, WorkflowWordsTest, WorkflowsTest (all); schedule.test.ts, workflows.test.ts, workflows-contract.test.ts (all) | **partial**: the editor screens are untested; the phone's POST /v1/automations/check never runs |
| U3 | Runs: following, gates, the account gate, results, notes | automations.test.ts (all); SummaryTest.runLines, run; WorkflowWordsTest.aRunsStepsInWords, aRunsOutputsAndItsNewReasons | **covered** |
| U4 | Run now, Stop, Retry (from a step) | AutomationsLiveTest.runNowShowsItsRunAtOnceAndARefusalIsSaidInTheAppsWords, stopAndRetryFromAStep; AutomationsScreenTest.aSavedWorkflowShowsItsStepsRunsNowAndSavesOnlyWhenChanged; EditorRulesTest.retryIsForRunsThatEndedBadly; WorkflowWordsTest.whichStepRetryStartsFrom; fixtures rest-automation-run.json, rest-automation-stop.json, rest-automation-retry.json; automations.test.ts › "runs: Retry runs an ended one again…"; workflows.test.ts › "Retry from a step…"; http.test.ts › "workflows: an ask answered over HTTP…" | **covered** |
| U5 | Ask steps answered from the phone or a notification | AutomationsLiveTest.anAnswerFromTheAppEndsTheAskAndOneAnsweredElsewhereFirstPutsTheRunAsItIs, anAnswerTooLateFailsButTheCardShowsTheRunTheMacHas, offScreenANoteIsANotificationAndAnAskOneHasItsAnswersAsButtons, anAnswerFromTheNotificationGoesToTheMacAndTheNotificationGoes (`NotifyActions`, ACTION_ANSWER); AutomationsScreenTest.anAskIsAnsweredFromItsCard, anAskSomeoneElseAnsweredSaysWhatWasTaken; WorkflowsTest.answersFlagsAndNotes; fixtures rest-automation-answer.json, rest-automation-not-asking.json, ws-note.json; workflows.test.ts › "start, ask, answer…", "an ask nobody answers…" | **covered** |
| U6 | Webhook trigger and outputs | workflows.test.ts (webhook, firing and output tests); http.test.ts › "workflows: a webhook trigger fired…", "the local fire route…", "webhook outputs…" | **partial**: showing and renewing the token on the phone is untested |
| U7 | Shell steps | workflows.test.ts (the shell tests) | **covered** |

## 11. Codex

| # | Capability | Tests | Status |
|---|---|---|---|
| X1 | Turn Codex on or off; its card in Settings | CodexActionsTest.turningCodexOnPostsItAndTheHubHasWhatTheMacSays, aCodexTooOldIsRefusedInTheMacsWordsAndNothingChanges; AgentCardsTest (all); fixtures ws-codex.json, rest-codex.json; codex-only.test.ts › "the phone turns Codex off and on" | **covered** |
| X2 | Codex's state, account and windows; its star and usage card | CodexTest.readsCodexWithItsAccountAndWindows, noCodexOnAMacWithoutIt; AgentLeadTest (all); UsageCardsTest (all); SkySplitTest (all); codex.test.ts › "the snapshot says what Codex is…"; codex-units.test.ts › "Codex's windows go by their length…" | **covered** |
| X3 | A Codex session's chat: turns, queue, Stop | codex.test.ts (all); codex-units.test.ts › "items as the chat's events…", "a thread's history reads as its turns…" | **partial**: no rig test has a Codex session |
| X4 | Codex model and effort switch | CodexActionsTest.theModelsAreAskedOnceAWhileAndAgainWhenCodexTurnsOn, aCodexSessionsModelAndEffortGoTogetherAndItsChipFollowsAtOnce; AgentSwitchSheetTest.aCodexSessionsModelPickerSwitchesItsModelAndEffort; CodexTest.readsCodexModels; fixtures rest-codex-models.json, rest-codex-session-model.json; codex.test.ts › "mode and model take effect from the next turn…", "the models' default…" | **covered** |
| X5 | Switch agents: preview, edit, switch | CodexActionsTest.aSwitchsPreviewAsksForTheAgentAndHowMuchToCarry, aSwitchSendsTheTextAndChoicesAndOpensTheNewSession, aSwitchTurnedDownSaysWhyWhereItWentAndTheMostItTakes; AgentSwitchSheetTest (all 7); CodexTest.readsASwitchPreview, readsACodexSessionAndItsSwitch; fixtures rest-switch-preview.json, rest-switch.json, rest-switch-too-large.json, rest-switch-switched.json; codex.test.ts › "a switch carries the approved text…", "a busy session can't switch…", "two switches at once…"; codex-units.test.ts › "a switch's text reaches the other agent…" | **covered** |
| X6 | Rename, Stop and Delete a Codex session | HomeCardTest.theMenuOffersRenameStopAndDeleteForCodex; CardSwipeChoiceTest.deleteForJobsAndCodexOnly; codex.test.ts (the session-menu tests) | **covered** |
| X7 | Codex in Statistics | StatsTest (the Codex tests); codex-only.test.ts › "Statistics count Codex's turns…"; codex-units.test.ts › "Codex's tokens in Statistics…" | **covered** |

## 12. Agents boards

| # | Capability | Tests | Status |
|---|---|---|---|
| G1 | Agent and workflow cards, live | AgentsLiveTest.theBoardComesWithTheSessionAndTheSocketKeepsItCurrent, anOlderMacWithoutTheRouteLeavesTheBoardEmpty; AgentCardsChatTest (the chat's cards, drawn); fixtures ws-agent.json, ws-workflow.json, rest-agents.json; contract.test.ts › "{t:"usage"}, {t:"agent"} and {t:"workflow"}", "GET …/agents and an agent's events…"; agents.test.ts (all) | **covered** |
| G2 | The status bar's agents and the workflow's tally | StatusModelTest (the workflow and agent tests) | **covered** |
| G3 | An agent's own screen: its transcript, "Starting…", following by cursor | AgentsLiveTest (the `agentEvents` tests: cursor, merge, a whole read at the end, `starting`, an older Mac); AgentScreenTest (all 5: polling every 1.5 s on the compose clock, once more at the end, Starting, Try again); AgentEmptyTest (all); fixtures rest-agent-events.json, rest-agent-events-starting.json; agents.test.ts › "a phone following an agent gets only what changed since its cursor…", "an agent just started…" | **covered** |
| G4 | An agent waiting on its background command stays at work, saying so; its finish, the cap and a restart mid-wait | agents.test.ts › "a background command and then SubagentStop…", "Claude Code's word that it waits…", "the wake-up carries on…", "a command that ended, was killed…", "a report handed back…", "the cap…", "a long Monitor…", "the main turn's Stop leaves…", "a daemon restart mid-wait…"; StatusModelTest.anAgentsWaitingLineIsSaidAsItIs; screens/SessionSheetTest.anAgentWaitingOnItsBackgroundCommandIsAtWorkAndSaysSo | **covered** |

## 13. Read aloud and voice

| # | Capability | Tests | Status |
|---|---|---|---|
| V1 | Dictation: press and hold, hands-free, Send while dictating | MicGestureTest (all); VoiceTest (all) | **partial**: Android's `SpeechRecognizer` (`PhoneDictation`) never runs; the dictation capsule isn't drawn |
| V2 | Which recognizer: the phone's, or on-device only | DictationChoiceTest (all); SettingsTest.voiceEngineOnDevice | **covered** |
| V3 | Read aloud: the server's voice streamed as PCM, or the phone's own text-to-speech (a Linux server) | ReadAloudLiveTest (all 8: the phone's own engine on a Linux server, in pieces, its Stop and its failure; a play streamed into a fake `AudioTrack` with started and ended said to the server, the server's `speech.stop`, the phone's Stop, the fallback to the phone's voice; the setting); SpeakingBarTest (all 3: the bar, the app's bar following `Speaker`, its Stop); SpeechTest (all); ChatTest.readsARepliesWordsNotItsMarkdown; fixtures rest-speech-play.json, rest-speech-settings.json, rest-speech-voices.json, ws-speech.json, ws-speech-stop.json, ws-speech-progress.json; speech.test.ts (all) | **covered** |
| V4 | Read aloud settings: voice, speed, Test | SpeechTest.speedsSnapToTheStepsSettingsOffers; speech.test.ts › "the setting: the Mac's voice only…" | **partial**: the Read aloud section isn't drawn and its Test never runs |
| V5 | Dictation keeps listening through pauses until it's ended: words joined across the recognizer's sessions, segmented sessions from Android 13, a new session otherwise, real errors, the bound on sessions failing at once, the cap, Submit and letting go between sessions | ContinuousDictationTest (all); PhoneDictationTest (all, on Robolectric's stand-in recognizer); DictationChoiceTest.segmented_sessions_are_asked_for_from_android_13_as_long_as_the_cap | **partial**: no real recognizer runs: whether the phone's honours segmented sessions, how seamless a restart is and any beep need a phone (`adb logcat -s Hypernova` says "dictation: Segmented, n segments, n restarts" at each end) |

## 14. Files and changes

| # | Capability | Tests | Status |
|---|---|---|---|
| F1 | Browse files (the projects folder; outside it with the setting on) | FilesLiveTest.aFolderIsAskedForByItsPathAndListedFoldersFirst, aFolderTheMacWontOpenIsAFileFailureWithItsCodeAndSetting; FilesScreensTest (the folder tests); FileProblemTest (all); fixture rest-files.json; files.test.ts (all); secretfiles.test.ts (all) | **covered** |
| F2 | View a file | FilesLiveTest.aFilesBytesComeWithTheirType; FilesScreensTest.aTextFileInMonoWithItsLineNumbers, aFileTheServerWontShowSaysWhyWithTryAgain; FileProblemTest (all); files.test.ts › "a file: its codes…" | **covered** |
| F3 | Download a file: resume, names, share or save, clean-up | FilesLiveTest (the download tests: streamed into the cache, carried on after a drop with Range and If-Range, a refusal, Cancel); FilesScreensTest.aFileWithNoPreviewIsDownloadedThenShared; DownloadsTest (all); files.test.ts › "the content disposition keeps any name…" | **covered** |
| F4 | Previews of a dev server or a file; the in-app web page | WebTitleTest, PageErrorTest, PreviewPortsTest (all); LinksTest.onlyTheWebOpensAndDevServersPreview; LanRouteTest.pagesOpenOnTheActiveRouteAndOutsideByTheBaseUrl; files.test.ts › "previews never serve keys…"; lan.test.ts › "a preview and the checklist asked for over the listener…" | **partial**: the phone's POST /v1/preview and `WebScreen` are untested; no fixture |
| F5 | What changed: the list, a diff, highlighting | FilesLiveTest.whatChangedAsksForTheSessionsOwnThenEverythingAndADiffByItsPath; ChangesScreenTest (all 4); HighlightTest (all); ContractTest (rest-changes.json, rest-changes-diff.json through `ChangesRepo`); SessionInfoTest.theGitPartFromTheChanges; changes.test.ts (all) | **covered** |
| F6 | Browse outside projects setting | AllServersSettingsTest.eachServersBlockActsOnItsOwnServer; FileProblemTest (the "Turn on in Settings" test); fixture ws-files.json; files.test.ts, folders.test.ts | **covered** |

## 15. Settings

| # | Capability | Tests | Status |
|---|---|---|---|
| T1 | Settings' parts, one server or All servers | SettingsTabTest (all 9: the whole tab through the app's own HubViewModel over real hubs and FakeDaemon: one server in order, All servers with its intro line, a block per server then This phone, a server that can't be reached keeping its card alone, nothing paired; baselines `settings_tab_one_server`, `settings_tab_all_servers`, `settings_tab_not_paired`); SettingsTest.thePartsAsTheyStart; SettingsPlanTest (all); AllServersSettingsTest.eachServersBlockActsOnItsOwnServer | **covered** |
| T2 | Providers: add, edit, Check, delete, balance | ProviderFormTest (all); ProvidersTest (all); SettingsCallsTest (the six provider tests: `saveProvider` new and edited, a refusal's code, `checkNewProvider`, `checkProvider`, `deleteProvider`); ProvidersScreenTest (all 7: the list and the balance read as it opens, the editor's Check, Save, a refusal under its field, a key left behind, Delete; baselines `providers_list`, `provider_edit_checked`); fixtures rest-provider-save.json, rest-provider-save-balance.json, rest-provider-check.json, rest-provider-balance.json, rest-provider-edit.json, rest-providers.json, rest-provider-delete.json, rest-provider-refused.json, ws-providers.json; contract.test.ts › "the providers: saved and checked…"; providers-store.test.ts, providers.test.ts, balance.test.ts (all); http.test.ts › "providers: addresses cleaned…" | **covered** |
| T3 | Claude Code's card: versions, plan, Sessions in claude agents | AgentCardsTest (all); ActionsTest.claudeAgentsSettingIsPostedAndApplied; SocketEventsTest.theClaudeSettingFollowsItsMessage; fixtures ws-claude.json, rest-claude.json; claude-agents.test.ts › "the setting from the phone…" | **covered** |
| T4 | Account: check, sign in on the phone or the server, the code, cancel | SettingsCallsTest (the five account tests: `checkAccount`, `signIn` here and on the server, signed in meanwhile (`not_needed`), `sendSignInCode`, `cancelSignIn`, a code with no sign-in running); SignInPanelTest (all 5: each step as the server says it, the page, Paste and Send, a refused code, Try again, Cancel; baseline `signin_waiting_code`); fixtures ws-account.json, ws-account-sign-in.json, rest-account-check.json, rest-account-login.json, rest-account-login-cancel.json, rest-account-login-code.json, rest-account-login-code-refused.json; contract.test.ts › "signing in from the phone…" (through fixtures/fake-claude.sh); account.test.ts (the sign-in tests); http.test.ts › "the account is in the snapshot…" | **covered** (the Home account card around the panel is H10's) |
| T5 | This phone: card swipes, session top bar, typing, display, new sessions, Stay connected | SettingsTest.cardSwipeRightPicksOpen, topBarToggleMoveAndReset, enterKeyNewLineAndBack; SettingsTabTest.thePhonesOwnPartsKeepWhatsPicked (Display's text size, New sessions' mode, Stay connected off and on); CardSwipeChoiceTest.picksAreKept; TopBarTest.itIsKeptAsOneLineAndReadBack; ServerSwitcherTest.namesStillShowAtALargeFont | **covered** (Display has only the text size now) |
| T6 | Refresh usage now (POST /v1/usage/refresh) | UsageRefreshTest (all); UsageRefreshCallTest (all: the phone's call through Core, once in five minutes, a 404 changing nothing); usage-probe.test.ts › "POST /v1/usage/refresh (refreshUsage)…" (two asks share one look, again within 30 s none); fixtures rest-usage-refresh.json, rest-usage.json; contract.test.ts › "POST /v1/account/check, then GET /v1/usage and POST /v1/usage/refresh" (over HTTP, signed out: no look); usage-probe.test.ts, usage-probe-pace.test.ts (all) | **covered** |
| T7 | Testing checklist | SettingsCallsTest.theChecklistIsItsPageOnTheRouteInUse; SettingsTabTest.oneServerRowsOpenTheirScreensAndTheChecklistItsPage, allServersEachBlockActsOnItsOwnServer; fixture rest-qa.json; http.test.ts › "the checklist needs its key…"; lan.test.ts › "a preview and the checklist…" | **covered** (unlocking it with seven taps can't run in a debug build, where it's always on) |
| T8 | Terminal engine (testing only): the live terminal drawn by Ghostty's core (ghostty-web, `assets/term-ghostty/`) instead of xterm.js, as a trial | SettingsTest.terminalEngineGhosttyAndBack; SettingsPlanTest (all); TermEngineTest (all); `node scripts/term-pages.mjs` (the page in headless Chromium: screen, typing, fit, finger scrolling, fullscreen wheel, colours; and both pages measured) | **partial**: the Ghostty page runs only in desktop headless Chromium, never in a phone's WebView; the script isn't part of any suite |
| T9 | An agent's settings on the server, to view: the fixed list and summaries, and Claude Code's MCP servers in every scope (the server's own, then each project's by name), per server | agent-settings.test.ts (all: a secret planted in every place that must stay, in keys and values, also in `~/.claude.json` and `.mcp.json` with the raw body checked; types and shapes; names checked; each MCP scope; caps; paired phone only; missing, malformed, unreadable; a link, a folder or a pipe as `.mcp.json`; the TOML reader); contract.test.ts and ContractTest, fixture rest-agent-settings; SettingsCallsTest (the four agent settings tests: the route, a file's state, a 404 saying the server needs updating, another failure); AgentSettingsScreenTest (all); ProvidersScreenTest.theAgentsCardsLinkToTheirAccountPages (each card's row); SettingsTabTest (All servers: shed's hub answers, z2 isn't asked) | **covered** |
| T10 | An agent's settings on the server, to change: a fixed list per server, a picker of the server's values, a confirm and the owner's fingerprint to loosen a permission, Undo, when each applies | agent-settings-edit.test.ts (all: the allow-list and refused values with no write; device token only; byte identity of JSON and TOML; a file changed before and during the write; read-only, malformed, non-UTF-8, a link, no folder, a new file; backups and their cap; undo, and that it never restores a permissive value; the log); contract.test.ts and ContractTest, fixtures rest-agent-settings-put, -undo, -refused-key, -refused-value, -file-changed, -undo-nothing, ws-agent-settings; SettingsCallsTest (the five change tests: the route, the words, a 404, undo, the broadcast); AgentSettingsScreenTest (the picker, the confirm, OwnerCheck passed, cancelled and with no lock, a permissive value, 409, an older server, Undo, a new file) | **partial**: no run against real agents with an account; when a running session picks a key up isn't tested (DECISIONS, "The agents' settings, edited", 7) |

## 16. App update

| # | Capability | Tests | Status |
|---|---|---|---|
| W1 | The server fetches GitHub releases and offers the APK | releases.test.ts (all) | **covered** |
| W2 | The phone's offer: from the snapshot, from `{t:"app"}`, a notification once per version, Settings → About | AboutUpdateTest (all); SocketEventsTest.aNewerAppOnTheServerIsOfferedAndWithdrawnWithoutReconnecting, aNewerAppOffScreenIsANotificationOncePerVersion, anOlderOrSameAppIsNoOffer; SettingsTest.aboutUpdateRowOffersTheUpdate; fixture ws-app.json | **covered** |
| W3 | Download, check and install | UpdatePolicyTest (all); AppUpdaterTest (all 6: the archive read through PackageManager, a real PackageInstaller session, each of its answers, the next start's note); CoreServersTest.theAppUpdateAsksForThePermissionThenDownloadsAndSaysWhatWentWrong; AllServersSettingsTest.eachServersBlockActsOnItsOwnServer; files.test.ts › "the APK download carries on…" | **covered** (on the JVM Robolectric stands in for what is the system's: it is told what the archive holds, and Android's "Update this app?" dialog and the signature check never run; PackageInstaller's answers are sent as the system would) |
| W4 | The install page for a browser (/download/android) | UpdatePolicyTest.aBrowserGetsThePageOnlyWhereItCouldOpenIt; files.test.ts, lan.test.ts, linux-cli.test.ts (the download tests) | **covered** |
| W5 | The server updates itself, at once whatever sessions are doing: Settings' switch, its line (why not here, installing, what failed, the last one) | MacOnlyScreensTest.theServersOwnUpdateIsTurnedOnFromThePhone, theServersOwnUpdateSaysWhyNotAndWhatItIsDoing, aFailedUpdateIsOnTheRow, aRefusedSwitchIsSaid; ServerUpdateTest (all); fixtures rest-update.json, ws-update.json, ws-update-failed.json; server-update.test.ts (all) | **covered** |

## 17. Widget

| # | Capability | Tests | Status |
|---|---|---|---|
| WG1 | Needs you: rows per computer, what each waits on, offline rows open only | WidgetModelTest (all) | **covered** |
| WG2 | Deny and Allow from the widget, to that row's computer | WidgetSyncTest.allowFromTheWidgetGoesToTheRowsComputerAndTheRowGoes, denyThatDoesntGetThroughSaysSoThenTheButtonsComeBack, claudeCodesOwnDialogIsAnsweredThroughItsMenuOnTheRowsComputer, onALockedPhoneATapOpensTheSessionAndAnswersNothing, openAndTheComputersNameOpenTheApp, theListsRowsAreTheComputerThenWhatEachSessionWaitsOn (`WidgetTap`, `WidgetActions` and `WidgetListService`'s RemoteViews, through Core against FakeDaemon); MultiServerTest.anAnswerFromTheWidgetGoesToTheServerTheApprovalWaitsOn; WidgetModelTest.answeredHereHidesTheRowAndAnsweringShowsOnIt | **covered** |
| WG3 | Redraws at most every 1.5 s, never with the screen off | WidgetSyncTest.aChangeIsDrawnOnceTheGapIsUpAndABurstIsDrawnOnce (and nothing on a timer), backToWhatsShownIsNoPicture, nothingIsDrawnWithTheScreenOffAndWhatChangedIsDrawnAsItComesOn | **covered** (the alarm that redraws a picture gone out of date by itself, `WidgetSync.tick`, isn't fired) |

## 18. Notifications

| # | Capability | Tests | Status |
|---|---|---|---|
| NT1 | An approval: a notification off screen, a banner on screen | ConnectTest.anApprovalInTheSnapshotIsAnnouncedOnceAsABannerOnScreen, offScreenAnApprovalInTheSnapshotIsANotification, aSnapshotWithoutAnApprovalLetsItsNotificationGo; SocketEventsTest.anApprovalOffScreenIsANotification; MultiServerTest.theWatchedHubsApprovalIsANotificationNotABanner | **covered** |
| NT2 | Allow, Deny, Reply and Answer from a notification | NotifyActionsTest (all 13: the receiver as Android delivers it, each way it goes); ServersTest.aNotificationGoesToTheServerItNames, anAnswerGoesWhereTheApprovalWaits; MultiServerTest.aNotificationNamesItsServerAndGoesThereEvenWithTheApprovalGone; WidgetModelTest.denyAndAllowOnlyWhereTheNotificationsRuleSaysSo | **covered** |
| NT3 | Finished, error, job died, low battery, put-away rules | SocketEventsTest.aTurnThatEndsIsAFinishedNoticeAMomentLater, aTurnThatCarriesOnWithinTheMomentSaysNothing, anErrorIsSaidAtOnceAndOffScreenAsANotification, aDeadJobIsSaidOnce, aLowBatteryIsSaidOnceUntilItHasBeenCharged; MacOnlyTest.theMacSleepingSoonIsABannerOnScreenAndANotificationAway, backpackEndingOffersTwoHoursMore; NotifyActionsTest.backpacksNoticeOffersTwoHoursMoreWithoutOpeningTheApp; AutomationNotesTest (all 4); PutAwayTest (all); PhrasesTest (all) | **covered** |
| NT5 | A server's update that failed: a banner or a notification, once per release and once when it gives up, each server's its own, a tap opening its section of Settings (the jump itself by hand) | ServerUpdateNoticeTest.onScreen_aFailureIsABannerOncePerRelease_andOnceMoreWhenItGivesUp, offScreen_aFailureIsANotification_fromASnapshotToo_andOnceAcrossRestartsOfTheApp, eachWatchedServerSaysItsOwn, anUpdateGoingWell_orAnOlderDaemon_saysNothing; NotifyActionsTest.aFailedServerUpdateOpensThatServersSettings_andGoesOnceItsFine; SettingsPlanTest.keysForAJump | **partial** |
| NT6 | An approval a daemon restart took away, its session still asking in its own dialog, said again | ServerUpdateNoticeTest.anApprovalARestartTookAway_itsSessionStillAsking_isSaidAgain, anApprovalAnsweredWhileAway_isNotSaidAgain; claude-agents.test.ts "a restart handed a held ask back"; with a real Claude Code by hand (docs/DECISIONS.md, "Servers update without waiting") | **partial** |
| NT4 | A tap opens the right session on the right server | NavTest (all); ServersTest.aNotificationGoesToTheServerItNames | **covered** |
| NT7 | Which notifications: a master and a switch per group as defaults, Same as all / Custom / Off per server, kept on the phone; a switch off clears the shade; banners, cards and the widget unchanged; the warning before a prompt goes silent (docs/DECISIONS.md, "Which notifications") | NotificationPrefsTest (all); NotificationControlsTest (all); NotificationSettingsTest (all); clearing the shade against Robolectric's list of active notifications, not yet on a phone | **covered** |

## 19. Statistics

| # | Capability | Tests | Status |
|---|---|---|---|
| ST1 | Totals, ranges (Today, Week, Month, YTD, All), the last 30 days as bars | StatsScreenTest (all); DayBarsTest (all); StatsTest (all); SettingsCallsTest.statisticsAreAskedForTheirRangeAndKeptForAMoment, statisticsTheServerRefusesAreItsWords (`StatsSource.load` against FakeDaemon); fixtures rest-stats.json, rest-stats-bad-range.json; contract.test.ts › "GET /v1/stats…"; stats.test.ts (all); stats-ytd.test.ts (all) | **covered** |
| ST2 | All servers: every server's statistics added up, the ones not answering named, the ones too old for the range named apart | AllStatsTest (all); StatsScreenTest.allServersAddsTheirStatisticsUp, allServersAsksEachForTheSameRange, aServerThatFailsIsLeftOutAndNamed, noServerAnsweringSaysSo, oneServerIsStillOneServers, allServersYtdIsSinceJanuary1Too, allServersStartsAtTheEarliestOfThoseThatAnswered, allServersNamesTheOneTooOldAndAddsUpTheRest, everyServerTooOldIsNotAnErrorEither | **partial**: asking real servers at once isn't driven (a fake hub has no server to ask, so a linked one with no answer put fails); Home handing the servers to the screen isn't drawn |
| ST3 | All and YTD reach back to the first use: older days from Claude Code's summary (sessions, tool calls), kept in stats.json so losing the file loses nothing, a line for what starts later | stats-backfill.test.ts (all); fixture rest-stats-backfill.json; contract.test.ts › "GET /v1/stats…"; ContractTest; StatsTest.anOlderServersBackfillTokensAreIgnored; AllStatsTest.olderDaysTakeTheEarliestStartAndTheHoursLineFollowsEachServer; StatsScreenTest.allWithOlderDaysStartsAtFirstUseAndSaysWhatStartsLater, ytdWithOlderDaysSaysSoToo, noOlderDaysNoLine, aDayOnlyTheSummaryKnowsHasNoBarAndSaysNoHoursCounted, allServersLineUsesTheEarliestOfEach | **covered** |

## 20. Mac-only features

On a Linux server these answer as unavailable (docs/PROTOCOL.md, "Mac-only features").

| # | Capability | Tests | Status |
|---|---|---|---|
| MO1 | Linux says they are unavailable, each with a sentence | platform.test.ts › "a Mac lacks nothing; Linux lacks the Mac-only features…"; wsl.test.ts › "a WSL snapshot's reasons…"; mac-only-routes.test.ts (all 3); MacOnlyTest.aLinuxServerLacksThemAndSaysWhyWhenAskedAnyway, aMacHasEveryFeature; MacOnlyScreensTest.aLinuxServerHasNoWindowToOpen; fixtures rest-mac-awake-unavailable.json, rest-session-open-unavailable.json | **partial**: the Settings tab's own check that hides Stay awake (`mac.has(awake)` in Settings.kt) isn't drawn: the tab takes HubViewModel, which the screen tests have no fake of (SettingsTest says the same); Home's "Sign in on the Mac" is T4's |
| MO2 | Stay awake: While working, Backpack, Off, +2 h | AnswersTest.readsWhetherBackpackCanBeExtended; MacOnlyTest.aMacsBatteryAndStayAwakeComeFromTheirMessages, stayAwakeIsSetAndExtendedThroughTheMacsRoute, backpackEndingOffersTwoHoursMore; MacOnlyScreensTest.stayAwakeIsPickedOnTheMac, backpackBeforeItsSetupSaysToSetItUpOnTheMac, aMacWithoutALidHasNoBackpack, aRefusalIsSaid; NotifyActionsTest.backpacksNoticeOffersTwoHoursMoreWithoutOpeningTheApp; fixtures ws-awake.json, rest-mac-awake.json, ws-mac-sleep-soon.json, ws-mac-backpack-ending.json; awake.test.ts (all) | **covered** |
| MO3 | Battery | AnswersTest.lowBatteryIsTwentyPercentOrLessAndNotCharging; SocketEventsTest.aLowBatteryIsSaidOnceUntilItHasBeenCharged; MacOnlyTest.aMacsBatteryAndStayAwakeComeFromTheirMessages; fixture ws-battery.json; battery.test.ts (all) | **covered** |
| MO4 | Open the terminal in a window on the Mac (POST …/open) | MacOnlyTest.openOnTheMacSaysWhichAppAndWhetherItWasOpenAlready, aLinuxServerLacksThemAndSaysWhyWhenAskedAnyway; MacOnlyScreensTest.aMacsTerminalOpensInAWindowThere, aLinuxServerHasNoWindowToOpen; mac-only-routes.test.ts › "POST …/open on Linux…"; fixture rest-session-open-unavailable.json | **covered** (the daemon's Mac path runs `/usr/bin/open` on app bundles and is fixed at load by `platform.ts`, so only a Mac can run it) |

## 21. CLI and daemon admin

| # | Capability | Tests | Status |
|---|---|---|---|
| D1 | `hypernova setup` and `uninstall`: Node, the service, lingering, the integration | linux-cli.test.ts (all) | **covered** |
| D2 | `pair`, `status`, `doctor`, `logs`, `lan` | linux-cli.test.ts (all) | **covered** |
| D3 | WSL | wsl.test.ts (all); linux-cli.test.ts (the WSL tests) | **covered** |
| D4 | Hooks and the status line tap | hooks.test.ts (all) | **covered** |
| D5 | Shared terminals (the holder) | holder-exit.test.ts, holder-move.test.ts, term-scroll.test.ts (all); hn-cli.test.ts (all 3: `hn ls` and `hn list`, `hn attach` in a pty, typing, Ctrl+\\ detaching, the refusals) | **covered** (a window resize under `hn attach` isn't driven) |
| D6 | `claude agents` jobs | claude-agents.test.ts (all); job-deaths.test.ts (all) | **covered** |
| D7 | Claude Code's SDK, fetched not bundled | sdk.test.ts, sdk-missing.test.ts (all) | **covered** |
| D8 | Usage: the probe, merging readings, the spend rate | usage-probe.test.ts, usage-probe-pace.test.ts, usage-merge.test.ts, usage-rate.test.ts, usage-screen.test.ts, usage-no-five-hour.test.ts (all) | **covered** |
| D9 | Account and limits | account.test.ts, account-restart.test.ts, restart.test.ts, provider-env.test.ts (all) | **covered** |
| D10 | Where state and logs live; log rotation | platform.test.ts, linux-fixes.test.ts, util.test.ts (all) | **covered** |
| D11 | Security: no CORS, the hooks' secret, keys masked, credential files kept back | http.test.ts › "no web page may read the phone API…"; hooks.test.ts › "the hooks' route takes its own secret only…"; normalize.test.ts (the masking tests); secretfiles.test.ts (all); permission-rules.test.ts › "secrets in a settings file never leave…" | **covered** |

## Protocol without a contract fixture

The daemon already tests most of these in its own suites. What is missing is the shared golden file
that both sides must agree on.

- **WebSocket:** `pong` (the phone's constant answer, which the daemon takes and ignores:
  ws-ping.test.ts). (`speaking` has none on purpose: it goes to the Mac app alone, which this
  repository doesn't have, and the phone never reads it.)
- **REST:** …/menu, GET /v1/codex and POST /v1/codex/refresh (the phone calls neither),
  /v1/file (bytes, not JSON: FilesLiveTest and files.test.ts), /v1/preview, /v1/mac/files,
  POST /v1/speech (the older whole clip) and /v1/speech/plays/{id}/stop (the phone stops by
  `speech.progress`), POST /v1/automations/{id}/token. …/open and POST /v1/mac/awake have their
  Linux refusals only (`rest-session-open-unavailable`, `rest-mac-awake-unavailable`): a Mac's
  answer can't be made on the Linux machine the daemon's suite runs on. Everything else has its
  fixture now.

## Biggest gaps

Ranked by how likely a move to Kotlin Multiplatform breaks it (most of `data/` moves from OkHttp to
Ktor; the screens move to Compose Multiplatform) and how soon the owner would notice.

1. ~~**Starting a new session** (N1).~~ Covered 2026-10-31. The owner does it many times a day, and its request body and
   refusals go over HTTP with no rig test and no fixture. *Layer 2:* an ActionsTest for `start` and
   its 409s; *layer 1:* `rest-sessions.json`.
2. ~~**Attachments** (Q7).~~ Covered 2026-10-31. File reading and the upload body are the most platform-specific HTTP code,
   and neither side tests the route. *Layer 2:* `upload` against FakeDaemon (bytes, name,
   `attachment_gone`); *daemon:* an HTTP test of POST /v1/attachments.
3. ~~**Mutual TLS and the device certificate** (P3).~~ Done: MutualTlsTest, DeviceCertStoreTest,
   fixture rest-device-cert.json.
4. ~~**Pairing end to end** (P1).~~ Done: PairTest, PairingScreensTest.
5. ~~**Load earlier** (C4).~~ Covered 2026-10-31. A long chat would silently show only its latest events. *Layer 2:* a
   SocketEventsTest-style test of `hasEarlier` and `loadEarlier` with the limit fixture.
6. **Automations on the phone** (U1, U4, U5). **Done, 2026-10-31: covered** (see the rows). Many REST calls and five messages with no rig test,
   no fixture and no screen. *Layer 1:* `ws-automation`, `ws-run`, `ws-note` fixtures; *layer 2:*
   AutomationsLive calls; *layer 3:* the tab and the editor.
7. **Files and What changed** (F1, F2, F3, F5). **Done, 2026-10-31: covered** (see the rows). Whole screens with no screen test, and their routes
   never run from the phone. *Layer 3:* Files, FileViewer and ChangesScreen baselines; *layer 2:*
   `files`, `file`, `download` and `ChangesRepo`.
8. **Read aloud playback** (V3). **Done, 2026-10-31: covered** (see the rows). Audio is per platform (`expect`/`actual`), and on the owner's Linux
   server it is the phone's own text-to-speech, which nothing runs. *Layer 2:* the play routes with
   a fake player; *layer 1:* fixtures for the speech routes and messages.
9. **Codex flows on the phone** (X1, X4, X5). **Done, 2026-10-31: covered** (see the rows). The switch sheet, Codex's model switch and turning it
   on all go untested on the phone. *Layer 2:* `switchPreview`, `switchAgent`, `setCodexModel`,
   `setCodexEnabled`; *layer 1:* fixtures for …/switch/preview and /v1/codex/models.
10. **Agent boards and the agent screen** (G1, G3). **Done, 2026-10-31: covered** (see the rows). Cursor polling is timing code that a move to
    other coroutines breaks easily, and the owner opens agents often. *Layer 2:* `agents()` and
    `agentEvents` with a cursor and `starting`; *layer 3:* AgentScreen.

Just behind them: ~~the sky's drawing (H12)~~, ~~account sign-in (T4)~~ and ~~blocked calls' Allow
& retry (S5)~~, all covered now. (The notification receivers, NT2, are done: NotifyActionsTest.)
