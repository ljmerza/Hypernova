# Features and the tests that guard them

A checklist of every user-facing capability of the Android app, and of the daemon features the app
depends on, with the tests that guard each one. It is the fourth layer of the safety net built
before a move to Kotlin Multiplatform and Compose Multiplatform (docs/PLATFORMS.md). The other three
are the contract fixtures (docs/PROTOCOL.md, "Contract fixtures"), the data-layer rig and the screen
tests. Built from the code, docs/PROTOCOL.md, docs/DECISIONS.md and the screens under
`android/app/src/main/java/com/prudnikweb/hypernova/ui/`, on 2026-10-31. Both suites passed that day:
940 app tests, 857 daemon tests.

**Update this file whenever a feature or its tests change:** add the row, move its mark, name the test.

## How to read it

- **covered**: the behaviour is asserted.
- **partial**: some paths only; the row says what is missing.
- **gap**: nothing guards it.

Test names, shortened:

- `Class.test` is an app test under `android/app/src/test/java/com/prudnikweb/hypernova/`.
  - Layer 2, the real `LiveHub`/`Core` against `FakeDaemon`: `data/live/` (ActionsTest,
    AllServersSettingsTest, ConnectTest, CoreServersTest, ErrorsTest, MultiServerTest,
    ReconnectTest, SocketEventsTest).
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
| P1 | Pair from the server's QR code or link: its `/pair` page hands over to the app; paste a link; `hypernova://` links; an older `hypernova.observer` link read from its fragment | PairLinksTest (all 11); lan.test.ts › "the pairing page on the listener…", "pairing over the listener: open while a code is shown…", "the pairing links point at this server…"; linux-cli.test.ts › "pair: the QR code and its link…", "pair waits for the phone and names it"; fixture rest-pair.json | **partial**: the app's `Pairer.pair` (POST /v1/pair over the pinned listener, then `u`) never runs against a daemon; the onboarding screen has no test |
| P2 | Pair the same computer again, or another one | ReplacePairingTest (all); ServersTest.pairingAnotherComputerAddsIt, pairingTheSameComputerAgainTakesItsPlace, theSameComputerByIdOrByName; pair.test.ts (all) | **covered** |
| P3 | Device certificate and mutual TLS on the local route (the CSR, POST /v1/device/cert, the pinned server certificate) | CsrTest (all); LanRouteTest.pinAcceptsTheRightCertificate, pinRefusesAWrongCertificate, pinRefusesAnExpiredCertificate, theDeviceCertificateGoesToTheListenerAlone, theWebViewGoesOnOnlyForThePinnedCertificate; lan.test.ts › "POST /v1/device/cert…", "with its certificate the phone reaches the whole API…", "a new device certificate closes the WebSockets…", "the daemon keeps a device certificate's expiry…" | **partial**: the phone's `DeviceCertStore` (keystore key, request, install, renewal) is untested; FakeDaemon is plain HTTP, so no layer-2 test runs a TLS handshake with a client certificate; /v1/device/cert has no fixture |
| P4 | Pick a route: the local listener first, then the base URL; hotspot sweep; recheck back-off | LanRouteTest (probe and chooser tests); LocalFirstTest (all 11); RouteRecheckTest (all); ConnectTest.theRouterProbesTheBaseUrlFirst; lan.test.ts › "only the local network, the hotspot…", "the advertised addresses are private IPv4 and ULA only…", "a phone on another private subnet…"; linux-fixes.test.ts › "the local route skips bridges…" | **covered** |
| P5 | Connect: the snapshot, then the socket, the token on every request | ConnectTest.theSnapshotFillsSessionsApprovalsAndUsageThenTheSocketOpens, everyRequestCarriesTheToken; fixture snapshot.json; http.test.ts › "/v1/ws takes the token in its query…" | **covered** |
| P6 | Ping and pong; a silent socket is declared dead | ConnectTest.aPingIsAnsweredWithAPong; ReconnectTest.aSilentSocketIsDeclaredDeadAfterSeventySeconds, backOnScreenASocketSilentForThirtySecondsIsReplacedAtOnce | **partial**: no daemon test that it sends `{t:"ping"}` every 25 s or reads the pong; no fixture |
| P7 | Reconnect: once at once, then every 30 s; on a new network, screen on, an alarm, Retry now | ReconnectTest (all 13); RetryTest (all); ErrorsTest.aServerThatIsntThereIsOfflineAndTriedAgainAtOnceThenEveryThirtySeconds, aServerErrorOnTheSnapshotIsRetried, anActionWhileTheServerIsAwaySaysItIsntAnswering | **covered** |
| P8 | A 401 anywhere unpairs | ErrorsTest.a401OnTheSnapshotUnpairsAndNothingIsTriedAgain, a401OnTheSocketUnpairs, a401OnAnActionUnpairs, a401WhileOpeningASessionUnpairs | **covered** |
| P9 | Forget a server (DELETE /v1/devices/me) | AllServersSettingsTest.eachServersBlockActsOnItsOwnServer; ServersTest.forgettingTheFirstServerClearsTheOldKeysToo, forgettingTheActiveOneShowsTheNext; lan.test.ts › "forgetting a device revokes its certificate…" | **covered** |
| P10 | Stay connected in the background (`ConnectionService`, its permanent notification and Disconnect, `BootReceiver`) | ReconnectTest.offScreenWithoutTheBackgroundServiceTheNextTryWaitsForTheApp | **partial**: the service, its notification, Disconnect and the boot receiver never run |
| P11 | Connection log in Settings | ConnLogTest (all) | **partial**: the screen and its Copy have no test |
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
| M8 | Automations stay one server's under All ("pick a server") | none | **gap** |

## 3. Home

| # | Capability | Tests | Status |
|---|---|---|---|
| H1 | Three groups and their sorts (A to Z; Completed newest first) | HomeGroupsTest (all); HomeScreenTest.groupsInOrderEachSorted | **covered** |
| H2 | A Working card says what it does now and "for 12 min" | CardNowTest (all); HomeScreenTest.workingCardSaysItsTodoAndForHowLong; chat.test.ts › "the list in short for Home…"; turn-clock.test.ts (all) | **covered** |
| H3 | A Needs-you card: Allow and Deny, a terminal dialog denied through the menu, no blind Allow | HomeCardTest.aWholeCommandCanBeAllowedOnTheCard, noBlindAllow, questionsAndPlansOpenTheSession; HomeScreenTest.needsYouCardAllowAnswersItsApproval, needsYouCardDenyAnswersItsApproval, anAnswerThatDidNotGetThroughBringsThePillsBack, terminalDialogCardDeniesThroughTheMenu; TerminalAskTest.homeCardAllowsAndDeniesIt | **covered** |
| H4 | Unread dot and "finished … ago" on Completed cards | UnreadTest (all); HomeScreenTest.unreadCompletedCardHasTheDot; HomeMoreTest.completedCardsAreUnreadTillOpenedAndAReplyAfterBringsTheDotBack (Home reads the read marks; opening the chat clears the dot, a later reply brings it back, a reply while it's on screen doesn't), aHubWithoutAServerKeepsNoMarksAndShowsNoDots | **covered** |
| H5 | Long-press menu: Rename, Stop, Delete, Restart (and Codex's) | HomeCardTest.theMenuOffersWhatTheMacCanDo, theMenuOffersRenameStopAndDeleteForCodex, readsTheJob; HomeMoreTest.theMenuRenamesWithItsSheet, theMenusStopGoesAtOnce, theMenusDeleteAsksFirst, anOutdatedJobsMenuRestartsItAfterAsking; ActionsTest.renameTrimsTheName, stopRespawnAndDelete; contract.test.ts › "POST …/rename on a claude agents job", "POST …/stop, …/respawn and /v1/jobs/respawn"; codex.test.ts › "the session menu renames…", "…deletes a Codex session…" | **covered** |
| H6 | Card swipes: Smart, and Settings' picks for left and right | CardSwipeChoiceTest (all); HomeScreenTest.smartSwipeOpensANeedsInputCard, smartSwipeStopsACompletedJobThatRuns, smartSwipeDeletesAStoppedJobAfterAsking, workingCardHasNoSmartSwipe, rightSwipeFollowsTheSetting, aPickThatDoesNotFitLeavesThatWayWithoutASwipe | **covered** |
| H7 | Search Home's sessions: local matching plus the server's full text | HomeSearchTest (all); HomeScreenTest.searchOpensFiltersAndCloses | **covered** |
| H8 | A job that died: "Stopped with an error" on its card, a banner or notification | JobDiedTest (all); SocketEventsTest.aDeadJobIsSaidOnce; fixture ws-job-died.json; job-deaths.test.ts (all); claude-agents.test.ts › "a job that crashes mid-turn…" | **covered** |
| H9 | "Restart all" for jobs on an older Claude Code | OutdatedJobsTest (all); RestartOfferTest (all); HomeMoreTest.theOutdatedJobsNoticeRestartsThemAllAfterAskingAndSaysWhichRefused; ActionsTest.respawnOutdatedJobsUsesTheSlowRoute; fixture rest-jobs-respawn.json; claude-agents.test.ts › "the respawn-all route…" | **covered** |
| H10 | Cards above the sessions (account, providers, prompts for gone sessions, automation questions), dismiss and undo | AttentionTest (all); HomeGroupsTest.theAccountCardFollowsWhatsOverIt; ProvidersTest (all); HomeMoreTest.aPromptWhoseSessionEndedStartsANewSessionOrGoes, aProvidersTroubleIsACardThatGoesWithASwipeAndComesBackWithUndo; SessionRoutesTest.aWaitingPromptWhoseSessionIsGoneStartsANewSessionInItsFolder | **partial**: the account card (signed out, a limit, no plan) and an automation's question card (the Automations area's) aren't drawn in a screen test |
| H11 | Usage cards (Claude, Codex, one per computer) | UsageCardsTest (all); AllUsageTest (all); UsageProjectionTest (all); UsageRateTest (all); FiveHourReportedTest (all); ModelWeeksTest (all); HomeMoreTest.whereOnlyCodexIsInUseHomesUsageCardIsCodexs, eachModelsOwnWeekHasItsMeter; the `home_groups` baseline | **covered** |
| H12 | The sky: the sun as usage, planets as projects, Codex's star, companions | SkyPlanTest, SkyLayoutTest, SkyPhysicsTest, SkySplitTest, AgentLeadTest, PaceTest, CountdownTest (all) | **partial**: placement and physics are tested; the Canvas drawing is in no baseline: it moves by its own clock and physics, so a picture needs a frozen-frame seam in Cosmos first |

## 4. Session chat

| # | Capability | Tests | Status |
|---|---|---|---|
| C1 | Transcript rows: user, reply, tool, terminal output, diff, command, thinking, notices | SessionScreenTest.messagesRenderEachAsItsKind; ToolRunsTest (all); AsidesTest (all); AnswersTest.thinkingIsOngoingOnlyWhileItIsTheNewestThingInARunningSession; normalize.test.ts (all); contract.test.ts › "GET …/events: the transcript as the chat draws it"; fixture rest-session-events.json | **covered** |
| C2 | Markdown, tables, Mermaid diagrams, links that ask first | MermaidTest (all); LinkedTextTest (all); LinksTest (all); ChatMoreTest.aReplysMarkdownIsDrawnNotShown (a heading, a table and a code block drawn, not their marks) | **partial**: Mermaid's WebView drawing never runs (Robolectric has no WebView engine) |
| C3 | Live updates: events grow, `event.removed`, a resync reloads the open chat, a failed load can be retried | SocketEventsTest.eventsGrowTheOpenTranscriptAndEventRemovedTakesOneBack, aResyncSnapshotReloadsTheOpenSession, aHistoryFailureIsSaidAndCanBeRetried; EventRemovedTest (all); fixtures ws-event*.json | **covered** |
| C4 | Load earlier events | SessionRoutesTest.aLongHistoryOpensOnItsLatestAndLoadEarlierPutsTheRestInFront, aHistoryTheMacRewroteMeanwhileIsTakenWholeWithWhatCameSince, aShortHistoryHasNothingEarlierAndAFailedLoadEarlierIsSaid (the limit fixture); ChatMoreTest.aLongSessionOffersItsStartAndAsksForIt, aSessionThatHasItAllOffersNothingEarlier; fixture rest-session-events-limit.json | **covered** |
| C5 | Status bar: what it's doing, the workflow's tally, the red bar, Stop | StatusModelTest (all) | **covered** |
| C6 | Top bar: the owner's parts and order, 5H only when reported, a tap opens the sheet | TopBarTest (all); SessionScreenTest.topBarShowsTheDefaultItems, topBarHidesFiveHoursWhenNotReported, topBarFollowsTheChosenItemsAndOrder, topBarOpensTheSessionSheet | **covered** |
| C7 | Usage strip: context and cost, Compact at 85% | ChatTest.readsTheUsage, theStripNamesTheModelTheSessionRunsNow, money; RowFitTest (all); ChatMoreTest.aNearlyFullContextOffersCompactInOneTap, aContextWithRoomHasNoCompact; ChatRepoTest.theUsageStripReadsTheSessionsUsage; fixture rest-session-usage.json; chat.test.ts › "knows the models…", "prices a call…", "adds up a session and its agents…" | **covered** |
| C8 | To-do list | ChatTest.readsTheTodoList; ChatMoreTest.theToDoListShowsWhatsDoneWhatsUnderWayAndWhatsLeft; ChatRepoTest.theToDoListReadsClaudesList; fixture rest-session-todos.json; chat.test.ts › "the to-do list TodoWrite last wrote", "the task list TaskCreate and TaskUpdate keep" | **covered** |
| C9 | Rewind from a message | ChatMoreTest.rewindAsksFirstSayingWhatGoesAndSaysWhenItCant; ChatRepoTest.rewindGoesOnInTheNewSessionWithTheMessageForThePromptBox, whatTheyCantDoIsTheServersWordsOrSaysTheServerIsOlder; fixture rest-session-rewind.json; contract.test.ts › "POST …/rewind…" (first_message); chat.test.ts › "rewinds to just before a message…"; sdk-missing.test.ts › "what runs through it answers sdk_missing…" | **covered** |
| C10 | Message menu: copy, quote, read aloud | ChatTest.quotesLineByLine, readsARepliesWordsNotItsMarkdown; ChatMoreTest.aMessagesMenuCopiesQuotesAndSavesItAsAQuickPrompt | **covered** |
| C11 | Live terminal: one-minute ticket, xterm in a WebView, the key bar | TermTicketTest (all); TermFramesTest (all); KeyBarTest (all); term-scroll.test.ts (all); SessionRoutesTest.theTerminalsTicketIsOnTheRouteInUse; fixture rest-session-terminal.json; contract.test.ts › "POST …/terminal and …/interrupt…" (and 409 no_terminal) | **partial**: `TerminalScreen` never runs (its xterm is a WebView, which Robolectric can't run) |
| C12 | Never a session over a session: Back goes Home | NavTest (all) | **covered** |
| C13 | Limits: the PAUSED notice, Continue, carry on by itself | ProvidersTest.aBlockStaysWhileItCarriesOnByItselfOrWaitsForContinue; SessionsTest.readsWhyItEnded; SessionRoutesTest.afterALimitContinueSaysWhyAndCarryingOnByItselfCanBeTurnedOff; ChatMoreTest.carryingOnByItselfCanBeTurnedOffOrDoneNow, carryingOnByItselfTurnedOffCanBeTurnedBackOn, aSessionStoppedWhileAwayContinues; fixtures ws-session-limited.json, ws-usage-limited.json, rest-session-auto-continue.json; contract.test.ts › "POST …/auto-continue and …/provider…" (bad_on, not_limited); resume.test.ts (all); account.test.ts (limit tests) | **covered** |

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
| Q7 | Attachments: paperclip, camera, pasted images | PastedTest (all); PickedTest (all: the 30 MB limit, an unreadable document); SessionRoutesTest.anUploadStreamsTheFileUnderItsName, anUploadTheServerRefusesSaysWhy, aPromptCarriesItsFilesPathsAndAGoneFileIsSaid, aWaitingPromptWhoseFilesAreGoneGoesWithoutThem; fixtures rest-attachment.json, rest-prompt.json, rest-prompt-attachment-gone.json; attachments.test.ts (all); contract.test.ts › "POST /v1/attachments, then a prompt…" (the @-mention typed); normalize.test.ts › "a command typed after pasting a picture…" | **covered** |
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
| U1 | The tab: list, switch on and off, pause all, delete | SummaryTest.readsTheSnapshotAndTheSocket; EditorRulesTest.aRunsMessageUpdatesTheBook, theRunsCountsKeepOneWindowAsRunsCome; automations.test.ts › "routes: list, create, replace, enable…"; http.test.ts › "automations: the routes, their errors…" | **partial**: the app's `AutomationsLive` calls never run against a rig; `automation`, `run` and `note` messages are read only from hand-written JSON (no fixtures); the tab has no screen test |
| U2 | Editor: triggers, templates, actions, steps, paid providers | EditorRulesTest, PaidTest, SummaryTest, WorkflowRulesTest, WorkflowWordsTest, WorkflowsTest (all); schedule.test.ts, workflows.test.ts, workflows-contract.test.ts (all) | **partial**: the editor screens are untested; the phone's POST /v1/automations/check never runs |
| U3 | Runs: following, gates, the account gate, results, notes | automations.test.ts (all); SummaryTest.runLines, run; WorkflowWordsTest.aRunsStepsInWords, aRunsOutputsAndItsNewReasons | **covered** |
| U4 | Run now, Stop, Retry (from a step) | EditorRulesTest.retryIsForRunsThatEndedBadly; WorkflowWordsTest.whichStepRetryStartsFrom; automations.test.ts › "runs: Retry runs an ended one again…"; workflows.test.ts › "Retry from a step…"; http.test.ts › "workflows: an ask answered over HTTP…" | **partial**: the phone's calls never run |
| U5 | Ask steps answered from the phone or a notification | WorkflowsTest.answersFlagsAndNotes; workflows.test.ts › "start, ask, answer…", "an ask nobody answers…" | **partial**: answering from the app or a notification never runs |
| U6 | Webhook trigger and outputs | workflows.test.ts (webhook, firing and output tests); http.test.ts › "workflows: a webhook trigger fired…", "the local fire route…", "webhook outputs…" | **partial**: showing and renewing the token on the phone is untested |
| U7 | Shell steps | workflows.test.ts (the shell tests) | **covered** |

## 11. Codex

| # | Capability | Tests | Status |
|---|---|---|---|
| X1 | Turn Codex on or off; its card in Settings | AgentCardsTest (all); fixture ws-codex.json; codex-only.test.ts › "the phone turns Codex off and on" | **partial**: the app's `setCodexEnabled` never runs |
| X2 | Codex's state, account and windows; its star and usage card | CodexTest.readsCodexWithItsAccountAndWindows, noCodexOnAMacWithoutIt; AgentLeadTest (all); UsageCardsTest (all); SkySplitTest (all); codex.test.ts › "the snapshot says what Codex is…"; codex-units.test.ts › "Codex's windows go by their length…" | **covered** |
| X3 | A Codex session's chat: turns, queue, Stop | codex.test.ts (all); codex-units.test.ts › "items as the chat's events…", "a thread's history reads as its turns…" | **partial**: no rig test has a Codex session |
| X4 | Codex model and effort switch | CodexTest.readsCodexModels; codex.test.ts › "mode and model take effect from the next turn…", "the models' default…" | **partial**: the app's `setCodexModel` never runs; no fixture for GET /v1/codex/models |
| X5 | Switch agents: preview, edit, switch | CodexTest.readsASwitchPreview, readsACodexSessionAndItsSwitch; codex.test.ts › "a switch carries the approved text…", "a busy session can't switch…", "two switches at once…"; codex-units.test.ts › "a switch's text reaches the other agent…" | **partial**: the switch sheet, `switchPreview` and `switchAgent` are untested on the phone; no fixtures for …/switch/preview or …/switch |
| X6 | Rename, Stop and Delete a Codex session | HomeCardTest.theMenuOffersRenameStopAndDeleteForCodex; CardSwipeChoiceTest.deleteForJobsAndCodexOnly; codex.test.ts (the session-menu tests) | **covered** |
| X7 | Codex in Statistics | StatsTest (the Codex tests); codex-only.test.ts › "Statistics count Codex's turns…"; codex-units.test.ts › "Codex's tokens in Statistics…" | **covered** |

## 12. Agents boards

| # | Capability | Tests | Status |
|---|---|---|---|
| G1 | Agent and workflow cards, live | fixtures ws-agent.json, ws-workflow.json, rest-agents.json; contract.test.ts › "{t:"usage"}, {t:"agent"} and {t:"workflow"}", "GET …/agents and an agent's events…"; agents.test.ts (all) | **partial**: the phone's board (`agents()`, GET …/agents) never runs against a rig; the chat's agent and workflow cards aren't drawn |
| G2 | The status bar's agents and the workflow's tally | StatusModelTest (the workflow and agent tests) | **covered** |
| G3 | An agent's own screen: its transcript, "Starting…", following by cursor | AgentEmptyTest (all); fixtures rest-agent-events.json, rest-agent-events-starting.json; agents.test.ts › "a phone following an agent gets only what changed since its cursor…", "an agent just started…" | **partial**: the phone's polling (`agentEvents` with its cursor, every 1.5 s while starting, a whole read at the end) never runs; `AgentScreen` isn't drawn |

## 13. Read aloud and voice

| # | Capability | Tests | Status |
|---|---|---|---|
| V1 | Dictation: press and hold, hands-free, Send while dictating | MicGestureTest (all); VoiceTest (all) | **partial**: Android's `SpeechRecognizer` (`PhoneDictation`) never runs; the dictation capsule isn't drawn |
| V2 | Which recognizer: the phone's, or on-device only | DictationChoiceTest (all); SettingsTest.voiceEngineOnDevice | **covered** |
| V3 | Read aloud: the server's voice streamed as PCM, or the phone's own text-to-speech (a Linux server) | SpeechTest (all); ChatTest.readsARepliesWordsNotItsMarkdown; speech.test.ts (all) | **partial**: `SpeechPlayer`, the phone's own text-to-speech path (the one a Linux server uses), the speaking bar and Stop never run; no fixtures for the speech routes or `speech`, `speech.stop`, `speech.progress` |
| V4 | Read aloud settings: voice, speed, Test | SpeechTest.speedsSnapToTheStepsSettingsOffers; speech.test.ts › "the setting: the Mac's voice only…" | **partial**: the Read aloud section isn't drawn and its Test never runs |

## 14. Files and changes

| # | Capability | Tests | Status |
|---|---|---|---|
| F1 | Browse files (the projects folder; outside it with the setting on) | FileProblemTest (all); files.test.ts (all); secretfiles.test.ts (all) | **partial**: the app's `files()` and the Files screen never run; no fixture for GET /v1/files |
| F2 | View a file | FileProblemTest (all); files.test.ts › "a file: its codes…" | **partial**: `FileViewer` and `file()` are untested |
| F3 | Download a file: resume, names, share or save, clean-up | DownloadsTest (all); files.test.ts › "the content disposition keeps any name…" | **partial**: `DownloadJob` and `download()` never run against a server (only the APK's in CoreServersTest) |
| F4 | Previews of a dev server or a file; the in-app web page | WebTitleTest, PageErrorTest, PreviewPortsTest (all); LinksTest.onlyTheWebOpensAndDevServersPreview; LanRouteTest.pagesOpenOnTheActiveRouteAndOutsideByTheBaseUrl; files.test.ts › "previews never serve keys…"; lan.test.ts › "a preview and the checklist asked for over the listener…" | **partial**: the phone's POST /v1/preview and `WebScreen` are untested; no fixture |
| F5 | What changed: the list, a diff, highlighting | ContractTest (rest-changes.json, rest-changes-diff.json through `ChangesRepo`); SessionInfoTest.theGitPartFromTheChanges; changes.test.ts (all) | **partial**: `ChangesScreen`, the diff's highlighting and `ChangesRepo`'s requests (its scope, `all=1`) never run |
| F6 | Browse outside projects setting | AllServersSettingsTest.eachServersBlockActsOnItsOwnServer; FileProblemTest (the "Turn on in Settings" test); fixture ws-files.json; files.test.ts, folders.test.ts | **covered** |

## 15. Settings

| # | Capability | Tests | Status |
|---|---|---|---|
| T1 | Settings' parts, one server or All servers | SettingsTest.thePartsAsTheyStart; SettingsPlanTest (all); AllServersSettingsTest.eachServersBlockActsOnItsOwnServer | **covered** |
| T2 | Providers: add, edit, Check, delete, balance | ProviderFormTest (all); ProvidersTest (all); fixture ws-providers.json; providers-store.test.ts, providers.test.ts, balance.test.ts (all); http.test.ts › "providers: addresses cleaned…" | **partial**: the app's `saveProvider`, `deleteProvider`, `checkProvider` and `checkNewProvider` never run; the providers screens are untested; no fixtures for the provider routes |
| T3 | Claude Code's card: versions, plan, Sessions in claude agents | AgentCardsTest (all); ActionsTest.claudeAgentsSettingIsPostedAndApplied; SocketEventsTest.theClaudeSettingFollowsItsMessage; fixtures ws-claude.json, rest-claude.json; claude-agents.test.ts › "the setting from the phone…" | **covered** |
| T4 | Account: check, sign in on the phone or the server, the code, cancel | fixture ws-account.json; account.test.ts (the sign-in tests); http.test.ts › "the account is in the snapshot…" | **partial**: the app's `checkAccount`, `signIn`, `sendSignInCode` and `cancelSignIn` and their sheet are untested |
| T5 | This phone: card swipes, session top bar, typing, display | SettingsTest.cardSwipeRightPicksOpen, topBarToggleMoveAndReset, enterKeyNewLineAndBack; CardSwipeChoiceTest.picksAreKept; TopBarTest.itIsKeptAsOneLineAndReadBack; ServerSwitcherTest.namesStillShowAtALargeFont | **partial**: Display (text size, effects) has no test of its own |
| T6 | Refresh usage now (POST /v1/usage/refresh) | UsageRefreshTest (all); usage-probe.test.ts, usage-probe-pace.test.ts (all) | **partial**: the route has no HTTP test or fixture; the phone's call never runs |
| T7 | Testing checklist | http.test.ts › "the checklist needs its key…"; lan.test.ts › "a preview and the checklist…" | **partial**: the app's `checklist()` never runs |

## 16. App update

| # | Capability | Tests | Status |
|---|---|---|---|
| W1 | The server fetches GitHub releases and offers the APK | releases.test.ts (all) | **covered** |
| W2 | The phone's offer: from the snapshot, from `{t:"app"}`, a notification once per version, Settings → About | AboutUpdateTest (all); SocketEventsTest.aNewerAppOnTheServerIsOfferedAndWithdrawnWithoutReconnecting, aNewerAppOffScreenIsANotificationOncePerVersion, anOlderOrSameAppIsNoOffer; SettingsTest.aboutUpdateRowOffersTheUpdate; fixture ws-app.json | **covered** |
| W3 | Download, check and install | UpdatePolicyTest (all); CoreServersTest.theAppUpdateAsksForThePermissionThenDownloadsAndSaysWhatWentWrong; AllServersSettingsTest.eachServersBlockActsOnItsOwnServer; files.test.ts › "the APK download carries on…" | **partial**: the PackageInstaller hand-off and the real archive check are only modelled as events; neither runs |
| W4 | The install page for a browser (/download/android) | UpdatePolicyTest.aBrowserGetsThePageOnlyWhereItCouldOpenIt; files.test.ts, lan.test.ts, linux-cli.test.ts (the download tests) | **covered** |

## 17. Widget

| # | Capability | Tests | Status |
|---|---|---|---|
| WG1 | Needs you: rows per computer, what each waits on, offline rows open only | WidgetModelTest (all) | **covered** |
| WG2 | Deny and Allow from the widget, to that row's computer | MultiServerTest.anAnswerFromTheWidgetGoesToTheServerTheApprovalWaitsOn; WidgetModelTest.answeredHereHidesTheRowAndAnsweringShowsOnIt | **partial**: `WidgetSync`, the list's RemoteViews (`WidgetListService`) and its tap intents never run |
| WG3 | Redraws at most every 1.5 s, never with the screen off | none | **gap** |

## 18. Notifications

| # | Capability | Tests | Status |
|---|---|---|---|
| NT1 | An approval: a notification off screen, a banner on screen | ConnectTest.anApprovalInTheSnapshotIsAnnouncedOnceAsABannerOnScreen, offScreenAnApprovalInTheSnapshotIsANotification, aSnapshotWithoutAnApprovalLetsItsNotificationGo; SocketEventsTest.anApprovalOffScreenIsANotification; MultiServerTest.theWatchedHubsApprovalIsANotificationNotABanner | **covered** |
| NT2 | Allow, Deny, Reply and Answer from a notification | ServersTest.aNotificationGoesToTheServerItNames, anAnswerGoesWhereTheApprovalWaits; MultiServerTest.aNotificationNamesItsServerAndGoesThereEvenWithTheApprovalGone; WidgetModelTest.denyAndAllowOnlyWhereTheNotificationsRuleSaysSo | **partial**: only the choice of server is tested; the `NotifyActions` receiver (Allow, Deny, inline Reply, Answer) never runs |
| NT3 | Finished, error, job died, low battery, put-away rules | SocketEventsTest.aTurnThatEndsIsAFinishedNoticeAMomentLater, aTurnThatCarriesOnWithinTheMomentSaysNothing, anErrorIsSaidAtOnceAndOffScreenAsANotification, aDeadJobIsSaidOnce, aLowBatteryIsSaidOnceUntilItHasBeenCharged; PutAwayTest (all); PhrasesTest (all) | **partial**: automation notes and the sleep-soon and Backpack-ending notices are untested on the phone |
| NT4 | A tap opens the right session on the right server | NavTest (all); ServersTest.aNotificationGoesToTheServerItNames | **covered** |

## 19. Statistics

| # | Capability | Tests | Status |
|---|---|---|---|
| ST1 | Totals, ranges, the last 30 days as bars | StatsScreenTest (all); DayBarsTest (all); StatsTest (all); stats.test.ts (all) | **partial**: GET /v1/stats has no fixture (the app reads hand-written JSON) |

## 20. Mac-only features

On a Linux server these answer as unavailable (docs/PROTOCOL.md, "Mac-only features").

| # | Capability | Tests | Status |
|---|---|---|---|
| MO1 | Linux says they are unavailable, each with a sentence | platform.test.ts › "a Mac lacks nothing; Linux lacks the Mac-only features…"; wsl.test.ts › "a WSL snapshot's reasons…" | **partial**: how the phone hides or explains them is untested |
| MO2 | Stay awake: While working, Backpack, Off, +2 h | AnswersTest.readsWhetherBackpackCanBeExtended; awake.test.ts (all) | **partial**: the app's `setAwake` and `extendAwake`, the Stay awake section and the Backpack notice are untested; no fixtures for `awake`, `mac_sleep_soon` or `mac_backpack_ending` |
| MO3 | Battery | AnswersTest.lowBatteryIsTwentyPercentOrLessAndNotCharging; SocketEventsTest.aLowBatteryIsSaidOnceUntilItHasBeenCharged; battery.test.ts (all) | **partial**: no fixture for `{t:"battery"}` |
| MO4 | Open the terminal in a window on the Mac (POST …/open) | none | **gap** |

## 21. CLI and daemon admin

| # | Capability | Tests | Status |
|---|---|---|---|
| D1 | `hypernova setup` and `uninstall`: Node, the service, lingering, the integration | linux-cli.test.ts (all) | **covered** |
| D2 | `pair`, `status`, `doctor`, `logs`, `lan` | linux-cli.test.ts (all) | **covered** |
| D3 | WSL | wsl.test.ts (all); linux-cli.test.ts (the WSL tests) | **covered** |
| D4 | Hooks and the status line tap | hooks.test.ts (all) | **covered** |
| D5 | Shared terminals (the holder) | holder-exit.test.ts, holder-move.test.ts, term-scroll.test.ts (all) | **partial**: only a command's exit, a move and scrolling are tested; `hn ls` and `hn attach` have none |
| D6 | `claude agents` jobs | claude-agents.test.ts (all); job-deaths.test.ts (all) | **covered** |
| D7 | Claude Code's SDK, fetched not bundled | sdk.test.ts, sdk-missing.test.ts (all) | **covered** |
| D8 | Usage: the probe, merging readings, the pace | usage-probe.test.ts, usage-probe-pace.test.ts, usage-merge.test.ts, usage-rate.test.ts, usage-screen.test.ts, usage-no-five-hour.test.ts (all) | **covered** |
| D9 | Account and limits | account.test.ts, account-restart.test.ts, restart.test.ts, provider-env.test.ts (all) | **covered** |
| D10 | Where state and logs live; log rotation | platform.test.ts, linux-fixes.test.ts, util.test.ts (all) | **covered** |
| D11 | Security: no CORS, the hooks' secret, keys masked, credential files kept back | http.test.ts › "no web page may read the phone API…"; hooks.test.ts › "the hooks' route takes its own secret only…"; normalize.test.ts (the masking tests); secretfiles.test.ts (all); permission-rules.test.ts › "secrets in a settings file never leave…" | **covered** |

## Protocol without a contract fixture

The daemon already tests most of these in its own suites. What is missing is the shared golden file
that both sides must agree on.

- **WebSocket:** `battery`, `awake`, `mac_sleep_soon`, `mac_backpack_ending`, `devices`, `speech`,
  `speech.stop`, `speech.progress` (from the phone), `speaking`, `automation`, `automation.removed`,
  `automations`, `run`, `note`, `ping`/`pong`.
- **REST:** …/menu, …/open, …/switch/preview, …/switch, /v1/codex (GET, POST, refresh),
  /v1/codex/models, /v1/providers (all routes), /v1/files, /v1/file, /v1/preview, /v1/usage,
  /v1/usage/refresh, /v1/mac/awake, /v1/mac/files, /v1/stats, /v1/account/*, /v1/devices/me,
  /v1/device/cert, /v1/qa, /v1/speech/*, /v1/automations/*, /v1/health. (POST /v1/sessions, the
  approvals route, a prompt without hold, held edits, attachments, …/interrupt, …/terminal,
  …/continue, …/auto-continue, …/provider, …/usage, …/todos, …/rewind, …/blocked with retry and
  dismiss, GET /v1/commands, /v1/projects and /v1/folders have theirs now.)

## Biggest gaps

Ranked by how likely a move to Kotlin Multiplatform breaks it (most of `data/` moves from OkHttp to
Ktor; the screens move to Compose Multiplatform) and how soon the owner would notice.

1. ~~**Starting a new session** (N1).~~ Covered 2026-10-31. The owner does it many times a day, and its request body and
   refusals go over HTTP with no rig test and no fixture. *Layer 2:* an ActionsTest for `start` and
   its 409s; *layer 1:* `rest-sessions.json`.
2. ~~**Attachments** (Q7).~~ Covered 2026-10-31. File reading and the upload body are the most platform-specific HTTP code,
   and neither side tests the route. *Layer 2:* `upload` against FakeDaemon (bytes, name,
   `attachment_gone`); *daemon:* an HTTP test of POST /v1/attachments.
3. **Mutual TLS and the device certificate** (P3). The move rewrites TLS (Ktor engines, key
   storage), and every connection depends on it. *Layer 2:* an HTTPS mode for FakeDaemon
   (MockWebServer's `HeldCertificate`) that asks for a client certificate; *layer 1:* a fixture for
   /v1/device/cert.
4. **Pairing end to end** (P1). If it breaks, nothing works on a new install or a new server.
   *Layer 2:* `Pairer.pair` against FakeDaemon (the listener first, then `u`, a refused code).
5. ~~**Load earlier** (C4).~~ Covered 2026-10-31. A long chat would silently show only its latest events. *Layer 2:* a
   SocketEventsTest-style test of `hasEarlier` and `loadEarlier` with the limit fixture.
6. **Automations on the phone** (U1, U4, U5). Many REST calls and five messages with no rig test,
   no fixture and no screen. *Layer 1:* `ws-automation`, `ws-run`, `ws-note` fixtures; *layer 2:*
   AutomationsLive calls; *layer 3:* the tab and the editor.
7. **Files and What changed** (F1, F2, F3, F5). Whole screens with no screen test, and their routes
   never run from the phone. *Layer 3:* Files, FileViewer and ChangesScreen baselines; *layer 2:*
   `files`, `file`, `download` and `ChangesRepo`.
8. **Read aloud playback** (V3). Audio is per platform (`expect`/`actual`), and on the owner's Linux
   server it is the phone's own text-to-speech, which nothing runs. *Layer 2:* the play routes with
   a fake player; *layer 1:* fixtures for the speech routes and messages.
9. **Codex flows on the phone** (X1, X4, X5). The switch sheet, Codex's model switch and turning it
   on all go untested on the phone. *Layer 2:* `switchPreview`, `switchAgent`, `setCodexModel`,
   `setCodexEnabled`; *layer 1:* fixtures for …/switch/preview and /v1/codex/models.
10. **Agent boards and the agent screen** (G1, G3). Cursor polling is timing code that a move to
    other coroutines breaks easily, and the owner opens agents often. *Layer 2:* `agents()` and
    `agentEvents` with a cursor and `starting`; *layer 3:* AgentScreen.

Just behind them: the sky's drawing (H12, *layer 3:* a Home baseline with the sky on), account
sign-in (T4, *layer 2*), ~~blocked calls' Allow & retry (S5)~~ (covered), and the
notification receivers (NT2), which stay Android-only on a desktop-first move.
