package com.prudnikweb.hypernova.ui.components

/** The words the shared drawing's desktop half can't read for itself: they're this app's own (strings_desktop.xml), not the phone's. Set as the app starts. */
object DeskUi {
    var diagramNote: () -> String = { "" }
}
