import Foundation

/// `Hypernova --window-capture-selftest`: Telescope's window source decides whose windows it may
/// look at by the program the kernel loaded for each process, never by the name a process gives
/// itself (Core/WindowCapture.swift). Starts two short-lived processes from a scratch "session"
/// folder: a real program built there (with /usr/bin/cc), and /bin/sleep claiming in its
/// argv[0] to be that program, and checks that only the first counts; then the folders that may
/// never be a session's. Needs no Screen Recording and no daemon; exits 1 on a failure.
extension DevTools {
    static func windowCaptureSelfTest() -> Int32 {
        var failures = 0
        func check(_ ok: Bool, _ what: String) { print("\(ok ? "ok  " : "FAIL") \(what)"); if !ok { failures += 1 } }

        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("hypernova-window-selftest-\(getpid())")
        let project = dir.appendingPathComponent("project")
        defer { try? fm.removeItem(at: dir) }
        let built = project.appendingPathComponent(".build/release/App").path
        // A program really built in the folder, by the Command Line Tools' compiler (a copy of a
        // system program is killed on launch).
        do {
            try fm.createDirectory(at: project.appendingPathComponent(".build/release"), withIntermediateDirectories: true)
            let cc = Process()
            cc.executableURL = URL(fileURLWithPath: "/usr/bin/cc")
            cc.arguments = ["-x", "c", "-", "-o", built]
            let input = Pipe()
            cc.standardInput = input
            try cc.run()
            input.fileHandleForWriting.write(Data("#include <unistd.h>\nint main(void) { sleep(5); return 0; }\n".utf8))
            try input.fileHandleForWriting.close()
            cc.waitUntilExit()
            guard cc.terminationStatus == 0 else { throw CocoaError(.executableLoad) }
        } catch {
            print("FAIL building a program in the scratch folder: \(error.localizedDescription)")
            return 1
        }

        /// Starts `path` with this argv[0]; its pid, or 0.
        func start(_ path: String, argv0: String) -> pid_t {
            var pid: pid_t = 0
            let args: [UnsafeMutablePointer<CChar>?] = [strdup(argv0), strdup("5"), nil]
            defer { args.forEach { free($0) } }
            return posix_spawn(&pid, path, nil, nil, args, environ) == 0 ? pid : 0
        }
        let real = start(built, argv0: built)
        let spoof = start("/bin/sleep", argv0: built)
        defer { for p in [real, spoof] where p > 0 { kill(p, SIGKILL); var st: Int32 = 0; waitpid(p, &st, 0) } }
        check(real > 0 && spoof > 0, "two processes started")
        usleep(200_000)

        check(WindowCapture.executable(of: spoof) == "/bin/sleep", "the kernel's path for the spoofing process is /bin/sleep")
        let root = project.path
        let mine = WindowCapture.allowed([real, spoof], roots: [root])
        check(mine == [real], "only the program really built in the folder counts (got \(mine), built \(real), spoofed \(spoof))")
        check(WindowCapture.allowed([real, spoof], roots: []).isEmpty, "no folders, no processes (a daemon from before the check)")
        check(WindowCapture.allowed([getpid()], roots: [Bundle.main.bundlePath]).isEmpty, "never this app itself")

        let home = NSHomeDirectory()
        for bad in ["/", home, (home as NSString).deletingLastPathComponent, "/Applications", "/System/Applications", "/Library/Apple",
                    "/usr/local/bin", "/opt/homebrew", home + "/Applications", home + "/Library", home + "/Library/Mail"] {
            check(!WindowCapture.acceptableRoot(bad, home: home), "refused as a session's folder: \(bad)")
        }
        for good in [home + "/Programming/app", "/private/tmp/project",
                     home + "/Library/Developer/Xcode/DerivedData/Notes-abcdefghijklmnopqrstuvwxyzab/Build/Products"] {
            check(WindowCapture.acceptableRoot(good, home: home), "accepted: \(good)")
        }
        check(!WindowCapture.acceptableRoot(home + "/Library/Developer/Xcode/DerivedData", home: home), "refused: all of DerivedData")
        check(WindowCapture.allowed([real], roots: ["/Applications"]).isEmpty, "a session at /Applications names no app")
        print(failures == 0 ? "all window checks passed" : "\(failures) window check(s) failed")
        return failures == 0 ? 0 : 1
    }
}
