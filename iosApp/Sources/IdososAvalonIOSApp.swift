import SwiftUI
import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Se a monitorização estava ligada (por exemplo, o iOS fechou a app ou o
        // iPhone reiniciou), retoma-a automaticamente até o utilizador carregar em Parar.
        MonitorController.shared.resumeIfNeeded()
        return true
    }
}

@main
struct IdososAvalonIOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
