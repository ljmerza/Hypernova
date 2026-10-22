import SwiftUI

/// Settings' Files part, in the phone's words (its Settings → Files): **Browse outside projects**, off
/// by default. On, paired phones can open sessions' folders in the home folder too, read only; folders
/// with keys and private data, and files with passwords, stay locked whatever it says (the daemon's
/// files.ts and secretfiles.ts). One calm line, "Why it matters" behind a quiet link. The phone asks
/// for its fingerprint before turning it on; here the Mac is the owner's own.
struct FilesSettings: View {
    @EnvironmentObject private var model: AppModel
    @State private var why = false

    static let title = "Browse outside projects"
    static let line = "Open sessions’ folders in your home folder too, read only."
    static let more = "A paired phone can then read those folders and download their files. Folders with keys and private data (.ssh, keychains, mail, browsers’ profiles, app data) and files with passwords stay locked whatever this says, and folders outside your home folder stay closed."

    var body: some View {
        // An older daemon doesn't have the setting: nothing to show.
        if model.daemonUp, let files = model.status?.files {
            let on = files.browseOutsideProjects == true
            VStack(alignment: .leading, spacing: 9) {
                Seam(label: "Files", trail: on ? "outside projects too" : "projects only")
                HStack(spacing: 10) {
                    Text(Self.title).font(HNFont.small).foregroundStyle(Color.hnInk)
                    Spacer(minLength: 6)
                    HNSwitch(isOn: on, label: Self.title) { model.setBrowseOutsideProjects($0) }
                        .help(on ? "Phones open only your projects folder again" : "Phones can open sessions’ folders in your home folder too, read only")
                }
                .frame(height: 22)
                Text(Self.line).font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                if why {
                    Text(Self.more).font(HNFont.sans(11)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                        .transition(.opacity)
                } else {
                    Button("Why it matters") { withAnimation(.hnExpo(0.3)) { why = true } }
                        .buttonStyle(LinkStyle(color: .hnMuted))
                }
            }
        }
    }
}
